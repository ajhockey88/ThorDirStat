package com.thordirstat;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The treemap with a line above it naming the folder it shows, and WinDirStat's status line
 * below it: the selected item's path, size and share.
 */
final class TreemapPane extends LinearLayout implements Session.Listener, TreemapView.Listener {
    private final Activity activity;
    private final TextView where;
    private final TextView status;
    private final TreemapView map;

    TreemapPane(Activity activity) {
        super(activity);
        this.activity = activity;
        float d = getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        setBackgroundColor(Color.BLACK);

        where = line(activity, d);
        where.setTextColor(Color.argb(200, 255, 255, 255));
        addView(where, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout frame = new FrameLayout(activity);
        map = new TreemapView(activity);
        map.setListener(this);
        frame.addView(map, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addView(frame, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        status = line(activity, d);
        status.setTextColor(Color.argb(170, 255, 255, 255));
        addView(status, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        refreshText();
    }

    private static TextView line(Activity a, float d) {
        TextView t = new TextView(a);
        t.setTextSize(13);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.START);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding((int) (8 * d), (int) (4 * d), (int) (8 * d), (int) (4 * d));
        return t;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Session.get().addListener(this);
        refreshText();
        map.rerender();
    }

    @Override
    protected void onDetachedFromWindow() {
        Session.get().removeListener(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void onSessionChanged(int what) {
        if ((what & (Session.TREE | Session.ZOOM | Session.OPTIONS | Session.SCAN)) != 0) map.rerender();
        else if ((what & (Session.SELECTION | Session.EXT)) != 0) map.invalidate();
        refreshText();
    }

    private void refreshText() {
        Session s = Session.get();
        Node zoom = s.zoom();
        if (zoom == null) {
            where.setText(s.isScanning() ? "Scanning " + s.scanLabel() + "…" : "Treemap");
            status.setText("");
            return;
        }
        where.setText("🗺️ " + (zoom.parent == null ? zoom.displayName() : zoom.path())
                + "  ·  " + Format.size(zoom.size)
                + (zoom == s.root() ? "   ·  tap: select, double-tap: zoom in, hold: actions" : ""));
        Node sel = s.selected();
        if (sel == null) {
            status.setText("");
        } else {
            String share = sel.parent != null ? "  ·  " + Format.percent(sel.size, sel.parent.size) + " of its folder" : "";
            String name = sel.isReal() && sel.parent != null ? sel.path() : sel.displayName();
            if (!sel.isReal()) name = sel.displayName() + " on " + sel.path();
            status.setText(name + "  ·  " + Format.size(sel.size) + share
                    + (sel.kind == Node.DIR ? "  ·  " + Format.count(sel.items()) + " items" : ""));
        }
    }

    @Override
    public void onTreemapLongPress(Node n) {
        Actions.show(activity, n);
    }
}
