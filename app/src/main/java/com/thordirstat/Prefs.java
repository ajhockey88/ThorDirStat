package com.thordirstat;

import android.content.Context;
import android.content.SharedPreferences;

/** User-tunable settings, shared by the screens and the treemap. */
final class Prefs {
    static final String KEY_BOTH_SCREENS = "both_screens";
    static final String KEY_SWAP_SCREENS = "swap_screens";
    static final String KEY_SHOW_UNKNOWN = "show_unknown";
    static final String KEY_SIZE_ON_DISK = "size_on_disk";
    static final String KEY_CUSHION = "cushion";
    static final String KEY_GRID = "grid";
    static final String KEY_BRIGHTNESS = "brightness";
    static final String KEY_CUSHION_HEIGHT = "cushion_height";
    static final String KEY_CUSHION_SCALE = "cushion_scale";
    static final String KEY_LAST_VOLUMES = "last_volumes";

    private final SharedPreferences sp;

    Prefs(Context context) {
        sp = context.getApplicationContext().getSharedPreferences("thordirstat", Context.MODE_PRIVATE);
    }

    SharedPreferences raw() { return sp; }

    /** Folder lists on the bottom screen and the treemap on the top screen. */
    boolean bothScreens() { return sp.getBoolean(KEY_BOTH_SCREENS, true); }
    /** Treemap on the bottom screen, lists on the top. */
    boolean swapScreens() { return sp.getBoolean(KEY_SWAP_SCREENS, false); }
    /** Show the used space no folder accounts for (apps, system) as <Unknown>. */
    boolean showUnknown() { return sp.getBoolean(KEY_SHOW_UNKNOWN, true); }
    /** Count the space files take on disk instead of their length. */
    boolean sizeOnDisk() { return sp.getBoolean(KEY_SIZE_ON_DISK, false); }

    Treemap.Options treemapOptions() {
        Treemap.Options o = new Treemap.Options();
        o.cushion = sp.getBoolean(KEY_CUSHION, true);
        o.grid = sp.getBoolean(KEY_GRID, false);
        o.brightness = sp.getFloat(KEY_BRIGHTNESS, 0.88f);
        o.height = sp.getFloat(KEY_CUSHION_HEIGHT, 0.39f);
        o.scale = sp.getFloat(KEY_CUSHION_SCALE, 0.75f);
        return o;
    }
}
