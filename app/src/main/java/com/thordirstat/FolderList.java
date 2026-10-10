package com.thordirstat;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * WinDirStat's directory list: a tree of folders and files, biggest first, with each one's
 * share of its folder as a bar, its size, item count and last change.
 *
 * Tap ▸ (or tap a selected folder) to open or close it; tap a row to select it; press and
 * hold for its actions.
 */
final class FolderList extends ListView {
    interface Listener {
        void onRowLongPress(Node n);
    }

    /** WinDirStat colours the share bars by depth. */
    private static final int[] DEPTH_COLORS = {
            Color.rgb(0x46, 0x82, 0xd2), Color.rgb(0xd2, 0x64, 0x46), Color.rgb(0x5a, 0xb4, 0x5a),
            Color.rgb(0xc8, 0xa0, 0x32), Color.rgb(0x9a, 0x5c, 0xc8), Color.rgb(0x3c, 0xb4, 0xb4)};

    private final float d;
    private final List<Node> rows = new ArrayList<>();
    private final Set<Node> open = Collections.newSetFromMap(new IdentityHashMap<Node, Boolean>());
    private final Adapter adapter = new Adapter();
    private Listener listener;
    private Node lastRoot;

    FolderList(Context context) {
        super(context);
        d = getResources().getDisplayMetrics().density;
        setDivider(null);
        setBackgroundColor(Color.BLACK);
        setFocusable(false);
        setFastScrollEnabled(true);
        setAdapter(adapter);
        setOnItemClickListener(new OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Node n = rows.get(position);
                Row row = (Row) view;
                boolean onArrow = row.lastTouchX < row.arrowRight();
                if (n.kind == Node.DIR && n.children.length > 0 && (onArrow || n == Session.get().selected())) {
                    Haptics.play(getContext(), Haptics.EXPAND);
                    toggle(n);
                } else {
                    Haptics.play(getContext(), Haptics.SELECT);
                }
                Session.get().select(n);
            }
        });
        setOnItemLongClickListener(new OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                Node n = rows.get(position);
                Haptics.play(getContext(), Haptics.HOLD);
                Session.get().select(n);
                if (listener != null) listener.onRowLongPress(n);
                return true;
            }
        });
    }

    void setListener(Listener l) { listener = l; }

    /** The tree changed (new scan, rescan, delete): rebuild the visible rows. */
    void refresh() {
        Node root = Session.get().root();
        if (root != lastRoot) {
            open.clear();
            lastRoot = root;
            if (root != null) {
                open.add(root);
                // With several volumes, show each one's top folders straight away.
                if (root.label != null && root.name.isEmpty()) Collections.addAll(open, root.children);
            }
        }
        rebuild();
    }

    private void rebuild() {
        rows.clear();
        Node root = Session.get().root();
        if (root != null) addRows(root);
        adapter.notifyDataSetChanged();
    }

    private void addRows(Node n) {
        rows.add(n);
        if (n.kind == Node.DIR && open.contains(n)) for (Node c : n.children) addRows(c);
    }

    void toggle(Node n) {
        if (!open.remove(n)) open.add(n);
        rebuild();
    }

    boolean isOpen(Node n) { return open.contains(n); }

    void setOpen(Node n, boolean on) {
        if (on == open.contains(n)) return;
        toggle(n);
    }

    /** Opens the folders above the selection and scrolls it into view. */
    void reveal(Node n) {
        if (n == null) {
            adapter.notifyDataSetChanged();
            return;
        }
        boolean changed = false;
        for (Node p = n.parent; p != null; p = p.parent) changed |= open.add(p);
        if (changed) rebuild();
        else adapter.notifyDataSetChanged();
        int i = rows.indexOf(n);
        if (i < 0) return;
        int first = getFirstVisiblePosition(), last = getLastVisiblePosition();
        if (i <= first || i >= last) {
            int rowH = (int) (rowHeight());
            setSelectionFromTop(i, Math.max(0, getHeight() / 2 - rowH / 2));
        }
    }

    /** The row above or below the selection, for the gamepad. */
    Node neighbour(Node n, int step) {
        int i = rows.indexOf(n);
        if (i < 0) return rows.isEmpty() ? null : rows.get(0);
        int j = Math.max(0, Math.min(rows.size() - 1, i + step));
        return rows.get(j);
    }

    private float rowHeight() { return 34 * d; }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            Row r = convertView instanceof Row ? (Row) convertView : new Row(getContext());
            r.bind(rows.get(position));
            return r;
        }
    }

    /** One row, drawn directly (a scan can have hundreds of thousands of items). */
    final class Row extends View {
        private final TextPaint name = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint();
        private Node node;
        float lastTouchX;

        Row(Context c) {
            super(c);
            name.setTextSize(14 * d);
            name.setColor(Color.WHITE);
            small.setTextSize(12.5f * d);
            small.setColor(Color.rgb(0xaa, 0xb3, 0xc0));
            small.setTextAlign(Paint.Align.RIGHT);
            setMinimumHeight((int) rowHeight());
        }

        void bind(Node n) {
            node = n;
            invalidate();
        }

        float indent() {
            Node root = Session.get().root();
            int depth = node.depth() - (root != null ? root.depth() : 0);
            return 6 * d + depth * 14 * d;
        }

        float arrowRight() { return indent() + 28 * d; }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), (int) rowHeight());
        }

        @Override
        public boolean dispatchTouchEvent(android.view.MotionEvent e) {
            lastTouchX = e.getX();
            return super.dispatchTouchEvent(e);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (node == null) return;
            Session s = Session.get();
            int w = getWidth(), h = getHeight();
            boolean sel = node == s.selected();
            if (sel) {
                fill.setColor(Color.rgb(0x1d, 0x3a, 0x5c));
                c.drawRect(0, 0, w, h, fill);
            } else if (node == s.zoom() && node != s.root()) {
                fill.setColor(Color.rgb(0x1c, 0x1e, 0x24));
                c.drawRect(0, 0, w, h, fill);
            }
            float base = h / 2f - (name.descent() + name.ascent()) / 2;
            boolean wide = w > 560 * d;
            float sizeW = 78 * d, itemsW = 70 * d, dateW = wide ? 128 * d : 0, barW = 74 * d;
            float right = w - 8 * d;
            float dateX = right;
            float itemsX = dateX - dateW;
            float sizeX = itemsX - itemsW;
            float barRight = sizeX - sizeW - 6 * d;
            float barLeft = barRight - barW;

            // Name column: arrow, colour swatch for files, name.
            float x = indent();
            if (node.kind == Node.DIR && node.children.length > 0) {
                name.setColor(Color.argb(200, 255, 255, 255));
                c.drawText(open.contains(node) ? "▾" : "▸", x + 4 * d, base, name);
            }
            x += 18 * d;
            if (node.kind != Node.DIR) {
                fill.setColor(0xff000000 | s.types().color(node));
                float sw = 9 * d;
                c.drawRect(x, h / 2f - sw / 2, x + sw, h / 2f + sw / 2, fill);
                x += sw + 6 * d;
            } else {
                c.drawText("📁", x, base, name);
                x += 22 * d;
            }
            String label = node.displayName();
            if (node.restricted) label += "  (hidden by Android: allow Shizuku)";
            name.setColor(node.isReal() ? Color.WHITE : Color.rgb(0x9a, 0xa0, 0xaa));
            name.setTypeface(node.isReal() ? Typeface.DEFAULT : Typeface.create(Typeface.DEFAULT, Typeface.ITALIC));
            CharSequence shown = TextUtils.ellipsize(label, name, Math.max(0, barLeft - 8 * d - x), TextUtils.TruncateAt.MIDDLE);
            c.drawText(shown, 0, shown.length(), x, base, name);
            name.setTypeface(Typeface.DEFAULT);

            // Share of its folder.
            if (node.parent != null && node.parent.size > 0) {
                float frac = (float) ((double) node.size / node.parent.size);
                float top = h * 0.28f, bottom = h * 0.72f;
                fill.setColor(Color.rgb(0x22, 0x24, 0x2a));
                c.drawRect(barLeft, top, barRight, bottom, fill);
                fill.setColor(DEPTH_COLORS[Math.max(0, node.depth() - 1) % DEPTH_COLORS.length]);
                c.drawRect(barLeft, top, barLeft + barW * frac, bottom, fill);
                small.setTextAlign(Paint.Align.LEFT);
                small.setColor(Color.WHITE);
                String pct = Format.percent(node.size, node.parent.size);
                c.drawText(pct, barLeft + 4 * d, base, small);
                small.setTextAlign(Paint.Align.RIGHT);
                small.setColor(Color.rgb(0xaa, 0xb3, 0xc0));
            }
            c.drawText(Format.size(node.size), sizeX, base, small);
            if (node.kind == Node.DIR) c.drawText(Format.count(node.items()), itemsX, base, small);
            if (wide && node.isReal()) c.drawText(Format.date(node.modified), dateX, base, small);
        }
    }
}
