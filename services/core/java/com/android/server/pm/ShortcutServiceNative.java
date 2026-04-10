/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.pm;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.IShortcutServiceNative;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutInfoNative;
import android.content.pm.ShortcutManager;
import android.os.Binder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Native-friendly service for accessing shortcut information.
 * This provides a simplified interface suitable for Rust/C++ access.
 */
public class ShortcutServiceNative extends IShortcutServiceNative.Stub {
    private static final String TAG = "ShortcutServiceNative";

    private final ShortcutService mShortcutService;
    private final Object mLock = new Object();

    public ShortcutServiceNative(ShortcutService shortcutService) {
        mShortcutService = shortcutService;
        Log.i(TAG, "ShortcutServiceNative constructor called");
    }

    /**
     * Register this service with the service manager.
     */
    public static void register(ShortcutService shortcutService) {
        Log.i(TAG, "ShortcutServiceNative.register() called");
        try {
            ShortcutServiceNative service = new ShortcutServiceNative(shortcutService);
            ServiceManager.addService("shortcut_native", service);
            Log.i(TAG, "ShortcutServiceNative registered successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register ShortcutServiceNative", e);
        }
    }

    @Override
    public ShortcutInfoNative[] getDynamicShortcuts(String packageName, int userId)
            throws RemoteException {
        Log.i(TAG, "getDynamicShortcuts called for package: " + packageName + ", userId: " + userId);

        enforceCallingPermission();

        // Clear calling identity to run as system service
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                List<ShortcutInfo> shortcuts = mShortcutService.getShortcutsForQuery(
                    packageName,
                    ShortcutManager.FLAG_MATCH_DYNAMIC,
                    userId
                );

                ShortcutInfoNative[] result = convertToNative(shortcuts);
                Log.i(TAG, "Returning " + result.length + " dynamic shortcuts");
                return result;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting dynamic shortcuts", e);
            return new ShortcutInfoNative[0];
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public ShortcutInfoNative[] getShareShortcuts(String packageName, String mimeType, int userId)
            throws RemoteException {
        Log.i(TAG, "getShareShortcuts called for package: " + packageName + ", mimeType: " + mimeType + ", userId: " + userId);

        enforceCallingPermission();

        // Clear calling identity to run as system service
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                // Create an IntentFilter for the mime type
                IntentFilter filter = new IntentFilter();
                filter.addAction(Intent.ACTION_SEND);
                if (mimeType != null && !mimeType.isEmpty()) {
                    try {
                        filter.addDataType(mimeType);
                    } catch (IntentFilter.MalformedMimeTypeException e) {
                        Log.e(TAG, "Invalid MIME type: " + mimeType, e);
                        return new ShortcutInfoNative[0];
                    }
                }

                // Get share targets for this specific package
                List<ShortcutManager.ShareShortcutInfo> shareTargets =
                    mShortcutService.getShareTargetsForPackage(packageName, filter, userId);

                // Convert to native format, preserving the target component
                ShortcutInfoNative[] result = new ShortcutInfoNative[shareTargets.size()];
                for (int i = 0; i < shareTargets.size(); i++) {
                    ShortcutManager.ShareShortcutInfo shareInfo = shareTargets.get(i);
                    ShortcutInfo shortcut = shareInfo.getShortcutInfo();
                    result[i] = convertSingleToNative(shortcut);
                    // CRITICAL: Add the target component from ShareShortcutInfo
                    // This is what ChooserActivity uses to launch the shortcut
                    if (shareInfo.getTargetComponent() != null) {
                        result[i].targetComponent = shareInfo.getTargetComponent().flattenToString();
                        Log.i(TAG, "Share shortcut " + result[i].id + " has target: " + result[i].targetComponent);
                    }
                }

                Log.i(TAG, "Returning " + result.length + " share shortcuts");
                return result;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting share shortcuts", e);
            return new ShortcutInfoNative[0];
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public ShortcutInfoNative[] getAllShortcuts(String packageName, int userId)
            throws RemoteException {
        Log.i(TAG, "getAllShortcuts called for package: " + packageName + ", userId: " + userId);

        enforceCallingPermission();

        // Clear calling identity to run as system service
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                List<ShortcutInfo> shortcuts = mShortcutService.getShortcutsForQuery(
                    packageName,
                    ShortcutManager.FLAG_MATCH_DYNAMIC
                        | ShortcutManager.FLAG_MATCH_PINNED
                        | ShortcutManager.FLAG_MATCH_MANIFEST,
                    userId
                );

                ShortcutInfoNative[] result = convertToNative(shortcuts);
                Log.i(TAG, "Returning " + result.length + " total shortcuts");
                return result;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting all shortcuts", e);
            return new ShortcutInfoNative[0];
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Convert ShortcutInfo objects to the native-friendly format.
     */
    private ShortcutInfoNative[] convertToNative(@NonNull List<ShortcutInfo> shortcuts) {
        ShortcutInfoNative[] result = new ShortcutInfoNative[shortcuts.size()];

        for (int i = 0; i < shortcuts.size(); i++) {
            result[i] = convertSingleToNative(shortcuts.get(i));
        }

        return result;
    }

    /**
     * Convert a single ShortcutInfo to native format.
     */
    private ShortcutInfoNative convertSingleToNative(@NonNull ShortcutInfo shortcut) {
            ShortcutInfoNative nativeInfo = new ShortcutInfoNative();

            // Basic info
            nativeInfo.id = shortcut.getId();
            nativeInfo.packageName = shortcut.getPackage();
            nativeInfo.shortLabel = shortcut.getShortLabel() != null ?
                shortcut.getShortLabel().toString() : "";
            nativeInfo.longLabel = shortcut.getLongLabel() != null ?
                shortcut.getLongLabel().toString() : "";

            // Component info
            if (shortcut.getActivity() != null) {
                nativeInfo.activityComponent = shortcut.getActivity().flattenToString();
            } else {
                nativeInfo.activityComponent = "";
            }

            // Categories
            Set<String> categories = shortcut.getCategories();
            if (categories != null) {
                nativeInfo.categories = categories.toArray(new String[0]);
            } else {
                nativeInfo.categories = new String[0];
            }

            // Flags and state
            nativeInfo.rank = shortcut.getRank();
            nativeInfo.isDynamic = shortcut.isDynamic();
            nativeInfo.isPinned = shortcut.isPinned();
            nativeInfo.isManifest = shortcut.isDeclaredInManifest();
            nativeInfo.lastChangedTimestamp = shortcut.getLastChangedTimestamp();

            // Initialize targetComponent to empty (will be set for share shortcuts)
            nativeInfo.targetComponent = "";

            return nativeInfo;
    }

    /**
     * Enforce that the caller has the necessary permissions.
     */
    private void enforceCallingPermission() {
        // Allow system services and shell
        final int callingUid = Binder.getCallingUid();
        if (callingUid == android.os.Process.SYSTEM_UID
            || callingUid == android.os.Process.SHELL_UID
            || callingUid == android.os.Process.ROOT_UID) {
            return;
        }

        // Check for MANAGE_APP_PREDICTIONS permission (same as share targets)
        mShortcutService.getContext().enforceCallingOrSelfPermission(
            android.Manifest.permission.MANAGE_APP_PREDICTIONS,
            "ShortcutServiceNative requires MANAGE_APP_PREDICTIONS permission");
    }
}