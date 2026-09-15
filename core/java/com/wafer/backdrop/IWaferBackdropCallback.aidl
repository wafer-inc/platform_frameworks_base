/*
 * Copyright (C) 2026 The Wafer Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.wafer.backdrop;

import android.hardware.HardwareBuffer;

/**
 * Client-side callback. Invoked oneway from the service's capture thread — never
 * block here.
 *
 * @hide
 */
oneway interface IWaferBackdropCallback {

    /**
     * A new backdrop buffer is ready.
     *
     * Ownership: the buffer is valid until the next call to this method for this
     * session (or {@link #onBackdropUnavailable}). The client must NOT close() it.
     *
     * @param buffer         hardware buffer with USAGE_GPU_SAMPLED_IMAGE.
     * @param frameSeq       monotonic per-session counter. Gaps indicate drops.
     * @param presentTimeNs  CLOCK_MONOTONIC ns this buffer was produced for.
     * @param sourceWidthPx  width of the captured display area (full-res).
     * @param sourceHeightPx height of the captured display area (full-res).
     */
    void onBufferAvailable(in HardwareBuffer buffer, long frameSeq,
                           long presentTimeNs,
                           int sourceWidthPx, int sourceHeightPx);

    /**
     * No backdrop content available (secure layer, display off, target resolution
     * failed, etc.). The client should fall back to its static source.
     *
     * @param reason one of {@code REASON_*} on WaferBackdropManager.
     */
    void onBackdropUnavailable(int reason);
}
