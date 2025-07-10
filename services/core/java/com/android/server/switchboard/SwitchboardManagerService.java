package com.android.server.switchboard;

import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;

import com.android.server.SystemService;

import switchboard.ISwitchboardService;
import switchboard.ISwitchboardCallback;

/**
 * SystemService wrapper for Switchboard native service.
 * This allows system_server to monitor and manage the native switchboard service.
 */
public class SwitchboardManagerService extends SystemService {
    private static final String TAG = "SwitchboardManagerService";
    private static final String SWITCHBOARD_SERVICE = "switchboardservice";
    
    private ISwitchboardService mSwitchboardService;
    private final Object mLock = new Object();
    
    public SwitchboardManagerService(Context context) {
        super(context);
    }
    
    @Override
    public void onStart() {
        Slog.i(TAG, "Starting SwitchboardManagerService");
        // Connect to the native service
        connectToNativeService();
        
        // Publish the binder service wrapper if needed
        // publishBinderService(Context.SWITCHBOARD_SERVICE, new BinderService());
    }
    
    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            Slog.i(TAG, "System services ready, ensuring switchboard connection");
            ensureServiceConnection();
        }
    }
    
    private void connectToNativeService() {
        synchronized (mLock) {
            IBinder binder = ServiceManager.getService(SWITCHBOARD_SERVICE);
            if (binder != null) {
                mSwitchboardService = ISwitchboardService.Stub.asInterface(binder);
                Slog.i(TAG, "Connected to native switchboard service");
                
                // Set up death recipient to handle service crashes
                try {
                    binder.linkToDeath(new DeathRecipient(), 0);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to link death recipient", e);
                }
            } else {
                Slog.w(TAG, "Native switchboard service not available yet");
            }
        }
    }
    
    private void ensureServiceConnection() {
        synchronized (mLock) {
            if (mSwitchboardService == null) {
                connectToNativeService();
            }
        }
    }
    
    private class DeathRecipient implements IBinder.DeathRecipient {
        @Override
        public void binderDied() {
            Slog.w(TAG, "Native switchboard service died, reconnecting...");
            synchronized (mLock) {
                mSwitchboardService = null;
            }
            // Attempt to reconnect
            connectToNativeService();
        }
    }
    
    // Public API methods that system_server components can use
    
    /**
     * Lookup a notification in the switchboard database.
     * @return The lookup result or null if service is not available
     */
    public String lookupNotification(String app, String title, String body) {
        synchronized (mLock) {
            if (mSwitchboardService != null) {
                try {
                    return mSwitchboardService.lookupNotification(app, title, body);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to lookup notification", e);
                }
            }
        }
        return null;
    }
    
    /**
     * Gather context from the current state.
     * @param callback The callback to receive the context
     */
    public void gatherContext(final ISwitchboardCallback callback) {
        synchronized (mLock) {
            if (mSwitchboardService != null) {
                try {
                    mSwitchboardService.gatherContext(callback);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to gather context", e);
                    // Notify callback of error
                    if (callback != null) {
                        try {
                            callback.onError(-1, "Failed to gather context: " + e.getMessage());
                        } catch (RemoteException re) {
                            Slog.e(TAG, "Failed to notify callback of error", re);
                        }
                    }
                }
            } else if (callback != null) {
                try {
                    callback.onError(-2, "Switchboard service not available");
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to notify callback of error", e);
                }
            }
        }
    }
    
    /**
     * Send a span click event to the switchboard service.
     * @param clickEvent The click event as a JSON string
     * @param callback The callback to receive the updated event
     */
    public void spanClick(String clickEvent, final ISwitchboardCallback callback) {
        synchronized (mLock) {
            if (mSwitchboardService != null) {
                try {
                    mSwitchboardService.spanClick(clickEvent, callback);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to send span click", e);
                    // Notify callback of error
                    if (callback != null) {
                        try {
                            callback.onError(-1, "Failed to send span click: " + e.getMessage());
                        } catch (RemoteException re) {
                            Slog.e(TAG, "Failed to notify callback of error", re);
                        }
                    }
                }
            } else if (callback != null) {
                try {
                    callback.onError(-2, "Switchboard service not available");
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to notify callback of error", e);
                }
            }
        }
    }
    
    /**
     * Take an action based on the description.
     * @return true if action was successful, false otherwise
     */
    public boolean takeAction(String packageName, String actionDescription) {
        synchronized (mLock) {
            if (mSwitchboardService != null) {
                try {
                    return mSwitchboardService.takeAction(packageName, actionDescription);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to take action", e);
                }
            }
        }
        return false;
    }
}
