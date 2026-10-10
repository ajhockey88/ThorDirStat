package com.thordirstat;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.os.Bundle;
import android.view.Display;

/**
 * Invisible trampoline. With both screens in use: the lists on the bottom screen and the
 * treemap on the top screen. Otherwise everything on the screen ThorDirStat was opened from.
 */
public class LauncherActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Display lists = Displays.listDisplay(this);
        Display map = Displays.treemapDisplay(this);
        boolean dual = new Prefs(this).bothScreens() && lists != null && map != null
                && lists.getDisplayId() != map.getDisplayId();

        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(MainActivity.EXTRA_DUAL, dual);
        boolean placed = false;
        if (dual) {
            try {
                ActivityOptions options = ActivityOptions.makeBasic();
                options.setLaunchDisplayId(lists.getDisplayId());
                startActivity(intent, options.toBundle());
                placed = true;
            } catch (RuntimeException e) {
                // The system refused to place us on that screen; use one screen instead.
                intent.putExtra(MainActivity.EXTRA_DUAL, false);
            }
        }
        if (!placed) {
            try {
                startActivity(intent);
            } catch (RuntimeException ignored) {
            }
        }
        if (placed) MainActivity.openTreemapScreen(this);
        finish();
    }
}
