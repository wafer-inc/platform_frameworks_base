/*
 * Copyright (C) 2020 The Pixel Experience Project
 *               2021-2025 crDroid Android Project
 *               2026 Wafer Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util.wafer;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-process spoofing of Build props and system features for apps that gate
 * on Pixel-specific branding (e.g. Google Camera).
 *
 * @hide
 */
public final class PixelPropsUtils {

    private static final String TAG = "PixelPropsUtils";

    private static final String GOOGLE_CAMERA = "com.google.android.GoogleCamera";

    // Spoof as Pixel 9 Pro XL (komodo), matching a real factory fingerprint.
    private static final Map<String, String> CAMERA_PROPS = Map.of(
        "BRAND",        "google",
        "MANUFACTURER", "Google",
        "DEVICE",       "komodo",
        "PRODUCT",      "komodo",
        "HARDWARE",     "komodo",
        "MODEL",        "Pixel 9 Pro XL",
        "ID",           "BP1A.250505.005.D1",
        "FINGERPRINT",  "google/komodo/komodo:15/BP1A.250505.005.D1/12837754:user/release-keys",
        "TYPE",         "user",
        "TAGS",         "release-keys"
    );

    // System features that should appear present to spoofed packages.
    private static final Set<String> SPOOFED_FEATURES = new HashSet<>(Arrays.asList(
        "com.google.android.feature.GOOGLE_BUILD",
        "com.google.android.feature.GOOGLE_EXPERIENCE",
        "com.google.android.feature.PIXEL_EXPERIENCE",
        "com.google.android.feature.PIXEL_2017_EXPERIENCE",
        "com.google.android.feature.PIXEL_2018_EXPERIENCE",
        "com.google.android.feature.PIXEL_2019_EXPERIENCE",
        "com.google.android.feature.PIXEL_2019_MIDYEAR_EXPERIENCE",
        "com.google.android.feature.PIXEL_2020_EXPERIENCE",
        "com.google.android.feature.PIXEL_2020_MIDYEAR_EXPERIENCE",
        "com.google.android.feature.PIXEL_2021_EXPERIENCE",
        "com.google.android.feature.PIXEL_2021_MIDYEAR_EXPERIENCE",
        "com.google.android.feature.PIXEL_2022_EXPERIENCE",
        "com.google.android.feature.PIXEL_2022_MIDYEAR_EXPERIENCE",
        "com.google.android.feature.PIXEL_2023_EXPERIENCE",
        "com.google.android.feature.PIXEL_2023_MIDYEAR_EXPERIENCE",
        "com.google.android.feature.PIXEL_2024_EXPERIENCE",
        "com.google.android.feature.PIXEL_2024_MIDYEAR_EXPERIENCE"
    ));

    private static volatile boolean sSpoofedPackage = false;

    /**
     * Called from {@link android.app.Instrumentation#newApplication} to spoof
     * Build.* fields for the current process if it matches a target package.
     */
    public static void setProps(Context context) {
        if (context == null || android.os.Process.isIsolated()) return;
        final String pkg = context.getPackageName();
        if (!GOOGLE_CAMERA.equals(pkg)) return;

        Log.i(TAG, "Spoofing Build props for " + pkg);
        sSpoofedPackage = true;
        CAMERA_PROPS.forEach(PixelPropsUtils::setField);
    }

    /**
     * Called from {@link android.app.ApplicationPackageManager#hasSystemFeature}
     * to intercept feature queries for spoofed packages.
     *
     * @return {@code true} if the feature should be reported as present,
     *         {@code false} to fall through to the real implementation.
     */
    public static boolean shouldSpoofFeature(String name) {
        return sSpoofedPackage && SPOOFED_FEATURES.contains(name);
    }

    private static void setField(String key, String value) {
        try {
            Field f = Build.class.getDeclaredField(key);
            f.setAccessible(true);
            f.set(null, value);
            f.setAccessible(false);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set Build." + key, e);
        }
    }
}
