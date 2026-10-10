package com.thordirstat;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The treemap with a line above it naming the folder it shows, and WinDirStat's status line
 * below it: the selected item's path, size and share.
 *
 * Tapping a file or folder in the treemap brings up a card with a Delete button. Delete
 * removes it at once; the card then offers Undo for a few seconds before it's gone for good.
 */
final class TreemapPane extends LinearLayout implements Session.Listener, TreemapView.Listener {
    private final Activity activity;
    private final TextView where;
    private final TextView status;
    private final TreemapView map;
    private final float d;
    private final LinearLayout card;
    private final TextView cardText;
    private final Button cardAction;
    private final Button cardClose;
    /** What the card offers to delete, or null while it shows Undo (or is hidden). */
    private Node cardNode;

    TreemapPane(Activity activity) {
        super(activity);
        this.activity = activity;
        d = getResources().getDisplayMetrics().density;
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

        card = new LinearLayout(activity);
        card.setOrientation(HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding((int) (14 * d), (int) (8 * d), (int) (8 * d), (int) (8 * d));
        card.setBackground(rounded(Color.argb(235, 0x16, 0x18, 0x1d), 14));
        card.setElevation(8 * d);
        card.setClickable(true); // taps on the card don't reach the treemap
        cardText = new TextView(activity);
        cardText.setTextSize(15);
        cardText.setTextColor(Color.WHITE);
        cardText.setSingleLine(true);
        cardText.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        card.addView(cardText, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        cardAction = new Button(activity);
        cardAction.setAllCaps(false);
        cardAction.setTextSize(15);
        cardAction.setTextColor(Color.WHITE);
        cardAction.setPadding((int) (16 * d), 0, (int) (16 * d), 0);
        cardAction.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { cardActionClicked(); }
        });
        LayoutParams alp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (int) (44 * d));
        alp.leftMargin = (int) (12 * d);
        card.addView(cardAction, alp);
        cardClose = new Button(activity);
        cardClose.setText("✕");
        cardClose.setTextSize(16);
        cardClose.setTextColor(Color.argb(200, 255, 255, 255));
        cardClose.setBackground(rounded(Color.rgb(0x2a, 0x2d, 0x35), 10));
        cardClose.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { hideCard(); }
        });
        LayoutParams clp = new LayoutParams((int) (44 * d), (int) (44 * d));
        clp.leftMargin = (int) (8 * d);
        card.addView(cardClose, clp);
        card.setVisibility(View.GONE);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                (int) (480 * d), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cardLp.bottomMargin = (int) (16 * d);
        frame.addView(card, cardLp);
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

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(radiusDp * d);
        return bg;
    }

    // ---- Delete card ----------------------------------------------------------------------------

    @Override
    public void onTreemapTap(Node n) {
        if (Session.get().pendingDelete() != null) return; // the Undo card stays up
        if (Session.get().canDelete(n)) showDelete(n);
        else hideCard();
    }

    private void showDelete(Node n) {
        cardNode = n;
        cardText.setText((n.kind == Node.DIR ? "📁 " : "") + n.name + "  ·  " + Format.size(n.size));
        cardAction.setText("🗑️ Delete");
        cardAction.setBackground(rounded(Color.rgb(0xb3, 0x2d, 0x2d), 10));
        cardClose.setVisibility(View.VISIBLE);
        card.setVisibility(View.VISIBLE);
    }

    private void showUndo(Node n) {
        cardNode = null;
        cardText.setText("Deleted " + n.name + "  ·  " + Format.size(n.size) + " freed");
        cardAction.setText("↩️ Undo");
        cardAction.setBackground(rounded(Color.rgb(0x2a, 0x4a, 0x70), 10));
        cardClose.setVisibility(View.GONE);
        card.setVisibility(View.VISIBLE);
    }

    private void hideCard() {
        cardNode = null;
        card.setVisibility(View.GONE);
    }

    private void cardActionClicked() {
        Session s = Session.get();
        if (cardNode != null) {
            Haptics.play(activity, Haptics.DELETE);
            s.quickDelete(activity, cardNode);
        } else if (s.pendingDelete() != null) {
            Haptics.play(activity, Haptics.BAR);
            s.undoDelete();
        }
    }

    @Override
    public void onSessionChanged(int what) {
        Session session = Session.get();
        if ((what & Session.UNDO) != 0) {
            Node p = session.pendingDelete();
            if (p != null) showUndo(p);
            else hideCard();
        } else if (cardNode != null && ((what & Session.ZOOM) != 0
                || ((what & Session.SELECTION) != 0 && session.selected() != cardNode)
                || cardNode.parent == null)) {
            hideCard();
        }
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
                + (zoom == s.root() ? "   ·  tap: select or delete, double-tap: zoom in, hold: actions" : ""));
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
