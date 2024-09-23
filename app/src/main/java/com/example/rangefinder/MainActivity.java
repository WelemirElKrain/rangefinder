package com.example.rangefinder;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.rangefinder.helpers.CameraPermissionHelper;
import com.example.rangefinder.helpers.DisplayRotationHelper;
import com.example.rangefinder.helpers.FullScreenHelper;
import com.example.rangefinder.helpers.SnackbarHelper;
import com.example.rangefinder.rendering.BackgroundRenderer;

import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Point;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;

import java.io.IOException;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MainActivity extends AppCompatActivity implements GLSurfaceView.Renderer {
    private static final String TAG = MainActivity.class.getSimpleName();

    String message = new String();
    Exception exception = new Exception();
    private GLSurfaceView surfaceView;
    private Session session;
    private DisplayRotationHelper displayRotationHelper;
    private final SnackbarHelper messageSnackbarHelper = new SnackbarHelper();

    private boolean installRequested;
    private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();
    private boolean isTaped = false;
    private float[] tapCoordinates = new float[2];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        surfaceView = findViewById(R.id.surfaceview);
        displayRotationHelper = new DisplayRotationHelper(this);

        surfaceView.setPreserveEGLContextOnPause(true);
        surfaceView.setEGLContextClientVersion(2);
        surfaceView.setRenderer(this);
        surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        surfaceView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                isTaped = true;
                tapCoordinates[0] = event.getX();
                tapCoordinates[1] = event.getY();
                Log.d(TAG, "Tapped at: (" + tapCoordinates[0] + ", " + tapCoordinates[1] + ")");
            }
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        exception = null;
        message = null;
        if (session == null) {
            try {
                switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    case INSTALL_REQUESTED:
                        installRequested = true;
                        return;
                    case INSTALLED:
                        break;
                }

                if (!CameraPermissionHelper.hasCameraPermission(this)) {
                    CameraPermissionHelper.requestCameraPermission(this);
                    return;
                }

                session = new Session(this);
                Config config = new Config(session);
                session.configure(config);

            } catch (UnavailableArcoreNotInstalledException
                     | UnavailableUserDeclinedInstallationException e) {
                message = "Please install ARCore";
                exception = e;
            } catch (UnavailableApkTooOldException e) {
                message = "Please update ARCore";
                exception = e;
            } catch (UnavailableSdkTooOldException e) {
                message = "Please update this app";
                exception = e;
            } catch (UnavailableDeviceNotCompatibleException e) {
                message = "This device does not support AR";
                exception = e;
            } catch (Exception e) {
                message = "Failed to create AR session";
                exception = e;
            }

            try {
                session.resume();
            } catch (CameraNotAvailableException e) {
                Toast.makeText(this, "Camera not available. Try restarting the app.", Toast.LENGTH_LONG).show();
                session = null;
                return;
            }
        }

        surfaceView.onResume();
        displayRotationHelper.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (session != null) {
            displayRotationHelper.onPause();
            surfaceView.onPause();
            session.pause();
        }
    }

    @Override
    protected void onDestroy() {
        if (session != null) {
            session.close();
            session = null;
        }

        super.onDestroy();
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);
        try {
            backgroundRenderer.createOnGlThread(this);
        } catch (IOException e) {
            Log.e(TAG, "Failed to read an asset file", e);
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        displayRotationHelper.onSurfaceChanged(width, height);
        GLES20.glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        if (session == null) return;

        displayRotationHelper.updateSessionIfNeeded(session);

        try {
            session.setCameraTextureName(backgroundRenderer.getTextureId());
            Frame frame = session.update();
            backgroundRenderer.draw(frame);

            if (isTaped) {
                handleTap(frame);
            }

        } catch (Throwable t) {
            Log.e(TAG, "Exception on the OpenGL thread", t);
        }
    }

    private void handleTap(Frame frame) {
        isTaped = false;
        if (frame != null) {
            // Выполняем hitTest по координатам нажатия
            List<HitResult> hitResults = frame.hitTest(tapCoordinates[0], tapCoordinates[1]);

            for (HitResult hitResult : hitResults) {
                Trackable trackable = hitResult.getTrackable();

                if (trackable instanceof Point) {
                    Point point = (Point) trackable;
                    if (point.getOrientationMode() == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL) {
                        Pose hitPose = hitResult.getHitPose();
                        Pose cameraPose = frame.getCamera().getPose();
                        float distance = calculateDistanceToPoint(hitPose, cameraPose);
                        Log.d("ARCore", "Расстояние до ключевой точки: " + distance);
                        message = "Tapped at: (" + tapCoordinates[0] + ", " + tapCoordinates[1] + ")" + " depth: " + distance;
                        messageSnackbarHelper.showMessage(this, message);
                        return; // Найдена ключевая точка, останавливаем обработку
                    }
                }
            }

            // Если нет пересечения с ключевой точкой, ищем ближайшую ключевую точку среди всех точек
            float minDistance = Float.MAX_VALUE;
            Point nearestPoint = null;

            for (Point point : session.getAllTrackables(Point.class)) {
                Pose pointPose = point.getPose();
                float distance = calculateDistanceToPoint(pointPose, frame.getCamera().getPose());
                if (distance < minDistance) {
                    minDistance = distance;
                    nearestPoint = point;
                }
            }

            if (nearestPoint != null) {
                Log.d("ARCore", "Ближайшая ключевая точка найдена. Расстояние: " + minDistance);
                message = "Nearest point, distance: " + minDistance;
                messageSnackbarHelper.showMessage(this, message);
            } else {
                Log.d("ARCore", "Ключевые точки не найдены.");
                message = "Key point not found";
                messageSnackbarHelper.showMessage(this, message);
            }
        }
    }

    private static float calculateDistanceToPoint(Pose pointPose, Pose cameraPose) {
        float cameraX = cameraPose.tx();
        float cameraY = cameraPose.ty();
        float cameraZ = cameraPose.tz();

        float pointX = pointPose.tx();
        float pointY = pointPose.ty();
        float pointZ = pointPose.tz();

        return (float) Math.sqrt(
                Math.pow(cameraX - pointX, 2) +
                        Math.pow(cameraY - pointY, 2) +
                        Math.pow(cameraZ - pointZ, 2)
        );
    }
}

