package io.flutter.plugins.camera;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.view.Surface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;
import io.flutter.plugin.common.PluginRegistry.Registrar;
import io.flutter.view.FlutterView;

import static android.view.OrientationEventListener.ORIENTATION_UNKNOWN;

public class CameraPlugin implements MethodCallHandler {
    private static final int CAMERA_REQUEST_ID = 513469796;
    private static final int CAMERA_REQUEST_ID2 = 1000;
    private static final String TAG = "CameraPlugin";

    private static CameraManager cameraManager;
    private final FlutterView view;
    private Camera camera;
    private Activity activity;
    private Registrar registrar;
    private Application.ActivityLifecycleCallbacks activityLifecycleCallbacks;
    // The code to run after requesting camera permissions.
    private Runnable cameraPermissionContinuation;
    private boolean requestingPermission;
    private int currentOrientation = ORIENTATION_UNKNOWN;

    private class RequestPermissionsListener
            implements PluginRegistry.RequestPermissionsResultListener {
        private MethodChannel.Result mPendingResult;

        @Override
        public boolean onRequestPermissionsResult(int id, String[] strings, int[] ints) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "id : " + id);
                for (String string : strings) {
                    Log.d(TAG, " Strings : " + string);
                }

                for (int intTemp : ints) {
                    Log.d(TAG, "int :" + intTemp);
                }
            }
            if (id == CAMERA_REQUEST_ID2) {
                int count = strings == null ? 0 : strings.length;
                for (int i = 0; i < count; ++i) {
                    String permissionName = strings[i];
                    int temp = ints[i];
                    if (permissionName.equals(Manifest.permission.CAMERA)) {
                        mPendingResult.success(temp == PackageManager.PERMISSION_GRANTED);
                    }
                }
                mPendingResult = null;
                return true;
            }
            return false;
        }

        public void setPendingResult(MethodChannel.Result result) {
            mPendingResult = result;
        }
    }

    private RequestPermissionsListener mRequestPermissionsListener =
            new RequestPermissionsListener();

    private CameraPlugin(Registrar registrar, FlutterView view, Activity activity) {
        this.registrar = registrar;
        this.view = view;
        this.activity = activity;

        registrar.addRequestPermissionsResultListener(new CameraRequestPermissionsListener());
        registrar.addRequestPermissionsResultListener(mRequestPermissionsListener);

        this.activityLifecycleCallbacks = new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}

            @Override
            public void onActivityStarted(Activity activity) {}

            @Override
            public void onActivityResumed(Activity activity) {
                boolean wasRequestingPermission = requestingPermission;
                if (requestingPermission) {
                    requestingPermission = false;
                }
                if (activity != CameraPlugin.this.activity) {
                    return;
                }
                if (camera != null && !wasRequestingPermission) {
                    camera.open(null);
                }
            }

            @Override
            public void onActivityPaused(Activity activity) {
                if (activity == CameraPlugin.this.activity) {
                    if (camera != null) {
                        camera.close();
                    }
                }
            }

            @Override
            public void onActivityStopped(Activity activity) {
                if (activity == CameraPlugin.this.activity) {
                    if (camera != null) {
                        camera.close();
                    }
                }
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

            @Override
            public void onActivityDestroyed(Activity activity) {}
        };
    }

    public static void registerWith(Registrar registrar) {
        if (registrar.activity() == null) {
            // When a background flutter view tries to register the plugin, the registrar has no
            // activity. We stop the registration process as this plugin is foreground only.
            return;
        }
        final MethodChannel channel =
                new MethodChannel(registrar.messenger(), "plugins.flutter.io/camera");

        cameraManager =
                (CameraManager) registrar.activity().getSystemService(Context.CAMERA_SERVICE);

        channel.setMethodCallHandler(
                new CameraPlugin(registrar, registrar.view(), registrar.activity()));
    }

    @Override
    public void onMethodCall(MethodCall call, final Result result) {
        switch (call.method) {
            case "requestForPermission":
                // API >=23
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (activity.checkSelfPermission(Manifest.permission.CAMERA)
                            == PackageManager.PERMISSION_GRANTED) {
                        result.success(true);
                    } else {
                        mRequestPermissionsListener.setPendingResult(result);
                        registrar.activity().requestPermissions(
                                new String[] {Manifest.permission.CAMERA,
                                        Manifest.permission.RECORD_AUDIO},
                                CAMERA_REQUEST_ID2);
                    }

                } else {
                    result.success(true);
                }
                break;
            case "availableCameras":
                try {
                    String[] cameraNames = cameraManager.getCameraIdList();
                    List<Map<String, Object>> cameras = new ArrayList<>();
                    for (String cameraName : cameraNames) {
                        HashMap<String, Object> details = new HashMap<>();
                        CameraCharacteristics characteristics =
                                cameraManager.getCameraCharacteristics(cameraName);
                        details.put("name", cameraName);
                        @SuppressWarnings("ConstantConditions")
                        int sensorOrientation =
                                characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                        details.put("sensorOrientation", sensorOrientation);

                        int lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
                        switch (lensFacing) {
                            case CameraMetadata.LENS_FACING_FRONT:
                                details.put("lensFacing", "front");
                                break;
                            case CameraMetadata.LENS_FACING_BACK:
                                details.put("lensFacing", "back");
                                break;
                            case CameraMetadata.LENS_FACING_EXTERNAL:
                                details.put("lensFacing", "external");
                                break;
                        }
                        cameras.add(details);
                    }
                    result.success(cameras);
                } catch (CameraAccessException e) {
                    result.error("cameraAccess", e.getMessage(), null);
                }
                break;
            case "initialize": {
                String cameraName = call.argument("cameraName");
                String resolutionPreset = call.argument("resolutionPreset");
                if (camera != null) {
                    camera.close();
                }
                camera = new Camera(cameraName, resolutionPreset, result);
                this.activity.getApplication().registerActivityLifecycleCallbacks(
                        this.activityLifecycleCallbacks);
                break;
            }
            case "takePicture": {
                camera.takePicture((String) call.argument("path"), result);
                break;
            }
            case "prepareForVideoRecording": {
                // This optimization is not required for Android.
                result.success(null);
                break;
            }
            case "startVideoRecording": {
                final String filePath = call.argument("filePath");
                camera.startVideoRecording(filePath, result);
                break;
            }
            case "stopVideoRecording": {
                camera.stopVideoRecording(result);
                break;
            }
            case "startImageStream": {
                try {
                    camera.startPreviewWithImageStream();
                    camera.mIsCanStartImageStream = true;
                    result.success(null);
                } catch (CameraAccessException e) {
                    result.error("CameraAccess", e.getMessage(), null);
                }
                break;
            }
            case "stopImageStream": {
                try {
                    camera.startPreview();
                    result.success(null);
                } catch (NullPointerException | CameraAccessException e) {
                    result.error("CameraAccess", e.getMessage(), null);
                }
                break;
            }
            case "setCanStartImageStream":
                camera.mIsCanStartImageStream = true;
                result.success(null);
                break;
            case "dispose": {
                if (camera != null) {
                    camera.dispose();
                }
                if (this.activity != null && this.activityLifecycleCallbacks != null) {
                    this.activity.getApplication().unregisterActivityLifecycleCallbacks(
                            this.activityLifecycleCallbacks);
                }
                result.success(null);
                break;
            }
            case "turnOn":
                turnOnOrOff(true, result);
                break;
            case "turnOff":
                turnOnOrOff(false, result);
                break;
            case "hasLamp":
                result.success(true);
                break;
            default:
                result.notImplemented();
                break;
        }
    }

    private void turnOnOrOff(boolean isTurnOn, final Result result) {
        boolean isSuccess = true;
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "turnOnOrOff : " + isTurnOn);
        }
        camera.captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                isTurnOn ? CaptureRequest.CONTROL_AE_MODE_ON : CaptureRequest.CONTROL_AE_MODE_OFF);
        camera.captureRequestBuilder.set(CaptureRequest.FLASH_MODE,
                isTurnOn ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
        try {
            camera.cameraCaptureSession.setRepeatingRequest(
                    camera.captureRequestBuilder.build(), null, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
            isSuccess = false;
        }
        result.success(isSuccess);
    }

    private static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow.
            return Long.signum((long) lhs.getWidth() * lhs.getHeight()
                    - (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    private class CameraRequestPermissionsListener
            implements PluginRegistry.RequestPermissionsResultListener {
        @Override
        public boolean onRequestPermissionsResult(
                int id, String[] permissions, int[] grantResults) {
            if (id == CAMERA_REQUEST_ID) {
                if (cameraPermissionContinuation != null) {
                    cameraPermissionContinuation.run();
                }
                return true;
            }
            return false;
        }
    }

    private class Camera {
        private final FlutterView.SurfaceTextureEntry textureEntry;
        private CameraDevice cameraDevice;
        private CameraCaptureSession cameraCaptureSession;
        private EventChannel.EventSink eventSink;
        private ImageReader pictureImageReader;
        private ImageReader imageStreamReader;
        private int sensorOrientation;
        private boolean isFrontFacing;
        private String cameraName;
        private Size captureSize;
        private Size previewSize;
        private CaptureRequest.Builder captureRequestBuilder;
        private Size videoSize;
        private MediaRecorder mediaRecorder;
        private boolean recordingVideo;
        private boolean mIsCanStartImageStream;

        Camera(final String cameraName, final String resolutionPreset,
                @NonNull final Result result) {
            this.cameraName = cameraName;
            textureEntry = view.createSurfaceTexture();

            registerEventChannel();

            try {
                int minHeight;
                switch (resolutionPreset) {
                    case "high":
                        minHeight = 720;
                        break;
                    case "medium":
                        minHeight = 480;
                        break;
                    case "low":
                        minHeight = 240;
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown preset: " + resolutionPreset);
                }

                CameraCharacteristics characteristics =
                        cameraManager.getCameraCharacteristics(cameraName);
                StreamConfigurationMap streamConfigurationMap =
                        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                // noinspection ConstantConditions
                sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                // noinspection ConstantConditions
                isFrontFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                        == CameraMetadata.LENS_FACING_FRONT;
                computeBestCaptureSize(streamConfigurationMap);

                if (BuildConfig.DEBUG) {
                    Log.d(TAG,
                            "sensorOrientation : " + sensorOrientation + ", isFrontFacing : "
                                    + isFrontFacing + ", minHeight : " + minHeight
                                    + ", captureSize : " + captureSize.toString());
                }

                computeBestPreviewAndRecordingSize(streamConfigurationMap, minHeight, captureSize);

                if (cameraPermissionContinuation != null) {
                    result.error("cameraPermission", "Camera permission request ongoing", null);
                }
                cameraPermissionContinuation = new Runnable() {
                    @Override
                    public void run() {
                        cameraPermissionContinuation = null;
                        if (!hasCameraPermission()) {
                            result.error("cameraPermission",
                                    "MediaRecorderCamera permission not granted", null);
                            return;
                        }
                        if (!hasAudioPermission()) {
                            result.error("cameraPermission",
                                    "MediaRecorderAudio permission not granted", null);
                            return;
                        }
                        open(result);
                    }
                };
                requestingPermission = false;
                if (hasCameraPermission() && hasAudioPermission()) {
                    cameraPermissionContinuation.run();
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        requestingPermission = true;
                        registrar.activity().requestPermissions(
                                new String[] {Manifest.permission.CAMERA,
                                        Manifest.permission.RECORD_AUDIO},
                                CAMERA_REQUEST_ID);
                    }
                }
            } catch (CameraAccessException e) {
                result.error("CameraAccess", e.getMessage(), null);
            } catch (IllegalArgumentException e) {
                result.error("IllegalArgumentException", e.getMessage(), null);
            }
        }

        private void registerEventChannel() {
            new EventChannel(registrar.messenger(),
                    "flutter.io/cameraPlugin/cameraEvents" + textureEntry.id())
                    .setStreamHandler(new EventChannel.StreamHandler() {
                        @Override
                        public void onListen(Object arguments, EventChannel.EventSink eventSink) {
                            Camera.this.eventSink = eventSink;
                        }

                        @Override
                        public void onCancel(Object arguments) {
                            Camera.this.eventSink = null;
                        }
                    });
        }

        private boolean hasCameraPermission() {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                    || activity.checkSelfPermission(Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED;
        }

        private boolean hasAudioPermission() {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                    || registrar.activity().checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
        }

        private void computeBestPreviewAndRecordingSize(
                StreamConfigurationMap streamConfigurationMap, int minHeight, Size captureSize) {
            Size[] sizes = streamConfigurationMap.getOutputSizes(SurfaceTexture.class);

            // Preview size and video size should not be greater than screen resolution or 1080.
            Point screenResolution = new Point();
            Display display = activity.getWindowManager().getDefaultDisplay();
            display.getRealSize(screenResolution);

            final boolean swapWH = getMediaOrientation() % 180 == 90;
            int screenWidth = swapWH ? screenResolution.y : screenResolution.x;
            int screenHeight = swapWH ? screenResolution.x : screenResolution.y;
            if (BuildConfig.DEBUG) {
                Log.d(TAG,
                        "computeBestPreviewAndRecordingSize screenWidth: " + screenWidth
                                + ", screenHeight : " + screenHeight);
            }

            List<Size> goodEnough = new ArrayList<>();
            for (Size s : sizes) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "computeBestPreviewAndRecordingSize : " + s.toString());
                }

                if (minHeight <= s.getHeight() && s.getWidth() <= screenWidth
                        && s.getHeight() <= screenHeight && s.getHeight() <= 1080) {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG,
                                "computeBestPreviewAndRecordingSize goodEnough: " + s.toString());
                    }

                    goodEnough.add(s);
                }
            }

            Collections.sort(goodEnough, new CompareSizesByArea());

            if (goodEnough.isEmpty()) {
                previewSize = sizes[0];
                videoSize = sizes[0];
            } else {
                float captureSizeRatio = (float) captureSize.getWidth() / captureSize.getHeight();

                if (BuildConfig.DEBUG) {
                    Log.d(TAG,
                            "computeBestPreviewAndRecordingSize "
                                    + "captureSize.getWidth(): " + captureSize.getWidth()
                                    + ", captureSize.getHeight() : " + captureSize.getHeight()
                                    + ", captureSizeRatio : " + captureSizeRatio
                                    + ", goodEnough.get(0) : " + goodEnough.get(0));
                }

                previewSize = goodEnough.get(0);
                for (Size s : goodEnough) {
                    if ((float) s.getWidth() / s.getHeight() == captureSizeRatio) {
                        previewSize = s;
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG,
                                    "computeBestPreviewAndRecordingSize "
                                            + "previewSize : " + previewSize);
                        }
                        break;
                    }
                }

                Collections.reverse(goodEnough);
                videoSize = goodEnough.get(0);
                for (Size s : goodEnough) {
                    if ((float) s.getWidth() / s.getHeight() == captureSizeRatio) {
                        videoSize = s;
                        break;
                    }
                }
            }
        }

        private void computeBestCaptureSize(StreamConfigurationMap streamConfigurationMap) {
            // For still image captures, we use the largest available size.
            captureSize = Collections.max(
                    Arrays.asList(streamConfigurationMap.getOutputSizes(ImageFormat.JPEG)),
                    new CompareSizesByArea());
        }

        private void prepareMediaRecorder(String outputFilePath) throws IOException {
            if (mediaRecorder != null) {
                mediaRecorder.release();
            }
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mediaRecorder.setVideoEncodingBitRate(1024 * 1000);
            mediaRecorder.setAudioSamplingRate(16000);
            mediaRecorder.setVideoFrameRate(27);
            mediaRecorder.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
            mediaRecorder.setOutputFile(outputFilePath);
            mediaRecorder.setOrientationHint(getMediaOrientation());

            mediaRecorder.prepare();
        }

        private void open(@Nullable final Result result) {
            if (!hasCameraPermission()) {
                if (result != null) {
                    result.error("cameraPermission", "Camera permission not granted", null);
                }
            } else {
                try {
                    pictureImageReader = ImageReader.newInstance(
                            captureSize.getWidth(), captureSize.getHeight(), ImageFormat.JPEG, 2);

                    // Used to steam image byte data to dart side.
                    imageStreamReader = ImageReader.newInstance(previewSize.getWidth(),
                            previewSize.getHeight(), ImageFormat.YUV_420_888, 2);
                    cameraManager.openCamera(cameraName, new CameraDevice.StateCallback() {
                        @Override
                        public void onOpened(@NonNull CameraDevice cameraDevice) {
                            Camera.this.cameraDevice = cameraDevice;
                            try {
                                startPreview();
                            } catch (NullPointerException | CameraAccessException e) {
                                if (result != null) {
                                    result.error("CameraAccess", e.getMessage(), null);
                                }
                                cameraDevice.close();
                                Camera.this.cameraDevice = null;
                                return;
                            }

                            if (result != null) {
                                Map<String, Object> reply = new HashMap<>();
                                reply.put("textureId", textureEntry.id());
                                reply.put("previewWidth", previewSize.getWidth());
                                reply.put("previewHeight", previewSize.getHeight());
                                result.success(reply);
                            }
                        }

                        @Override
                        public void onClosed(@NonNull CameraDevice camera) {
                            if (eventSink != null) {
                                Map<String, String> event = new HashMap<>();
                                event.put("eventType", "cameraClosing");
                                eventSink.success(event);
                            }
                            super.onClosed(camera);
                        }

                        @Override
                        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
                            cameraDevice.close();
                            Camera.this.cameraDevice = null;
                            sendErrorEvent("The camera was disconnected.");
                        }

                        @Override
                        public void onError(@NonNull CameraDevice cameraDevice, int errorCode) {
                            cameraDevice.close();
                            Camera.this.cameraDevice = null;
                            String errorDescription;
                            switch (errorCode) {
                                case ERROR_CAMERA_IN_USE:
                                    errorDescription = "The camera device is in use already.";
                                    break;
                                case ERROR_MAX_CAMERAS_IN_USE:
                                    errorDescription = "Max cameras in use";
                                    break;
                                case ERROR_CAMERA_DISABLED:
                                    errorDescription =
                                            "The camera device could not be opened due to a device policy.";
                                    break;
                                case ERROR_CAMERA_DEVICE:
                                    errorDescription =
                                            "The camera device has encountered a fatal error";
                                    break;
                                case ERROR_CAMERA_SERVICE:
                                    errorDescription =
                                            "The camera service has encountered a fatal error.";
                                    break;
                                default:
                                    errorDescription = "Unknown camera error";
                            }
                            sendErrorEvent(errorDescription);
                        }
                    }, null);
                } catch (CameraAccessException e) {
                    if (result != null) {
                        result.error("cameraAccess", e.getMessage(), null);
                    }
                }
            }
        }

        private void writeToFile(ByteBuffer buffer, File file) throws IOException {
            try (FileOutputStream outputStream = new FileOutputStream(file)) {
                while (0 < buffer.remaining()) {
                    outputStream.getChannel().write(buffer);
                }
            }
        }

        private void takePicture(String filePath, @NonNull final Result result) {
            final File file = new File(filePath);

            if (file.exists()) {
                result.error("fileExists",
                        "File at path '" + filePath + "' already exists. Cannot overwrite.", null);
                return;
            }

            pictureImageReader.setOnImageAvailableListener(
                    new ImageReader.OnImageAvailableListener() {
                        @Override
                        public void onImageAvailable(ImageReader reader) {
                            try (Image image = reader.acquireLatestImage()) {
                                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                                writeToFile(buffer, file);
                                result.success(null);
                            } catch (IOException e) {
                                result.error("IOError", "Failed saving image", null);
                            }
                        }
                    },
                    null);

            try {
                final CaptureRequest.Builder captureBuilder =
                        cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                captureBuilder.addTarget(pictureImageReader.getSurface());
                captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getMediaOrientation());

                cameraCaptureSession.capture(
                        captureBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                            @Override
                            public void onCaptureFailed(@NonNull CameraCaptureSession session,
                                    @NonNull CaptureRequest request,
                                    @NonNull CaptureFailure failure) {
                                String reason;
                                switch (failure.getReason()) {
                                    case CaptureFailure.REASON_ERROR:
                                        reason = "An error happened in the framework";
                                        break;
                                    case CaptureFailure.REASON_FLUSHED:
                                        reason =
                                                "The capture has failed due to an abortCaptures() call";
                                        break;
                                    default:
                                        reason = "Unknown reason";
                                }
                                result.error("captureFailure", reason, null);
                            }
                        }, null);
            } catch (CameraAccessException e) {
                result.error("cameraAccess", e.getMessage(), null);
            }
        }

        private void startVideoRecording(String filePath, @NonNull final Result result) {
            if (cameraDevice == null) {
                result.error("configureFailed", "Camera was closed during configuration.", null);
                return;
            }
            if (new File(filePath).exists()) {
                result.error("fileExists",
                        "File at path '" + filePath + "' already exists. Cannot overwrite.", null);
                return;
            }
            try {
                closeCaptureSession();
                prepareMediaRecorder(filePath);

                recordingVideo = true;

                SurfaceTexture surfaceTexture = textureEntry.surfaceTexture();
                surfaceTexture.setDefaultBufferSize(
                        previewSize.getWidth(), previewSize.getHeight());
                captureRequestBuilder =
                        cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);

                List<Surface> surfaces = new ArrayList<>();

                Surface previewSurface = new Surface(surfaceTexture);
                surfaces.add(previewSurface);
                captureRequestBuilder.addTarget(previewSurface);

                Surface recorderSurface = mediaRecorder.getSurface();
                surfaces.add(recorderSurface);
                captureRequestBuilder.addTarget(recorderSurface);

                cameraDevice.createCaptureSession(
                        surfaces, new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(
                                    @NonNull CameraCaptureSession cameraCaptureSession) {
                                try {
                                    if (cameraDevice == null) {
                                        result.error("configureFailed",
                                                "Camera was closed during configuration", null);
                                        return;
                                    }
                                    Camera.this.cameraCaptureSession = cameraCaptureSession;
                                    captureRequestBuilder.set(CaptureRequest.CONTROL_MODE,
                                            CameraMetadata.CONTROL_MODE_AUTO);
                                    cameraCaptureSession.setRepeatingRequest(
                                            captureRequestBuilder.build(), null, null);
                                    mediaRecorder.start();
                                    result.success(null);
                                } catch (CameraAccessException | IllegalStateException
                                        | IllegalArgumentException e) {
                                    result.error("cameraException", e.getMessage(), null);
                                }
                            }

                            @Override
                            public void onConfigureFailed(
                                    @NonNull CameraCaptureSession cameraCaptureSession) {
                                result.error("configureFailed",
                                        "Failed to configure camera session", null);
                            }
                        }, null);
            } catch (CameraAccessException | IOException e) {
                result.error("videoRecordingFailed", e.getMessage(), null);
            }
        }

        private void stopVideoRecording(@NonNull final Result result) {
            if (!recordingVideo) {
                result.success(null);
                return;
            }

            try {
                recordingVideo = false;
                mediaRecorder.stop();
                mediaRecorder.reset();
                startPreview();
                result.success(null);
            } catch (NullPointerException | CameraAccessException | IllegalStateException e) {
                result.error("videoRecordingFailed", e.getMessage(), null);
            }
        }

        private void startPreview() throws CameraAccessException, NullPointerException {
            closeCaptureSession();

            SurfaceTexture surfaceTexture = textureEntry.surfaceTexture();
            surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            captureRequestBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.set(
                    CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
            List<Surface> surfaces = new ArrayList<>();

            Surface previewSurface = new Surface(surfaceTexture);
            surfaces.add(previewSurface);
            captureRequestBuilder.addTarget(previewSurface);

            surfaces.add(pictureImageReader.getSurface());

            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {

                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    if (cameraDevice == null) {
                        sendErrorEvent("The camera was closed during configuration.");
                        return;
                    }
                    try {
                        cameraCaptureSession = session;
                        captureRequestBuilder.set(
                                CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                        ////
                        //                                captureRequestBuilder.set(CaptureRequest.FLASH_MODE,
                        //                                CaptureRequest.FLASH_MODE_TORCH);
                        cameraCaptureSession.setRepeatingRequest(
                                captureRequestBuilder.build(), null, null);
                    } catch (CameraAccessException | IllegalStateException
                            | IllegalArgumentException e) {
                        sendErrorEvent(e.getMessage());
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    sendErrorEvent("Failed to configure the camera for preview.");
                }
            }, null);
        }

        private void startPreviewWithImageStream() throws CameraAccessException {
            closeCaptureSession();

            SurfaceTexture surfaceTexture = textureEntry.surfaceTexture();
            surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());

            captureRequestBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);

            List<Surface> surfaces = new ArrayList<>();

            Surface previewSurface = new Surface(surfaceTexture);
            surfaces.add(previewSurface);
            captureRequestBuilder.addTarget(previewSurface);

            surfaces.add(imageStreamReader.getSurface());
            captureRequestBuilder.addTarget(imageStreamReader.getSurface());

            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    if (cameraDevice == null) {
                        sendErrorEvent("The camera was closed during configuration.");
                        return;
                    }
                    try {
                        cameraCaptureSession = session;
                        captureRequestBuilder.set(
                                CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                        cameraCaptureSession.setRepeatingRequest(
                                captureRequestBuilder.build(), null, null);
                    } catch (CameraAccessException | IllegalStateException
                            | IllegalArgumentException e) {
                        sendErrorEvent(e.getMessage());
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    sendErrorEvent("Failed to configure the camera for streaming images.");
                }
            }, null);

            registerImageStreamEventChannel();
        }

        private void registerImageStreamEventChannel() {
            final EventChannel imageStreamChannel = new EventChannel(
                    registrar.messenger(), "plugins.flutter.io/camera/imageStream");

            imageStreamChannel.setStreamHandler(new EventChannel.StreamHandler() {
                @Override
                public void onListen(Object o, EventChannel.EventSink eventSink) {
                    setImageStreamImageAvailableListener(eventSink);
                }

                @Override
                public void onCancel(Object o) {
                    if (imageStreamReader != null) {
                        imageStreamReader.setOnImageAvailableListener(null, null);
                    }
                }
            });
        }

        private byte[] NV21toRGBA(byte[] data, int width, int height) {
            int size = width * height;
            byte[] bytes = new byte[size * 4];
            int y, u, v;
            int r, g, b;
            int index;
            for (int i = 0; i < height; i++) {
                for (int j = 0; j < width; j++) {
                    index = j % 2 == 0 ? j : j - 1;

                    y = data[width * i + j] & 0xff;
                    u = data[width * height + width * (i / 2) + index + 1] & 0xff;
                    v = data[width * height + width * (i / 2) + index] & 0xff;

                    r = y + (int) 1.370705f * (v - 128);
                    g = y - (int) (0.698001f * (v - 128) + 0.337633f * (u - 128));
                    b = y + (int) 1.732446f * (u - 128);

                    r = r < 0 ? 0 : (r > 255 ? 255 : r);
                    g = g < 0 ? 0 : (g > 255 ? 255 : g);
                    b = b < 0 ? 0 : (b > 255 ? 255 : b);

                    bytes[width * i * 4 + j * 4 + 0] = (byte) r;
                    bytes[width * i * 4 + j * 4 + 1] = (byte) g;
                    bytes[width * i * 4 + j * 4 + 2] = (byte) b;
                    bytes[width * i * 4 + j * 4 + 3] = (byte) 255; //透明度
                }
            }
            return bytes;
        }

        private byte[] sortBytesRotate90(byte[] dataRGBABytes, int width, int height) {
            byte[] bytes = new byte[dataRGBABytes.length];
            for (int i = 0; i < height; ++i) {
                for (int j = 0; j < width; ++j) {
                    // 这个是一个rgba 数据
                    int newIndex = ((width - j) * height - 1 - i) * 4;
                    int oldIndex = (width - j - 1 + i * width) * 4;
                    bytes[newIndex] = dataRGBABytes[oldIndex];
                    bytes[newIndex + 1] = dataRGBABytes[oldIndex + 1];
                    bytes[newIndex + 2] = dataRGBABytes[oldIndex + 2];
                    bytes[newIndex + 3] = dataRGBABytes[oldIndex + 3];
                }
            }
            return bytes;
        }

        private byte[] sortBytesRotate270(byte[] dataRGBABytes, int width, int height) {
            byte[] bytes = new byte[dataRGBABytes.length];
            for (int i = 0; i < height; ++i) {
                for (int j = 0; j < width; ++j) {
                    // 这个是一个rgba 数据
                    int newIndex = (height - 1 - i + j * height) * 4;
                    int oldIndex = (width - j - 1 + (height - 1 - i) * width) * 4;
                    bytes[newIndex] = dataRGBABytes[oldIndex];
                    bytes[newIndex + 1] = dataRGBABytes[oldIndex + 1];
                    bytes[newIndex + 2] = dataRGBABytes[oldIndex + 2];
                    bytes[newIndex + 3] = dataRGBABytes[oldIndex + 3];
                }
            }
            return bytes;
        }

        private void setImageStreamImageAvailableListener(final EventChannel.EventSink eventSink) {
            imageStreamReader.setOnImageAvailableListener(
                    new ImageReader.OnImageAvailableListener() {
                        @Override
                        public void onImageAvailable(final ImageReader reader) {
                            Image img = reader.acquireLatestImage();
                            if (img == null) {
                                return;
                            }

                            if (mIsCanStartImageStream) {
                                mIsCanStartImageStream = false;

                                List<Map<String, Object>> planes = new ArrayList<>();

                                ByteBuffer Y = img.getPlanes()[0].getBuffer();
                                ByteBuffer U = img.getPlanes()[1].getBuffer();
                                ByteBuffer V = img.getPlanes()[2].getBuffer();

                                int Yb = Y.remaining();
                                int Ub = U.remaining();
                                int Vb = V.remaining();

                                byte[] data = new byte[Yb + Ub + Vb];

                                Y.get(data, 0, Yb);
                                V.get(data, Yb, Vb);
                                U.get(data, Yb + Vb, Ub);
                                byte[] bytes = NV21toRGBA(data, img.getWidth(), img.getHeight());
                                Map<String, Object> planeBuffer = new HashMap<>();
                                boolean isRotate = img.getWidth() > img.getHeight();
                                if (BuildConfig.DEBUG) {
                                    Log.d(TAG, "get image isRotate : " + isRotate);
                                }
                                if (isRotate) {
                                    if (getMediaOrientation() == 270) {
                                        bytes = sortBytesRotate270(
                                                bytes, img.getWidth(), img.getHeight());
                                    } else {
                                        bytes = sortBytesRotate90(
                                                bytes, img.getWidth(), img.getHeight());
                                    }
                                }

                                planeBuffer.put("bytes", bytes);
                                planes.add(planeBuffer);

                                //                                for (Image.Plane plane :
                                //                                img.getPlanes()) {
                                //                                    ByteBuffer buffer =
                                //                                    plane.getBuffer();
                                //
                                //                                    byte[] bytes = new
                                //                                    byte[buffer.remaining()];
                                //                                    buffer.get(bytes, 0,
                                //                                    bytes.length);
                                //
                                //                                    Map<String, Object>
                                //                                    planeBuffer = new HashMap<>();
                                //                                    planeBuffer.put("bytesPerRow",
                                //                                    plane.getRowStride());
                                //                                    planeBuffer.put("bytesPerPixel",
                                //                                    plane.getPixelStride());
                                //                                    planeBuffer.put("bytes",
                                //                                    bytes);
                                //                                    planes.add(planeBuffer);
                                //                                }

                                Map<String, Object> imageBuffer = new HashMap<>();
                                imageBuffer.put(
                                        "width", isRotate ? img.getHeight() : img.getWidth());
                                imageBuffer.put(
                                        "height", isRotate ? img.getWidth() : img.getHeight());
                                imageBuffer.put("format", img.getFormat());
                                imageBuffer.put("planes", planes);

                                //                            if(BuildConfig.DEBUG) {
                                //                                Log.d(TAG, "width : " +
                                //                                imageBuffer.get("width"));
                                //                                Log.d(TAG, "height : " +
                                //                                imageBuffer.get("height"));
                                //                                Log.d(TAG, "format : " +
                                //                                imageBuffer.get("format"));
                                //                                Log.d(TAG, "planes length: " +
                                //                                (planes == null ? 0 :
                                //                                planes.size())); Log.d(TAG,
                                //                                "planes : " + (planes == null ?
                                //                                "null" :
                                //                                imageBuffer.get("planes")));
                                //                            }

                                eventSink.success(imageBuffer);
                            }
                            img.close();
                        }
                    },
                    null);
        }

        private void sendErrorEvent(String errorDescription) {
            if (eventSink != null) {
                Map<String, String> event = new HashMap<>();
                event.put("eventType", "error");
                event.put("errorDescription", errorDescription);
                eventSink.success(event);
            }
        }

        private void closeCaptureSession() {
            if (cameraCaptureSession != null) {
                cameraCaptureSession.close();
                cameraCaptureSession = null;
            }
        }

        private void close() {
            closeCaptureSession();

            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (pictureImageReader != null) {
                pictureImageReader.close();
                pictureImageReader = null;
            }
            if (imageStreamReader != null) {
                imageStreamReader.close();
                imageStreamReader = null;
            }
            if (mediaRecorder != null) {
                mediaRecorder.reset();
                mediaRecorder.release();
                mediaRecorder = null;
            }
        }

        private void dispose() {
            close();
            textureEntry.release();
        }

        private int getMediaOrientation() {
            final int sensorOrientationOffset = (currentOrientation == ORIENTATION_UNKNOWN)
                    ? 0
                    : (isFrontFacing) ? -currentOrientation : currentOrientation;
            int value = (sensorOrientationOffset + sensorOrientation + 360) % 360;
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "getMediaOrientation : " + value);
            }
            return value;
        }
    }
}
