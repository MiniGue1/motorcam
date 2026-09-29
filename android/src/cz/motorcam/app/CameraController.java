package cz.motorcam.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Zadní kamera přes Camera2 API: náhled do TextureView a volitelně nahrávání videa (MP4).
 * Snímky pro neuronové sítě se berou přímo z TextureView (getBitmap) – jednoduché a
 * souřadnice detekcí pak přesně odpovídají tomu, co je vidět na displeji.
 */
public final class CameraController {
    public interface Listener {
        void onCameraError(String message);

        /** Nahrávání opravdu začalo (čas SystemClock.elapsedRealtimeNanos) – pro synchronizaci s CSV. */
        void onRecordingStarted(long elapsedNs);

        /** Nahrávání skončilo a soubor je uzavřený (null = selhalo). */
        void onRecordingStopped(File file);
    }

    private final Activity activity;
    private final TextureView view;
    private final Listener listener;
    private HandlerThread thread;
    private Handler handler;
    private CameraDevice device;
    private CameraCaptureSession session;
    private Size previewSize;
    private String cameraId;
    private int sensorOrientation = 90;
    private boolean hasStabilization, hasContinuousAf;
    private MediaRecorder recorder;
    private Size videoSize;
    public volatile boolean recording;
    public File videoFile;

    public CameraController(Activity activity, TextureView view, Listener listener) {
        this.activity = activity;
        this.view = view;
        this.listener = listener;
    }

    public void start() {
        thread = new HandlerThread("kamera");
        thread.start();
        handler = new Handler(thread.getLooper());
        if (view.isAvailable()) {
            open(view.getWidth(), view.getHeight());
        } else {
            view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                    open(w, h);
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                    configureTransform(w, h);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture st) {
                }
            });
        }
    }

    public void stop() {
        // nahrávání ukončit hned (synchronně), jinak by se video po zavření kamery poškodilo
        if (recording && recorder != null) {
            try {
                if (session != null) session.stopRepeating();
            } catch (Exception ignored) {
            }
            try {
                recorder.stop();
            } catch (RuntimeException ignored) {
            }
            abortRecording();
        }
        try {
            if (session != null) session.close();
        } catch (Exception ignored) {
        }
        session = null;
        if (device != null) device.close();
        device = null;
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
    }

    private void open(int w, int h) {
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        CameraManager mgr = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String id : mgr.getCameraIdList()) {
                CameraCharacteristics ch = mgr.getCameraCharacteristics(id);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;
                StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) continue;
                cameraId = id;
                Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
                sensorOrientation = so != null ? so : 90;
                hasStabilization = contains(ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES),
                        CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON);
                hasContinuousAf = contains(ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),
                        CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                previewSize = choose16x9(map.getOutputSizes(SurfaceTexture.class), 1920);
                videoSize = choose16x9(map.getOutputSizes(MediaRecorder.class), 1920);
                break;
            }
            if (cameraId == null) {
                listener.onCameraError("Nenalezena zadní kamera");
                return;
            }
            configureTransform(w, h);
            mgr.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice cd) {
                    device = cd;
                    createSession(null);
                }

                @Override
                public void onDisconnected(CameraDevice cd) {
                    cd.close();
                    device = null;
                }

                @Override
                public void onError(CameraDevice cd, int error) {
                    cd.close();
                    device = null;
                    listener.onCameraError("Chyba kamery " + error);
                }
            }, handler);
        } catch (CameraAccessException | SecurityException e) {
            listener.onCameraError("Kamera: " + e.getMessage());
        }
    }

    private static boolean contains(int[] arr, int v) {
        if (arr == null) return false;
        for (int a : arr) if (a == v) return true;
        return false;
    }

    /** Největší rozlišení 16:9 s šířkou nejvýš maxW (jinak největší dostupné). */
    private static Size choose16x9(Size[] sizes, int maxW) {
        Size best = null;
        for (Size s : sizes) {
            if (s.getWidth() > maxW || Math.abs(s.getWidth() * 9 - s.getHeight() * 16) > 16) continue;
            if (best == null || s.getWidth() > best.getWidth()) best = s;
        }
        if (best != null) return best;
        for (Size s : sizes) if (s.getWidth() <= maxW && (best == null || s.getWidth() > best.getWidth())) best = s;
        return best != null ? best : sizes[0];
    }

    /** Otočení a oříznutí náhledu podle orientace displeje (vyplní celou plochu). */
    void configureTransform(int vw, int vh) {
        if (previewSize == null || vw == 0 || vh == 0) return;
        final int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        final Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, vw, vh);
        RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        float cx = viewRect.centerX(), cy = viewRect.centerY();
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max((float) vh / previewSize.getHeight(), (float) vw / previewSize.getWidth());
            matrix.postScale(scale, scale, cx, cy);
            matrix.postRotate(90 * (rotation - 2), cx, cy);
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180, cx, cy);
        }
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                view.setTransform(matrix);
            }
        });
    }

    private void createSession(Surface recorderSurface) {
        if (device == null) return;
        try {
            SurfaceTexture st = view.getSurfaceTexture();
            if (st == null) return;
            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            final Surface preview = new Surface(st);
            final List<Surface> targets = new ArrayList<>();
            targets.add(preview);
            if (recorderSurface != null) targets.add(recorderSurface);
            final CaptureRequest.Builder req = device.createCaptureRequest(
                    recorderSurface != null ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW);
            for (Surface s : targets) req.addTarget(s);
            req.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            if (hasContinuousAf) req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            if (hasStabilization) {
                req.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
            }
            final boolean startRecorder = recorderSurface != null;
            device.createCaptureSession(targets, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    session = s;
                    try {
                        s.setRepeatingRequest(req.build(), null, handler);
                        if (startRecorder && recorder != null) {
                            recorder.start();
                            recording = true;
                            listener.onRecordingStarted(android.os.SystemClock.elapsedRealtimeNanos());
                        }
                    } catch (Exception e) {
                        listener.onCameraError("Náhled: " + e.getMessage());
                        if (startRecorder) abortRecording();
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession s) {
                    listener.onCameraError("Nepodařilo se nastavit kameru");
                    if (startRecorder) {
                        abortRecording();
                        createSession(null);
                    }
                }
            }, handler);
        } catch (Exception e) {
            listener.onCameraError("Relace kamery: " + e.getMessage());
        }
    }

    /** Spustí nahrávání do souboru (bez zvuku). */
    public void startRecording(final File file) {
        if (device == null || recording || handler == null) return;
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (session != null) session.close();
                    session = null;
                    int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
                    MediaRecorder r = new MediaRecorder();
                    r.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                    r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                    r.setOutputFile(file);
                    r.setVideoEncodingBitRate(12_000_000);
                    r.setVideoFrameRate(30);
                    r.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
                    r.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                    r.setOrientationHint((sensorOrientation - rotation * 90 + 360) % 360);
                    r.prepare();
                    recorder = r;
                    videoFile = file;
                    createSession(r.getSurface());
                } catch (Exception e) {
                    listener.onCameraError("Nahrávání: " + e.getMessage());
                    abortRecording();
                    createSession(null);
                }
            }
        });
    }

    public void stopRecording() {
        if (handler == null) return;
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (session != null) session.stopRepeating();
                } catch (Exception ignored) {
                }
                File done = videoFile;
                try {
                    if (recorder != null && recording) recorder.stop();
                } catch (RuntimeException e) {
                    listener.onCameraError("Video se nepodařilo uložit (příliš krátké?)");
                    done = null;
                }
                abortRecording();
                listener.onRecordingStopped(done);
                createSession(null);
            }
        });
    }

    private void abortRecording() {
        recording = false;
        if (recorder != null) {
            try {
                recorder.reset();
                recorder.release();
            } catch (Exception ignored) {
            }
        }
        recorder = null;
    }
}
