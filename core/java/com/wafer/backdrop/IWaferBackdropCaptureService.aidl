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

import com.wafer.backdrop.IWaferBackdropCallback;

/**
 * Privileged service that streams a live HardwareBuffer of the display contents
 * below the shade (currently: Phase 1 slow CPU capture of the topmost Task).
 *
 * Caller must hold com.wafer.permission.CAPTURE_BACKDROP (signature|privileged).
 *
 * @hide
 */
interface IWaferBackdropCaptureService {

    /**
     * Begin streaming frames to @p cb. Returns an opaque session token; release it with
     * {@link #stopSession}. Dropping the token without stopping leaks the capture thread's
     * resources until the callback binder dies.
     */
    IBinder startSession(int displayId, in IWaferBackdropCallback cb);

    /** Stop streaming and release the capture state. Idempotent. */
    void stopSession(in IBinder token);

    /** Temporarily suspend frame delivery without tearing down the capture state. */
    void pauseSession(in IBinder token);
    void resumeSession(in IBinder token);
}
