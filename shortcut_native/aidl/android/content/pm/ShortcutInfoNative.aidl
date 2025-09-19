/*
 * Copyright 2024, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.content.pm;

/**
 * Simplified shortcut information for native code access.
 * {@hide}
 */
parcelable ShortcutInfoNative {
    String id;
    String packageName;
    String shortLabel;
    String longLabel;
    int rank;
    String activityComponent;
    String[] categories;
    boolean isDynamic;
    boolean isPinned;
    boolean isManifest;
    long lastChangedTimestamp;
    // For share shortcuts - the actual target component to launch
    String targetComponent;
}