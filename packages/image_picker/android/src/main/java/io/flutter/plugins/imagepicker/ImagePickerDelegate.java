// Copyright 2017 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.imagepicker;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.VisibleForTesting;
import androidx.core.app.ActivityCompat;
import androidx.core.content.FileProvider;

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.PluginRegistry;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLConnection;
import java.util.List;
import java.util.UUID;

/**
 * A delegate class doing the heavy lifting for the plugin.
 *
 * <p>When invoked, both the {@link #chooseImageFromGallery} and {@link #takeImageWithCamera}
 * methods go through the same steps:
 *
 * <p>1. Check for an existing {@link #pendingResult}. If a previous pendingResult exists, this
 * means that the chooseImageFromGallery() or takeImageWithCamera() method was called at least
 * twice. In this case, stop executing and finish with an error.
 *
 * <p>2. Check that a required runtime permission has been granted. The chooseImageFromGallery()
 * method checks if the {@link Manifest.permission#READ_EXTERNAL_STORAGE} permission has been
 * granted. Similarly, the takeImageWithCamera() method checks that {@link
 * Manifest.permission#CAMERA} has been granted.
 *
 * <p>The permission check can end up in two different outcomes:
 *
 * <p>A) If the permission has already been granted, continue with picking the image from gallery or
 * camera.
 *
 * <p>B) If the permission hasn't already been granted, ask for the permission from the user. If the
 * user grants the permission, proceed with step #3. If the user denies the permission, stop doing
 * anything else and finish with a null result.
 *
 * <p>3. Launch the gallery or camera for picking the image, depending on whether
 * chooseImageFromGallery() or takeImageWithCamera() was called.
 *
 * <p>This can end up in three different outcomes:
 *
 * <p>A) User picks an image. No maxWidth or maxHeight was specified when calling {@code
 * pickImage()} method in the Dart side of this plugin. Finish with full path for the picked image
 * as the result.
 *
 * <p>B) User picks an image. A maxWidth and/or maxHeight was provided when calling {@code
 * pickImage()} method in the Dart side of this plugin. A scaled copy of the image is created.
 * Finish with full path for the scaled image as the result.
 *
 * <p>C) User cancels picking an image. Finish with null result.
 */
public class ImagePickerDelegate
        implements PluginRegistry.ActivityResultListener,
        PluginRegistry.RequestPermissionsResultListener {
    @VisibleForTesting
    static final int REQUEST_CODE_CHOOSE_IMAGE_FROM_GALLERY = 2342;
    @VisibleForTesting
    static final int REQUEST_CODE_TAKE_IMAGE_WITH_CAMERA = 2343;
    @VisibleForTesting
    static final int REQUEST_EXTERNAL_IMAGE_STORAGE_PERMISSION = 2344;
    @VisibleForTesting
    static final int REQUEST_CODE_CHOOSE_VIDEO_FROM_GALLERY = 2352;
    @VisibleForTesting
    static final int REQUEST_CODE_TAKE_VIDEO_WITH_CAMERA = 2353;
    @VisibleForTesting
    static final int REQUEST_EXTERNAL_VIDEO_STORAGE_PERMISSION = 2354;

    @VisibleForTesting
    final String fileProviderName;

    private final Activity activity;
    private final File externalFilesDirectory;
    private final ImageResizer imageResizer;
    private final PermissionManager permissionManager;
    private final IntentResolver intentResolver;
    private final FileUriResolver fileUriResolver;
    private final FileUtils fileUtils;

    interface PermissionManager {
        boolean isPermissionGranted(String permissionName);

        void askForPermission(String permissionName, int requestCode);
    }

    interface IntentResolver {
        boolean resolveActivity(Intent intent);
    }

    interface FileUriResolver {
        Uri resolveFileProviderUriForFile(String fileProviderName, File imageFile);

        void getFullImagePath(Uri imageUri, OnPathReadyListener listener);
    }

    interface OnPathReadyListener {
        void onPathReady(String path);
    }

    private Uri pendingCameraMediaUri;
    private MethodChannel.Result pendingResult;
    private MethodCall methodCall;

    public ImagePickerDelegate(
            final Activity activity, File externalFilesDirectory, ImageResizer imageResizer) {
        this(
                activity,
                externalFilesDirectory,
                imageResizer,
                null,
                null,
                new PermissionManager() {
                    @Override
                    public boolean isPermissionGranted(String permissionName) {
                        return ActivityCompat.checkSelfPermission(activity, permissionName)
                                == PackageManager.PERMISSION_GRANTED;
                    }

                    @Override
                    public void askForPermission(String permissionName, int requestCode) {
                        ActivityCompat.requestPermissions(activity, new String[]{permissionName}, requestCode);
                    }
                },
                new IntentResolver() {
                    @Override
                    public boolean resolveActivity(Intent intent) {
                        return intent.resolveActivity(activity.getPackageManager()) != null;
                    }
                },
                new FileUriResolver() {
                    @Override
                    public Uri resolveFileProviderUriForFile(String fileProviderName, File file) {
                        return FileProvider.getUriForFile(activity, fileProviderName, file);
                    }

                    @Override
                    public void getFullImagePath(final Uri imageUri, final OnPathReadyListener listener) {
                        MediaScannerConnection.scanFile(
                                activity,
                                new String[]{(imageUri != null) ? imageUri.getPath() : ""},
                                null,
                                new MediaScannerConnection.OnScanCompletedListener() {
                                    @Override
                                    public void onScanCompleted(String path, Uri uri) {
                                        listener.onPathReady(path);
                                    }
                                });
                    }
                },
                new FileUtils());
    }

    /**
     * This constructor is used exclusively for testing; it can be used to provide mocks to final
     * fields of this class. Otherwise those fields would have to be mutable and visible.
     */
    @VisibleForTesting
    ImagePickerDelegate(
            Activity activity,
            File externalFilesDirectory,
            ImageResizer imageResizer,
            MethodChannel.Result result,
            MethodCall methodCall,
            PermissionManager permissionManager,
            IntentResolver intentResolver,
            FileUriResolver fileUriResolver,
            FileUtils fileUtils) {
        this.activity = activity;
        this.externalFilesDirectory = externalFilesDirectory;
        this.imageResizer = imageResizer;
        this.fileProviderName = activity.getPackageName() + ".flutter.image_provider";
        this.pendingResult = result;
        this.methodCall = methodCall;
        this.permissionManager = permissionManager;
        this.intentResolver = intentResolver;
        this.fileUriResolver = fileUriResolver;
        this.fileUtils = fileUtils;
    }

    public void chooseVideoFromGallery(MethodCall methodCall, MethodChannel.Result result) {
        if (!setPendingMethodCallAndResult(methodCall, result)) {
            finishWithAlreadyActiveError(result);
            return;
        }

        if (!permissionManager.isPermissionGranted(Manifest.permission.READ_EXTERNAL_STORAGE)) {
            permissionManager.askForPermission(
                    Manifest.permission.READ_EXTERNAL_STORAGE, REQUEST_EXTERNAL_VIDEO_STORAGE_PERMISSION);
            return;
        }

        launchPickVideoFromGalleryIntent();
    }

    private void launchPickVideoFromGalleryIntent() {
        Intent pickVideoIntent = new Intent(Intent.ACTION_GET_CONTENT);
        pickVideoIntent.setType("video/*");

        activity.startActivityForResult(pickVideoIntent, REQUEST_CODE_CHOOSE_VIDEO_FROM_GALLERY);
    }

    public void takeVideoWithCamera(MethodCall methodCall, MethodChannel.Result result) {
        if (!setPendingMethodCallAndResult(methodCall, result)) {
            finishWithAlreadyActiveError(result);
            return;
        }

        launchTakeVideoWithCameraIntent();
    }

    private void launchTakeVideoWithCameraIntent() {
        Intent intent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
        boolean canTakePhotos = intentResolver.resolveActivity(intent);

        if (!canTakePhotos) {
            finishWithError("no_available_camera", "No cameras available for taking pictures.");
            return;
        }

        File videoFile = createTemporaryWritableVideoFile();
        pendingCameraMediaUri = Uri.parse("file:" + videoFile.getAbsolutePath());

        Uri videoUri = fileUriResolver.resolveFileProviderUriForFile(fileProviderName, videoFile);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, videoUri);
        grantUriPermissions(intent, videoUri);

        activity.startActivityForResult(intent, REQUEST_CODE_TAKE_VIDEO_WITH_CAMERA);
    }

    public void getLatestPhoto(MethodCall methodCall, MethodChannel.Result result) {
//        if (!setPendingMethodCallAndResult(methodCall, result)) {
//            finishWithAlreadyActiveError(result);
//            return;
//        }
//
//        if (!permissionManager.isPermissionGranted(Manifest.permission.READ_EXTERNAL_STORAGE)) {
//            permissionManager.askForPermission(
//                    Manifest.permission.READ_EXTERNAL_STORAGE, REQUEST_EXTERNAL_IMAGE_STORAGE_PERMISSION);
//            return;
//        }
//        Pair<Long, String> pair = getLatestPhoto(activity);
//        if (pair == null) {
//            result.error("获得图片", "数据为空", null);
//        } else {
//            Log.d("ImagePickerDelegate", "获得图片数据: longId : " + pair.first + " , path : " + pair.second);
//            result.success(pair.second);
//        }

        result.success(null);
    }

    public void saveImageToGallery(MethodCall methodCall, MethodChannel.Result result) throws IOException {
        if (!setPendingMethodCallAndResult(methodCall, result)) {
            finishWithAlreadyActiveError(result);
            return;
        }

        if (!permissionManager.isPermissionGranted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            permissionManager.askForPermission(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE, REQUEST_EXTERNAL_IMAGE_STORAGE_PERMISSION);
            return;
        }
        byte[] fileData = methodCall.argument("fileData");

        //Bitmap bitmap = BitmapFactory.decodeByteArray(fileData, 0, fileData.length);

        String title = methodCall.argument("title") == null ? "Camera" : methodCall.argument("title").toString();

        String desctiption = methodCall.argument("description") == null ? "123" : methodCall.argument("description").toString();

        String filePath = insertImage(activity.getContentResolver(), fileData, title, desctiption);

        finishWithSuccess(filePath);

    }

    private String insertImage(ContentResolver cr,
                               byte[] source,
                               String title,
                               String description) throws IOException {

        InputStream is = new BufferedInputStream(new ByteArrayInputStream(source));
        String mimeType = URLConnection.guessContentTypeFromStream(is);

        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.TITLE, title);
        values.put(MediaStore.Images.Media.DISPLAY_NAME, title);
        values.put(MediaStore.Images.Media.DESCRIPTION, description);
        values.put(MediaStore.Images.Media.MIME_TYPE, mimeType);
        // Add the date meta data to ensure the image is added at the front of the gallery
        values.put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis());
        values.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());

        Uri url = null;
        String stringUrl = "";    /* value to be returned */

        try {
            url = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

            if (source != null) {
                OutputStream imageOut = cr.openOutputStream(url);
                try {
                    //source.compress(Bitmap.CompressFormat.JPEG, 100, imageOut);
                    imageOut.write(source);
                } finally {
                    imageOut.close();
                }

                long id = ContentUris.parseId(url);
                // Wait until MINI_KIND thumbnail is generated.
                Bitmap miniThumb = MediaStore.Images.Thumbnails.getThumbnail(cr, id, MediaStore.Images.Thumbnails.MINI_KIND, null);
                // This is for backward compatibility.
                storeThumbnail(cr, miniThumb, id, 50F, 50F, MediaStore.Images.Thumbnails.MICRO_KIND);


            } else {
                cr.delete(url, null, null);
                url = null;
            }
        } catch (Exception e) {
            if (url != null) {
                cr.delete(url, null, null);
                url = null;
            }
        }

        if (url != null) {
            stringUrl = getFilePathFromContentUri(url, cr);
        }


        return stringUrl;
    }

    /**
     * A copy of the Android internals StoreThumbnail method, it used with the insertImage to
     * populate the android.provider.MediaStore.Images.Media#insertImage with all the correct
     * meta data. The StoreThumbnail method is private so it must be duplicated here.
     *
     * @see android.provider.MediaStore.Images.Media (StoreThumbnail private method)
     */
    private Bitmap storeThumbnail(
            ContentResolver cr,
            Bitmap source,
            long id,
            float width,
            float height,
            int kind) {

        // create the matrix to scale it
        Matrix matrix = new Matrix();

        float scaleX = width / source.getWidth();
        float scaleY = height / source.getHeight();

        matrix.setScale(scaleX, scaleY);

        Bitmap thumb = Bitmap.createBitmap(source, 0, 0,
                source.getWidth(),
                source.getHeight(), matrix,
                true
        );

        ContentValues values = new ContentValues(4);
        values.put(MediaStore.Images.Thumbnails.KIND, kind);
        values.put(MediaStore.Images.Thumbnails.IMAGE_ID, (int) id);
        values.put(MediaStore.Images.Thumbnails.HEIGHT, thumb.getHeight());
        values.put(MediaStore.Images.Thumbnails.WIDTH, thumb.getWidth());

        Uri url = cr.insert(MediaStore.Images.Thumbnails.EXTERNAL_CONTENT_URI, values);

        try {
            OutputStream thumbOut = cr.openOutputStream(url);
            //thumb.compress(Bitmap.CompressFormat.JPEG, 100, thumbOut);
            thumbOut.close();
            return thumb;
        } catch (FileNotFoundException ex) {
            return null;
        } catch (IOException ex) {
            return null;
        }
    }

    /**
     * Gets the corresponding path to a file from the given content:// URI
     *
     * @param selectedVideoUri The content:// URI to find the file path from
     * @param contentResolver  The content resolver to use to perform the query.
     * @return the file path as a string
     */
    public static String getFilePathFromContentUri(Uri selectedVideoUri,
                                                   ContentResolver contentResolver) {
        String filePath;
        String[] filePathColumn = {MediaStore.MediaColumns.DATA};

        Cursor cursor = contentResolver.query(selectedVideoUri, filePathColumn, null, null, null);
//	    也可用下面的方法拿到cursor
//	    Cursor cursor = this.context.managedQuery(selectedVideoUri, filePathColumn, null, null, null);

        cursor.moveToFirst();

        int columnIndex = cursor.getColumnIndex(filePathColumn[0]);
        filePath = cursor.getString(columnIndex);
        cursor.close();
        return filePath;
    }

    /**
     * 获取相册中最新一张图片
     *
     * @param context
     * @return
     */
    private Pair<Long, String> getLatestPhoto(Context context) {
        //检查camera文件夹，查询并排序
        Pair<Long, String> cameraPair = null;
        Cursor cursor = null;
        try {
            context.getContentResolver().query(MediaStore.Images.Thumbnails.EXTERNAL_CONTENT_URI,
                    new String[]{
                            MediaStore.Images.Thumbnails.IMAGE_ID,
                            MediaStore.Images.Thumbnails.DATA
                    },
                    null,
                    null,
                    MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC");
            if (cursor.moveToFirst()) {
                cameraPair = new Pair(cursor.getLong(0), cursor.getString(1));
            }
        } catch (Exception exception) {
            exception.printStackTrace();
        } finally {
            if (cursor != null && !cursor.isClosed()) {
                cursor.close();
            }
            cursor = null;
        }

        return cameraPair;
    }

    private String getBucketId(String path) {
        return String.valueOf(path.toLowerCase().hashCode());
    }

    /**
     * 获取截图路径
     *
     * @return
     */
    private String getScreenshotsPath() {
        String path = Environment.getExternalStorageDirectory().toString() + "/DCIM/Screenshots";
        File file = new File(path);
        if (!file.exists()) {
            path = Environment.getExternalStorageDirectory().toString() + "/Pictures/Screenshots";
        }
        file = null;
        return path;
    }

    public void chooseImageFromGallery(MethodCall methodCall, MethodChannel.Result result) {
        if (!setPendingMethodCallAndResult(methodCall, result)) {
            finishWithAlreadyActiveError(result);
            return;
        }

        if (!permissionManager.isPermissionGranted(Manifest.permission.READ_EXTERNAL_STORAGE)) {
            permissionManager.askForPermission(
                    Manifest.permission.READ_EXTERNAL_STORAGE, REQUEST_EXTERNAL_IMAGE_STORAGE_PERMISSION);
            return;
        }

        launchPickImageFromGalleryIntent();
    }

    private void launchPickImageFromGalleryIntent() {
        Intent pickImageIntent = new Intent(Intent.ACTION_GET_CONTENT);
        pickImageIntent.setType("image/*");

        activity.startActivityForResult(pickImageIntent, REQUEST_CODE_CHOOSE_IMAGE_FROM_GALLERY);
    }

    public void takeImageWithCamera(MethodCall methodCall, MethodChannel.Result result) {
        if (!setPendingMethodCallAndResult(methodCall, result)) {
            finishWithAlreadyActiveError(result);
            return;
        }

        launchTakeImageWithCameraIntent();
    }

    private void launchTakeImageWithCameraIntent() {
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        boolean canTakePhotos = intentResolver.resolveActivity(intent);

        if (!canTakePhotos) {
            finishWithError("no_available_camera", "No cameras available for taking pictures.");
            return;
        }

        File imageFile = createTemporaryWritableImageFile();
        pendingCameraMediaUri = Uri.parse("file:" + imageFile.getAbsolutePath());

        Uri imageUri = fileUriResolver.resolveFileProviderUriForFile(fileProviderName, imageFile);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, imageUri);
        grantUriPermissions(intent, imageUri);

        activity.startActivityForResult(intent, REQUEST_CODE_TAKE_IMAGE_WITH_CAMERA);
    }

    private File createTemporaryWritableImageFile() {
        return createTemporaryWritableFile(".jpg");
    }

    private File createTemporaryWritableVideoFile() {
        return createTemporaryWritableFile(".mp4");
    }

    private File createTemporaryWritableFile(String suffix) {
        String filename = UUID.randomUUID().toString();
        File image;

        try {
            image = File.createTempFile(filename, suffix, externalFilesDirectory);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return image;
    }

    private void grantUriPermissions(Intent intent, Uri imageUri) {
        PackageManager packageManager = activity.getPackageManager();
        List<ResolveInfo> compatibleActivities =
                packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);

        for (ResolveInfo info : compatibleActivities) {
            activity.grantUriPermission(
                    info.activityInfo.packageName,
                    imageUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        }
    }

    @Override
    public boolean onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        boolean permissionGranted =
                grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;

        switch (requestCode) {
            case REQUEST_EXTERNAL_IMAGE_STORAGE_PERMISSION:
                if (permissionGranted) {
                    launchPickImageFromGalleryIntent();
                }
                break;
            case REQUEST_EXTERNAL_VIDEO_STORAGE_PERMISSION:
                if (permissionGranted) {
                    launchPickVideoFromGalleryIntent();
                }
                break;
            default:
                return false;
        }

        if (!permissionGranted) {
            finishWithSuccess(null);
        }

        return true;
    }

    @Override
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_CODE_CHOOSE_IMAGE_FROM_GALLERY:
                handleChooseImageResult(resultCode, data);
                break;
            case REQUEST_CODE_TAKE_IMAGE_WITH_CAMERA:
                handleCaptureImageResult(resultCode);
                break;
            case REQUEST_CODE_CHOOSE_VIDEO_FROM_GALLERY:
                handleChooseVideoResult(resultCode, data);
                break;
            case REQUEST_CODE_TAKE_VIDEO_WITH_CAMERA:
                handleCaptureVideoResult(resultCode);
                break;
            default:
                return false;
        }

        return true;
    }

    private void handleChooseImageResult(int resultCode, Intent data) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            String path = fileUtils.getPathFromUri(activity, data.getData());
            handleImageResult(path, false);
            return;
        }

        // User cancelled choosing a picture.
        finishWithSuccess(null);
    }

    private void handleChooseVideoResult(int resultCode, Intent data) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            String path = fileUtils.getPathFromUri(activity, data.getData());
            handleVideoResult(path);
            return;
        }

        // User cancelled choosing a picture.
        finishWithSuccess(null);
    }

    private void handleCaptureImageResult(int resultCode) {
        if (resultCode == Activity.RESULT_OK) {
            fileUriResolver.getFullImagePath(
                    pendingCameraMediaUri,
                    new OnPathReadyListener() {
                        @Override
                        public void onPathReady(String path) {
                            handleImageResult(path, true);
                        }
                    });
            return;
        }

        // User cancelled taking a picture.
        finishWithSuccess(null);
    }

    private void handleCaptureVideoResult(int resultCode) {
        if (resultCode == Activity.RESULT_OK) {
            fileUriResolver.getFullImagePath(
                    pendingCameraMediaUri,
                    new OnPathReadyListener() {
                        @Override
                        public void onPathReady(String path) {
                            handleVideoResult(path);
                        }
                    });
            return;
        }

        // User cancelled taking a picture.
        finishWithSuccess(null);
    }

    private void handleImageResult(String path, boolean shouldDeleteOriginalIfScaled) {
        if (pendingResult != null) {
            Double maxWidth = methodCall.argument("maxWidth");
            Double maxHeight = methodCall.argument("maxHeight");

            String finalImagePath = imageResizer.resizeImageIfNeeded(path, maxWidth, maxHeight);
            finishWithSuccess(finalImagePath);

            //delete original file if scaled
            if (!finalImagePath.equals(path) && shouldDeleteOriginalIfScaled) {
                new File(path).delete();
            }
        }
    }

    private void handleVideoResult(String path) {
        finishWithSuccess(path);
    }

    private boolean setPendingMethodCallAndResult(
            MethodCall methodCall, MethodChannel.Result result) {
        if (pendingResult != null) {
            return false;
        }

        this.methodCall = methodCall;
        pendingResult = result;
        return true;
    }

    private void finishWithSuccess(String imagePath) {
        if (pendingResult == null) {
            return;
        }
        pendingResult.success(imagePath);
        clearMethodCallAndResult();
    }

    private void finishWithAlreadyActiveError(MethodChannel.Result result) {
        result.error("already_active", "Image picker is already active", null);
    }

    private void finishWithError(String errorCode, String errorMessage) {
        if (pendingResult == null) {
            return;
        }
        pendingResult.error(errorCode, errorMessage, null);
        clearMethodCallAndResult();
    }

    private void clearMethodCallAndResult() {
        methodCall = null;
        pendingResult = null;
    }
}
