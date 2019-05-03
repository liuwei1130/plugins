// Copyright 2017 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.imagepicker;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;

import androidx.annotation.VisibleForTesting;

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.PluginRegistry;

import java.io.File;
import java.io.IOException;

public class ImagePickerPlugin implements MethodChannel.MethodCallHandler {
    private static final String CHANNEL = "plugins.flutter.io/image_picker";

    private static final int REQUEST_ID = 1001010;

    private static final int SOURCE_CAMERA = 0;
    private static final int SOURCE_GALLERY = 1;

    private final PluginRegistry.Registrar registrar;
    private final ImagePickerDelegate delegate;

    public static void registerWith(PluginRegistry.Registrar registrar) {
        if (registrar.activity() == null) {
            // If a background flutter view tries to register the plugin, there will be no activity from the registrar,
            // we stop the registering process immediately because the ImagePicker requires an activity.
            return;
        }
        final MethodChannel channel = new MethodChannel(registrar.messenger(), CHANNEL);

        final File externalFilesDirectory =
                registrar.activity().getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        final ExifDataCopier exifDataCopier = new ExifDataCopier();
        final ImageResizer imageResizer = new ImageResizer(externalFilesDirectory, exifDataCopier);

        final ImagePickerDelegate delegate =
                new ImagePickerDelegate(registrar.activity(), externalFilesDirectory, imageResizer);
        registrar.addActivityResultListener(delegate);
        registrar.addRequestPermissionsResultListener(delegate);

        final ImagePickerPlugin instance = new ImagePickerPlugin(registrar, delegate);
        channel.setMethodCallHandler(instance);
    }

    @VisibleForTesting
    ImagePickerPlugin(PluginRegistry.Registrar registrar, ImagePickerDelegate delegate) {
        this.registrar = registrar;
        this.delegate = delegate;
    }

    @Override
    public void onMethodCall(MethodCall call, final MethodChannel.Result result) {
        if (registrar.activity() == null) {
            result.error("no_activity", "image_picker plugin requires a foreground activity.", null);
            return;
        }
        if (call.method.equals("requestForPermission")) {
            //API >=23
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (registrar.context().checkSelfPermission(
                        Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        == PackageManager.PERMISSION_GRANTED &&
                        registrar.context().checkSelfPermission(
                                Manifest.permission.READ_EXTERNAL_STORAGE)
                                == PackageManager.PERMISSION_GRANTED) {
                    result.success(true);
                } else {
                    registrar.addRequestPermissionsResultListener(new PluginRegistry.RequestPermissionsResultListener() {
                        @Override
                        public boolean onRequestPermissionsResult(int id, String[] strings, int[] ints) {
                            if (id == REQUEST_ID) {
                                result.success(true);
                                return true;
                            }
                            result.success(null);
                            return false;
                        }
                    });
                    registrar
                            .activity()
                            .requestPermissions(
                                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                            Manifest.permission.READ_EXTERNAL_STORAGE},
                                    REQUEST_ID);
                }

            } else {
                result.success(true);
            }

        } else if (call.method.equals("pickImage")) {
            int imageSource = call.argument("source");
            switch (imageSource) {
                case SOURCE_GALLERY:
                    delegate.chooseImageFromGallery(call, result);
                    break;
                case SOURCE_CAMERA:
                    delegate.takeImageWithCamera(call, result);
                    break;
                default:
                    throw new IllegalArgumentException("Invalid image source: " + imageSource);
            }
        } else if (call.method.equals("pickVideo")) {
            int imageSource = call.argument("source");
            switch (imageSource) {
                case SOURCE_GALLERY:
                    delegate.chooseVideoFromGallery(call, result);
                    break;
                case SOURCE_CAMERA:
                    delegate.takeVideoWithCamera(call, result);
                    break;
                default:
                    throw new IllegalArgumentException("Invalid video source: " + imageSource);
            }
        } else if (call.method.equals("getLatestImage")) {
            delegate.getLatestPhoto(call, result);
        } else if (call.method.equals("saveFile")) {
            try {
                delegate.saveImageToGallery(call, result);
            } catch (IOException e) {
                e.printStackTrace();
                throw new IllegalArgumentException(e);
            }
        } else {
            throw new IllegalArgumentException("Unknown method " + call.method);
        }
    }
}
