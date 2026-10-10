package com.thordirstat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

/**
 * Shows the treemap of the zoomed folder. Tap a rectangle to select that file (the lists
 * follow), double-tap to zoom into its folder, press and hold for its actions.
 *
 * Renders on a background thread into a bitmap the size of the view, then outlines the
 * selection (white) and the highlighted file type (yellow) on top.
 */
final class TreemapView extends View {
    interface Listener {
        void onTreemapLongPress(Node n);
    }

    private static HandlerThread renderThread;

    private final Handler render;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint selPaint = new Paint();
    private final Paint selShadow = new Paint();
    private final Paint typePaint = new Paint();
    private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final GestureDetector gestures;
    /** The rectangle tapped last, so tapping it again can climb to its folder. */
    private Node lastTapped;
    private final float d;

    private Treemap shown;
    private Bitmap bitmap;
    private volatile int generation;
    private Treemap pending;
    private boolean rendering;
    private Listener listener;

    TreemapView(Context context) {
        super(context);
        d = getResources().getDisplayMetrics().density;
        synchronized (TreemapView.class) {
            if (renderThread == null) {
                renderThread = new HandlerThread("ThorDirStat treemap");
                renderThread.start();
            }
        }
        render = new Handler(renderThread.getLooper());
        selPaint.setStyle(Paint.Style.STROKE);
        selPaint.setColor(Color.WHITE);
        selPaint.setStrokeWidth(Math.max(2, 2 * d));
        selShadow.setStyle(Paint.Style.STROKE);
        selShadow.setColor(Color.BLACK);
        selShadow.setStrokeWidth(Math.max(4, 4 * d));
        typePaint.setStyle(Paint.Style.STROKE);
        typePaint.setColor(Color.YELLOW);
        typePaint.setStrokeWidth(Math.max(1, 1.2f * d));
        emptyPaint.setColor(Color.argb(140, 255, 255, 255));
        emptyPaint.setTextSize(15 * d);
        emptyPaint.setTextAlign(Paint.Align.CENTER);
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                Node n = hit(e);
                if (n == null) return true;
                Session s = Session.get();
                Node sel = s.selected();
                Node leaf = n;
                // Tapping the same rectangle again selects the folder around the selection, so a
                // few taps on one tiny file climb out to the folder the whole block belongs to.
                if (leaf == lastTapped && sel != null && sel.isAncestorOf(leaf) && sel.parent != null
                        && sel != s.zoom()) {
                    n = sel.parent;
                }
                lastTapped = leaf;
                Haptics.play(getContext(), Haptics.SELECT);
                s.select(n);
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                Node n = hit(e);
                if (n == null) return true;
                Node dir = n.kind == Node.DIR ? n : n.parent;
                Node zoom = Session.get().zoom();
                // Zoom one level at a time: into the child of the current view that holds it.
                while (dir != null && dir.parent != null && dir.parent != zoom && zoom.isAncestorOf(dir.parent)) {
                    dir = dir.parent;
                }
                if (dir != null && dir != zoom) {
                    Haptics.play(getContext(), Haptics.ZOOM);
                    Session.get().select(n);
                    Session.get().zoomTo(dir);
                }
                return true;
            }

            @Override public void onLongPress(MotionEvent e) {
                Node n = hit(e);
                if (n == null) return;
                Haptics.play(getContext(), Haptics.HOLD);
                Session.get().select(n);
                if (listener != null) listener.onTreemapLongPress(n);
            }
        });
    }

    void setListener(Listener l) { listener = l; }

    private Node hit(MotionEvent e) {
        Treemap t = shown;
        return t == null ? null : t.leafAt((int) e.getX(), (int) e.getY());
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return gestures.onTouchEvent(event) || super.onTouchEvent(event);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rerender();
    }

    boolean isRendering() { return rendering; }

    /** Draws the treemap again (the tree, zoom or options changed). */
    void rerender() {
        final int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        final int gen = ++generation;
        if (pending != null) pending.cancel();
        final Node root = Session.get().zoom();
        final FileTypes types = Session.get().types();
        final Treemap.Options options = new Prefs(getContext()).treemapOptions();
        final Treemap t = new Treemap();
        pending = t;
        rendering = true;
        invalidate();
        render.post(new Runnable() {
            @Override public void run() {
                if (gen != generation) return;
                final int[] px = new int[w * h];
                if (!t.render(root, w, h, px, options, types)) return;
                final Bitmap b = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
                main.post(new Runnable() {
                    @Override public void run() {
                        if (gen != generation) {
                            b.recycle();
                            return;
                        }
                        Bitmap old = bitmap;
                        bitmap = b;
                        shown = t;
                        pending = null;
                        rendering = false;
                        if (old != null) old.recycle();
                        invalidate();
                    }
                });
            }
        });
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.BLACK);
        Session s = Session.get();
        if (s.root() == null) {
            canvas.drawText(s.isScanning() ? "Scanning…" : "Scan a drive to see its treemap",
                    getWidth() / 2f, getHeight() / 2f, emptyPaint);
            return;
        }
        if (bitmap != null) canvas.drawBitmap(bitmap, 0, 0, bitmapPaint);
        Treemap t = shown;
        if (t == null) return;
        FileTypes.Type type = s.selectedType();
        if (type != null) {
            final Canvas c = canvas;
            t.forEachWithExt(type.ext, new Treemap.RectVisitor() {
                @Override public void visit(int x0, int y0, int x1, int y1) {
                    c.drawRect(x0 + 0.5f, y0 + 0.5f, x1 - 0.5f, y1 - 0.5f, typePaint);
                }
            });
        }
        Node sel = s.selected();
        int[] r = null;
        for (Node n = sel; n != null && r == null; n = n.parent) r = t.rectOf(n);
        if (r != null && sel != s.zoom()) {
            float inset = selPaint.getStrokeWidth() / 2;
            canvas.drawRect(r[0] + inset, r[1] + inset, r[2] - inset, r[3] - inset, selShadow);
            canvas.drawRect(r[0] + inset, r[1] + inset, r[2] - inset, r[3] - inset, selPaint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        generation++;
        if (pending != null) pending.cancel();
    }
}
