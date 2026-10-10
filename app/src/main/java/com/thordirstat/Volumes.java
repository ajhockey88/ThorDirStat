package com.thordirstat;

import android.content.Context;
import android.os.Environment;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** The Thor's storage: internal storage and any SD card or USB drive that's plugged in. */
final class Volumes {
    private Volumes() {}

    static final class Volume {
        final String path, label;
        final boolean removable;
        final long total, free;

        Volume(String path, String label, boolean removable, long total, long free) {
            this.path = path;
            this.label = label;
            this.removable = removable;
            this.total = total;
            this.free = free;
        }

        long used() { return Math.max(0, total - free); }
    }

    static List<Volume> list(Context context) {
        List<Volume> out = new ArrayList<>();
        StorageManager sm = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
        for (StorageVolume v : sm.getStorageVolumes()) {
            if (!Environment.MEDIA_MOUNTED.equals(v.getState())
                    && !Environment.MEDIA_MOUNTED_READ_ONLY.equals(v.getState())) continue;
            File dir = v.getDirectory();
            if (dir == null) continue;
            long total = 0, free = 0;
            try {
                StatFs fs = new StatFs(dir.getPath());
                total = fs.getTotalBytes();
                free = fs.getAvailableBytes();
            } catch (IllegalArgumentException ignored) {
            }
            String label = v.isPrimary() ? "Internal storage" : v.getDescription(context);
            out.add(new Volume(dir.getPath(), label, v.isRemovable(), total, free));
        }
        return out;
    }
}
