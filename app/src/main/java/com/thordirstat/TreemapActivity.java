package com.thordirstat;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;

/**
 * The treemap on its own screen (the Thor's big top screen), while the lists sit on the
 * bottom screen. Gamepad buttons pressed here drive the lists too.
 */
public class TreemapActivity extends Activity implements Session.Listener {
    private static TreemapActivity open;

    static boolean isOpen() { return open != null; }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        open = this;
        getWindow().getDecorView().setBackgroundColor(Color.BLACK);
        setContentView(new TreemapPane(this));
        Session.get().addListener(this);
    }

    @Override
    protected void onDestroy() {
        Session.get().removeListener(this);
        if (open == this) open = null;
        super.onDestroy();
    }

    @Override
    public void onSessionChanged(int what) {
        if ((what & Session.CLOSE) != 0) finishAndRemoveTask();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        MainActivity main = MainActivity.current();
        if (main != null && main.handleKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }
}
