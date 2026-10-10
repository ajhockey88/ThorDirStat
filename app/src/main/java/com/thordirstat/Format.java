package com.thordirstat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Sizes, counts and dates as WinDirStat shows them. */
final class Format {
    private Format() {}

    static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int u = -1;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return String.format(Locale.US, v >= 100 ? "%.0f %s" : v >= 10 ? "%.1f %s" : "%.2f %s", v, units[u]);
    }

    static String percent(long part, long whole) {
        if (whole <= 0) return "";
        double p = 100.0 * part / whole;
        return String.format(Locale.US, "%.1f%%", p);
    }

    static String count(long n) {
        return String.format(Locale.US, "%,d", n);
    }

    private static final SimpleDateFormat DATE = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);

    static synchronized String date(long ms) {
        return ms <= 0 ? "" : DATE.format(new Date(ms));
    }
}
