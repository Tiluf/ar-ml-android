package com.app.armlk;

import androidx.appcompat.app.AppCompatActivity;

import android.content.res.AssetManager;
import android.media.Image;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import com.app.armlk.render.RenderEngine;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.NotYetAvailableException;

import java.io.IOException;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = MainActivity.class.getSimpleName();

    private RenderEngine renderEngine;
    private boolean installRequested = false;
    private Session session;
    private GLSurfaceView surfaceView;
    private int viewPortWidth;
    private int viewPortHeight;
    private boolean viewPortChanged = false;
    private boolean hasSetTextureNames = false;
    private boolean useDepthForOcclusion = false;
    private boolean depthColorVisualizationEnabled = false;
    public static AssetManager assetManager;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        surfaceView = findViewById(R.id.glSurfaceView);

        assetManager = getAssets();
        prepSurface();
    }


    @Override
    protected void onResume() {
        super.onResume();
        Log.e(TAG, "onResume: ...");
        this.startArSession();

    }

    @Override
    protected void onPause() {
        super.onPause();
        if (session != null) {
            surfaceView.onPause();
            session.pause();
            Log.e(TAG, "onPause: ...");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (session != null) {
            session.close();
            session = null;
        }
    }


    private void prepSurface() {
        Log.e(TAG, "preparing GlSurface...");
        surfaceView.setPreserveEGLContextOnPause(true);
        surfaceView.setEGLContextClientVersion(3);
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        surfaceView.setRenderer(new GLSurfaceView.Renderer() {
            @Override
            public void onSurfaceCreated(GL10 gl, EGLConfig config) {
                Log.e(TAG, "onSurfaceCreated: ");
                renderEngine = new RenderEngine();

                GLES30.glEnable(GLES30.GL_BLEND);
            }

            @Override
            public void onSurfaceChanged(GL10 gl, int width, int height) {
                Log.e(TAG, "onSurfaceChanged: ");
                clear(0f, 0f, 0f, 1f);
                viewPortWidth = width;
                viewPortHeight = height;
                viewPortChanged = true;
            }

            @Override
            public void onDrawFrame(GL10 gl) {
                clear(0f, 0f, 0f, 1f);

                if (session == null)
                    return;

                if (!hasSetTextureNames) {
                    session.setCameraTextureNames(new int[]{renderEngine.getCameraColorTexture().getTextureId()});
                    hasSetTextureNames = true;
                }
                if (viewPortChanged) {
                    session.setDisplayGeometry(0, viewPortWidth, viewPortHeight);
                    viewPortChanged = false;
                }

                Frame frame;
                try {
                    frame = session.update();
                } catch (CameraNotAvailableException e) {
                    Log.e(TAG, "Camera not available during onDrawFrame", e);
                    return;
                }

                Camera camera = frame.getCamera();
                // Update BackgroundRenderer state to match the depth settings.
                try {
                    renderEngine.setUseDepthVisualization(depthColorVisualizationEnabled);
                    renderEngine.setUseOcclusion(useDepthForOcclusion);
                } catch (IOException e) {
                    Log.e(TAG, "Failed to read a required asset file", e);
                    return;
                }
                // BackgroundRenderer.updateDisplayGeometry must be called every frame to update the coordinates
                // used to draw the background camera image.
                renderEngine.updateDisplayGeometry(frame);

                if (camera.getTrackingState() == TrackingState.TRACKING && (useDepthForOcclusion ||depthColorVisualizationEnabled )) {
                    try (Image depthImage = frame.acquireDepthImage16Bits()) {
                        renderEngine.updateCameraDepthTexture(depthImage);
                    } catch (NotYetAvailableException e) {
                        // This normally means that depth data is not available yet. This is normal so we will not
                        // spam the logcat with this.
                    }
                }

                if (frame.getTimestamp() != 0) {
                    // Suppress rendering if the camera did not produce the first frame yet. This is to avoid
                    // drawing possible leftover data from previous sessions if the texture is reused.
                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
                    GLES30.glViewport(0, 0, viewPortWidth, viewPortHeight);
                    renderEngine.drawBackGround();
                }


            }
        });

        surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        surfaceView.setWillNotDraw(false);
    }

    public void clear(float r, float g, float b, float a) {
        GLES30.glClearColor(r, g, b, a);
        GLES30.glDepthMask(true);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
    }

    private void startArSession() {
        if (session == null) {
            try {
                switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    case INSTALL_REQUESTED:
                        installRequested = true;
                        return;
                    case INSTALLED:
                        break;
                }
                if (!PermissionHelper.hasCameraPermission(this)) {
                    PermissionHelper.requestCameraPermission(this);
                    return;
                }
                session = new Session(/* context= */ this);
            } catch (Exception exception) {
                exception.printStackTrace();
            }
        }
        //
        try {
            this.configSession();
            session.resume();
            Log.e(TAG, "startArSession: session Resumed...");
        } catch (Exception e) {
            e.printStackTrace();
        }

        surfaceView.onResume();
//        displayRotationHelper.onResume();
    }

    private void configSession() {
        Config config = session.getConfig();
        config.setLightEstimationMode(Config.LightEstimationMode.ENVIRONMENTAL_HDR);
        if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
            config.setDepthMode(Config.DepthMode.AUTOMATIC);
        } else {
            config.setDepthMode(Config.DepthMode.DISABLED);
        }
        config.setInstantPlacementMode(Config.InstantPlacementMode.DISABLED);
        session.configure(config);
        Log.e(TAG, "configSession:...");
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (!PermissionHelper.hasCameraPermission(this)) {
            // Use toast instead of snackbar here since the activity will exit.
            Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG)
                    .show();
            if (!PermissionHelper.shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                PermissionHelper.launchPermissionSettings(this);
            }
            finish();
        }
    }
}