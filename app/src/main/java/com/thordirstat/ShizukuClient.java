package com.thordirstat;

import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

import java.util.concurrent.CopyOnWriteArrayList;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuApplication;
import moe.shizuku.server.IShizukuService;

/**
 * A minimal Shizuku client. Shizuku (a free app many Thor owners already use) runs a helper
 * with ADB "shell" rights; ThorDirStat asks it to measure the folders Android hides from apps
 * (Android/data and Android/obb), to delete files there, and to grant all-files access. This mirrors what Shizuku's own client library does, kept
 * small so it builds with Java 8 tools.
 */
final class ShizukuClient {
    interface Listener { void onShizukuChanged(); }

    private static final int API_VERSION = 13;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private static IBinder binder;
    private static IShizukuService service;
    private static volatile boolean granted;

    private ShizukuClient() {}

    static void addListener(Listener l) { listeners.add(l); }

    static void removeListener(Listener l) { listeners.remove(l); }

    /** Shizuku is installed, running, and has handed us its binder. */
    static boolean isRunning() {
        return binder != null && binder.pingBinder();
    }

    static boolean hasPermission() {
        if (!isRunning()) return false;
        if (granted) return true;
        try {
            granted = service.checkSelfPermission();
        } catch (RemoteException | RuntimeException e) {
            granted = false;
        }
        return granted;
    }

    /** Shows Shizuku's "Allow ThorDirStat?" prompt. */
    static void requestPermission() {
        if (!isRunning()) return;
        try {
            service.requestPermission(1);
        } catch (RemoteException | RuntimeException ignored) {
        }
    }

    /** Runs a command as the Shizuku (shell) user. */
    static IRemoteProcess newProcess(String[] cmd) throws RemoteException {
        if (!isRunning()) throw new RemoteException("Shizuku is not running");
        return service.newProcess(cmd, null, null);
    }

    /** Runs a shell command as the Shizuku user and waits for it. Call off the main thread. */
    static boolean runShell(String command) {
        try {
            IRemoteProcess p = newProcess(new String[] {"sh", "-c", command});
            int code = p.waitFor();
            p.destroy();
            return code == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Called by {@link ShizukuBinderProvider} when Shizuku sends us its binder. */
    static void onBinderReceived(IBinder newBinder, String packageName) {
        if (newBinder == null || newBinder == binder) return;
        binder = newBinder;
        service = IShizukuService.Stub.asInterface(newBinder);
        granted = false;
        try {
            newBinder.linkToDeath(new IBinder.DeathRecipient() {
                @Override public void binderDied() {
                    binder = null;
                    service = null;
                    granted = false;
                    notifyChanged();
                }
            }, 0);
        } catch (RemoteException ignored) {
        }
        Bundle args = new Bundle();
        args.putInt("shizuku:attach-api-version", API_VERSION);
        args.putString("shizuku:attach-package-name", packageName);
        try {
            service.attachApplication(APPLICATION, args);
        } catch (RemoteException | RuntimeException ignored) {
        }
        notifyChanged();
    }

    private static final IShizukuApplication APPLICATION = new IShizukuApplication.Stub() {
        @Override public void bindApplication(Bundle data) {
            granted = data.getBoolean("shizuku:attach-reply-permission-granted", false);
            notifyChanged();
        }

        @Override public void dispatchRequestPermissionResult(int requestCode, Bundle data) {
            granted = data.getBoolean("shizuku:request-permission-reply-allowed", false);
            notifyChanged();
        }

        @Override public void showPermissionConfirmation(int requestUid, int requestPid,
                                                         String requestPackageName, int requestCode) {
        }
    };

    private static void notifyChanged() {
        main.post(new Runnable() {
            @Override public void run() {
                for (Listener l : listeners) l.onShizukuChanged();
            }
        });
    }
}
