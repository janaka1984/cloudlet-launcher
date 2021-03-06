// Copyright 2018 Carnegie Mellon University
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package edu.cmu.cs.gabriel;

import java.io.IOException;
import java.util.List;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import android.hardware.Camera.Size;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

public class CameraPreview extends SurfaceView implements SurfaceHolder.Callback {
    private static final String LOG_TAG = "CameraPreview";

    public SurfaceHolder mHolder;
    public Camera mCamera = null;
    public List<int[]> supportingFPS = null;
    public List<Camera.Size> supportingSize = null;

    public CameraPreview(Context context, AttributeSet attrs) {
        super(context, attrs);

        Log.v(LOG_TAG , "++CameraPreview");
        if (mCamera == null) {
            // Launching Camera App using voice command need to wait.
            // See more at https://code.google.com/p/google-glass-api/issues/list
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {}
            mCamera = Camera.open();
        }

        mHolder = getHolder();
        mHolder.addCallback(this);
        mHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
    }

    /**
     * This is only needed because once the application is onPaused, close() will be called, and during onResume,
     * the CameraPreview constructor is not called again.
     */
    public void checkCamera() {
        if (mCamera == null) {
            mCamera = Camera.open();
        }
    }

    public void close() {
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // TODO Auto-generated method stub
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    public void changeConfiguration(int[] range, Size imageSize) {
        Camera.Parameters parameters = mCamera.getParameters();
        if (range != null){
            Log.d("Config", "frame rate configuration : " + range[0] + "," + range[1]);
            parameters.setPreviewFpsRange(range[0], range[1]);
        }
        if (imageSize != null){
            Log.d("Config", "image size configuration : " + imageSize.width + "," + imageSize.height);
            parameters.setPreviewSize(imageSize.width, imageSize.height);
            parameters.setPictureFormat(ImageFormat.JPEG);
        }

        mCamera.setParameters(parameters);
    }

    public void surfaceCreated(SurfaceHolder holder) {
        Log.d(LOG_TAG, "++surfaceCreated");
        if (mCamera == null) {
            mCamera = Camera.open();
            // mCamera.setDisplayOrientation(90);
        }
        if (mCamera != null) {
            try {
                mCamera.setPreviewDisplay(holder);
                // set fps to capture
                Camera.Parameters parameters = mCamera.getParameters();
                List<int[]> supportedFps = parameters.getSupportedPreviewFpsRange();
                for (int[] range: supportedFps) {
                    Log.v(LOG_TAG, "available fps ranges:" + range[0] + ", " + range[1]);
                }
                if(this.supportingFPS == null)
                    this.supportingFPS = supportedFps;
                int index = 0, fpsDiff = Integer.MAX_VALUE;
                for (int i = 0; i < supportedFps.size(); i++){
                    int[] frameRate = supportedFps.get(i);
                    int diff = Math.abs(Const.MIN_FPS*1000 - frameRate[0]);
                    if (diff < fpsDiff){
                        fpsDiff = diff;
                        index = i;
                    }
                }
                int[] targetRange = supportedFps.get(index);

                // set resolution
                List<Camera.Size> supportedSizes = parameters.getSupportedPreviewSizes();
                for (Camera.Size size: supportedSizes) {
                    Log.v(LOG_TAG, "available sizes:" + size.width + ", " + size.height);
                }
                if(this.supportingSize == null)
                    this.supportingSize = supportedSizes;
                index = 0;
                int sizeDiff = Integer.MAX_VALUE;
                for (int i = 0; i < supportedSizes.size(); i++){
                    Camera.Size size = supportedSizes.get(i);
                    int diff = Math.abs(size.width - Const.IMAGE_WIDTH) + Math.abs(size.height - Const.IMAGE_HEIGHT);
                    if (diff < sizeDiff){
                        sizeDiff = diff;
                        index = i;
                    }
                }
                Camera.Size target_size = supportedSizes.get(index);
//              List<Integer> supportedFormat = parameters.getSupportedPreviewFormats();

                changeConfiguration(targetRange, target_size);
                mCamera.startPreview();

            } catch (IOException exception) {
                Log.e("Error", "exception:surfaceCreated Camera Open ");
                this.close();
            }
        }
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
        this.close();
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        Log.d(LOG_TAG, "surface changed");
        /*
         * Camera.Parameters parameters = mCamera.getParameters();
         * parameters.setPreviewSize(w, h); mCamera.setParameters(parameters);
         * mCamera.startPreview();
         */
    }

    public void setPreviewCallback(PreviewCallback previewCallback) {
        if (this.mCamera != null){
            mCamera.setPreviewCallback(previewCallback);
        }
    }

    public Camera getCamera() {
        return mCamera;
    }

}
