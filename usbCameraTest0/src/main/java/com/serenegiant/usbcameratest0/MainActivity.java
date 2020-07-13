/*
 *  UVCCamera
 *  library and sample to access to UVC web camera on non-rooted Android device
 *
 * Copyright (c) 2014-2017 saki t_saki@serenegiant.com
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 *  All files in the folder are under this Apache License, Version 2.0.
 *  Files in the libjpeg-turbo, libusb, libuvc, rapidjson folder
 *  may have a different license, see the respective files.
 */

package com.serenegiant.usbcameratest0;

import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.widget.ImageButton;
import android.widget.Toast;

import com.serenegiant.common.BaseActivity;
import com.serenegiant.usb.CameraDialog;
import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.USBMonitor.OnDeviceConnectListener;
import com.serenegiant.usb.USBMonitor.UsbControlBlock;
import com.serenegiant.usb.UVCCamera;

import java.nio.ByteBuffer;
import java.util.List;


public class MainActivity extends BaseActivity implements CameraDialog.CameraDialogParent {
	private static final boolean DEBUG = true;	// TODO set false when production
	private static final String TAG = "MainActivity";

    private final Object mSync = new Object();
    // for accessing USB and USB camera
    private USBMonitor mUSBMonitor;
	private UVCCamera mUVCCamera;
	private SurfaceView mUVCCameraView;
	// for open&start / stop&close camera preview
	private ImageButton mCameraButton;
	private Surface mPreviewSurface;
	private boolean isActive, isPreview;

	@Override
	protected void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		requestWindowFeature(Window.FEATURE_NO_TITLE);
		setContentView(R.layout.activity_main);
		mCameraButton = (ImageButton)findViewById(R.id.camera_button);
		mCameraButton.setOnClickListener(mOnClickListener);

		mUVCCameraView = (SurfaceView)findViewById(R.id.camera_surface_view);
		mUVCCameraView.getHolder().addCallback(mSurfaceViewCallback);

		mUSBMonitor = new USBMonitor(this, mOnDeviceConnectListener);
	}

	@Override
	protected void onStart() {
		super.onStart();
		if (DEBUG) Log.v(TAG, "onStart:");
		synchronized (mSync) {
			if (mUSBMonitor != null) {
				mUSBMonitor.register();
			}
		}
	}

	@Override
	protected void onStop() {
		if (DEBUG) Log.v(TAG, "onStop:");
		synchronized (mSync) {
			if (mUSBMonitor != null) {
				mUSBMonitor.unregister();
			}
		}
		super.onStop();
	}

	@Override
	protected void onDestroy() {
		if (DEBUG) Log.v(TAG, "onDestroy:");
		synchronized (mSync) {
			isActive = isPreview = false;
			if (mUVCCamera != null) {
				mUVCCamera.destroy();
				mUVCCamera = null;
			}
			if (mUSBMonitor != null) {
				mUSBMonitor.destroy();
				mUSBMonitor = null;
			}
		}
		mUVCCameraView = null;
		mCameraButton = null;
		super.onDestroy();
	}

	private final OnClickListener mOnClickListener = new OnClickListener() {
		@Override
		public void onClick(final View view) {
			if (mUVCCamera == null) {
				// XXX calling CameraDialog.showDialog is necessary at only first time(only when app has no permission).
				CameraDialog.showDialog(MainActivity.this);
			} else {
				synchronized (mSync) {
					mUVCCamera.destroy();
					mUVCCamera = null;
					isActive = isPreview = false;
				}
			}
		}
	};

	private final OnDeviceConnectListener mOnDeviceConnectListener = new OnDeviceConnectListener() {
		@Override
		public void onAttach(final UsbDevice device) {
			if (DEBUG) Log.v(TAG, "onAttach:");
			Toast.makeText(MainActivity.this, "USB_DEVICE_ATTACHED", Toast.LENGTH_SHORT).show();
		}

		@Override
		public void onConnect(final UsbDevice device, final UsbControlBlock ctrlBlock, final boolean createNew) {
			if (DEBUG) Log.v(TAG, "onConnect:");
			synchronized (mSync) {
				if (mUVCCamera != null) {
					mUVCCamera.destroy();
				}
				isActive = isPreview = false;
			}
			queueEvent(new Runnable() {
				@Override
				public void run() {
					synchronized (mSync) {
						final UVCCamera camera = new UVCCamera();
						camera.open(ctrlBlock);
						if (DEBUG) Log.i(TAG, "supportedSize:" + camera.getSupportedSize());

						List<Size> mjpeg_camera_sizes = camera.getSupportedSizeList(UVCCamera.FRAME_FORMAT_MJPEG);
						List<Size> yuv_camera_sizes = camera.getSupportedSizeList(UVCCamera.FRAME_FORMAT_YUYV);

						// Pick the size that is closest to our required resolution
						int required_width = 1920;
						int required_height = 1080;
						int required_area = required_width * required_height;

						int preview_width = 0;
						int preview_height = 0;
						int error = Integer.MAX_VALUE; // trying to get this as small as possible

						for (Size s : mjpeg_camera_sizes) {
							// calculate the area for each camera size
							int s_area = s.width * s.height;
							// calculate the difference between this size and the target size
							int abs_error = Math.abs(s_area - required_area);
							// check if the abs_error is smaller than what we have already
							// then use the new size
							if (abs_error < error){
								preview_width = s.width;
								preview_height = s.height;
								error = abs_error;
							}
						}



						try {
							camera.setPreviewSize(preview_width, preview_height, UVCCamera.FRAME_FORMAT_MJPEG);

						} catch (final IllegalArgumentException e) {
							Toast.makeText(MainActivity.this, "Trying YUV Mode", Toast.LENGTH_SHORT).show();
							try {
								// fallback to YUV mode
								// find closest matching size
								// Pick the size that is closest to our required resolution
								int yuv_preview_width = 0;
								int yuv_preview_height = 0;
								int yuv_error = Integer.MAX_VALUE; // trying to get this as small as possible

								for (Size s : yuv_camera_sizes) {
									// calculate the area for each camera size
									int s_area = s.width * s.height;
									// calculate the difference between this size and the target size
									int abs_error = Math.abs(s_area - required_area);
									// check if the abs_error is smaller than what we have already
									// then use the new size
									if (abs_error < yuv_error) {
										yuv_preview_width = s.width;
										yuv_preview_height = s.height;
										yuv_error = abs_error;
									}
								}

								camera.setPreviewSize(yuv_preview_width, yuv_preview_height, 0, 60);
							} catch (final IllegalArgumentException e1) {
								camera.destroy();
								return;
							}
						}
						mPreviewSurface = mUVCCameraView.getHolder().getSurface();
						if (mPreviewSurface != null) {
							isActive = true;
							camera.setPreviewDisplay(mPreviewSurface);
							//Toast.makeText(MainActivity.this, "We made it to startpreview", Toast.LENGTH_SHORT).show();
							camera.startPreview();
							isPreview = true;
						}
						synchronized (mSync) {
							mUVCCamera = camera;
						}
					}
				}
			}, 0);
		}

		@Override
		public void onDisconnect(final UsbDevice device, final UsbControlBlock ctrlBlock) {
			if (DEBUG) Log.v(TAG, "onDisconnect:");
			// XXX you should check whether the comming device equal to camera device that currently using
			queueEvent(new Runnable() {
				@Override
				public void run() {
					synchronized (mSync) {
						if (mUVCCamera != null) {
							mUVCCamera.close();
							if (mPreviewSurface != null) {
								mPreviewSurface.release();
								mPreviewSurface = null;
							}
							isActive = isPreview = false;
						}
					}
				}
			}, 0);
		}

		@Override
		public void onDettach(final UsbDevice device) {
			if (DEBUG) Log.v(TAG, "onDettach:");
			Toast.makeText(MainActivity.this, "USB_DEVICE_DETACHED", Toast.LENGTH_SHORT).show();
		}

		@Override
		public void onCancel(final UsbDevice device) {
		}
	};

	/**
	 * to access from CameraDialog
	 * @return
	 */
	@Override
	public USBMonitor getUSBMonitor() {
		return mUSBMonitor;
	}

	@Override
	public void onDialogResult(boolean canceled) {
		if (canceled) {
			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					// FIXME
				}
			}, 0);
		}
	}

	private final SurfaceHolder.Callback mSurfaceViewCallback = new SurfaceHolder.Callback() {
		@Override
		public void surfaceCreated(final SurfaceHolder holder) {
			if (DEBUG) Log.v(TAG, "surfaceCreated:");
		}

		@Override
		public void surfaceChanged(final SurfaceHolder holder, final int format, final int width, final int height) {
			if ((width == 0) || (height == 0)) return;
			if (DEBUG) Log.v(TAG, "surfaceChanged:");
			mPreviewSurface = holder.getSurface();
			synchronized (mSync) {
				if (isActive && !isPreview && (mUVCCamera != null)) {
					mUVCCamera.setPreviewDisplay(mPreviewSurface);
					mUVCCamera.startPreview();
					isPreview = true;
				}
			}
		}

		@Override
		public void surfaceDestroyed(final SurfaceHolder holder) {
			if (DEBUG) Log.v(TAG, "surfaceDestroyed:");
			synchronized (mSync) {
				if (mUVCCamera != null) {
					mUVCCamera.stopPreview();
				}
				isPreview = false;
			}
			mPreviewSurface = null;
		}
	};
}
