package com.thordirstat;

import android.app.AppOpsManager;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.Process;
import android.os.storage.StorageManager;

import java.util.ArrayList;
import java.util.List;

/**
 * How much internal storage each installed app takes (its code, private data and cache), so
 * the space that would otherwise be <Unknown> can be shown app by app. Reading other apps'
 * sizes needs Android's usage access, which Shizuku can switch on by itself.
 */
final class AppSizes {
    private AppSizes() {}

    static final class App {
        final String pkg, label;
        /** Code (APKs, compiled code, libraries, OBB files), private data, and the cache within it. */
        final long app, data, cache;

        App(String pkg, String label, long app, long data, long cache) {
            this.pkg = pkg;
            this.label = label;
            this.app = app;
            this.data = data;
            this.cache = cache;
        }
    }

    /** Apps live on internal storage: the volume /storage/emulated/0 sits on. */
    static boolean isInternal(String volumePath) {
        return Environment.getExternalStorageDirectory().getPath().equals(volumePath);
    }

    static boolean allowed(Context context) {
        AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(),
                context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    /** Every app's size, or null without usage access. Slow-ish: call off the main thread. */
    static List<App> query(Context context) {
        if (!allowed(context) && ShizukuClient.hasPermission()) {
            ShizukuClient.runShell("appops set " + context.getPackageName() + " GET_USAGE_STATS allow");
        }
        if (!allowed(context)) return null;
        StorageStatsManager ssm = (StorageStatsManager) context.getSystemService(Context.STORAGE_STATS_SERVICE);
        PackageManager pm = context.getPackageManager();
        List<App> out = new ArrayList<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if (!StorageManager.UUID_DEFAULT.equals(ai.storageUuid)) continue;
            try {
                StorageStats st = ssm.queryStatsForPackage(ai.storageUuid, ai.packageName, Process.myUserHandle());
                out.add(new App(ai.packageName, pm.getApplicationLabel(ai).toString(),
                        st.getAppBytes(), st.getDataBytes(), st.getCacheBytes()));
            } catch (Exception ignored) {
                // Uninstalled while we were looking, or not ours to see.
            }
        }
        return out;
    }
}
