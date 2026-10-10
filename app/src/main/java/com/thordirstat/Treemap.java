package com.thordirstat;

import java.util.Arrays;

/**
 * WinDirStat's treemap: every file is a rectangle sized by its bytes, nested inside its
 * folder's rectangle, and shaded as a "cushion" so the folder structure shows as bumps
 * (van Wijk and van de Wetering's cushion treemaps, squarified layout).
 *
 * Renders into a plain int[] of pixels and remembers where each node landed, so the view can
 * find the file under a finger and outline the selection. No Android classes here.
 */
final class Treemap {
    /** The treemap options from Settings. */
    static final class Options {
        boolean cushion = true;
        boolean grid = false;
        /** 0.5 to 1.5; WinDirStat's default is 0.88. */
        float brightness = 0.88f;
        /** Cushion height, 0 to 1. */
        float height = 0.39f;
        /** How much flatter each level's cushion is than its parent's, 0.1 to 1. */
        float scale = 0.75f;
        /** Light that reaches the sides facing away from the lamp, 0 to 1. */
        float ambient = 0.15f;
    }

    /** The colour of each leaf rectangle, as 0xRRGGBB. */
    interface Colors {
        int color(Node leaf);
    }

    private static final double PALETTE_BRIGHTNESS = 0.6;
    // The lamp sits up and to the top left, as in WinDirStat.
    private static final double LX, LY, LZ;

    static {
        double len = Math.sqrt(1 + 1 + 100);
        LX = -1 / len;
        LY = -1 / len;
        LZ = 10 / len;
    }

    static final int GRID_COLOR = 0x202020;

    // Where every drawn node landed: its pixel rect x0, y0, x1, y1 (exclusive) and if it's a leaf.
    private Node[] nodes = new Node[1024];
    private int[] rects = new int[4096];
    private boolean[] leaf = new boolean[1024];
    private int count;
    private int width, height;
    private int[] px;
    private Options o;
    private Colors colors;
    private volatile boolean cancelled;

    int width() { return width; }

    int height() { return height; }

    /** Stops a render running on another thread; render then returns false. */
    void cancel() { cancelled = true; }

    /** Draws root into pixels (w × h, 0xAARRGGBB). Returns false if it was cancelled. */
    boolean render(Node root, int w, int h, int[] pixels, Options options, Colors colorSource) {
        cancelled = false;
        width = w;
        height = h;
        px = pixels;
        o = options;
        colors = colorSource;
        count = 0;
        Arrays.fill(pixels, 0, w * h, 0xff000000);
        if (root == null || root.size <= 0 || w <= 0 || h <= 0) return true;
        draw(root, 0, 0, w, h, new double[4], o.height);
        return !cancelled;
    }

    private void draw(Node n, double x, double y, double w, double h, double[] parentSurface, double ridge) {
        if (cancelled) return;
        int x0 = (int) Math.round(x), y0 = (int) Math.round(y);
        int x1 = (int) Math.round(x + w), y1 = (int) Math.round(y + h);
        if (x1 <= x0 || y1 <= y0) return;
        double[] s = parentSurface.clone();
        if (o.cushion) addRidges(s, x, y, w, h, ridge);
        boolean isLeaf = n.kind != Node.DIR || n.children.length == 0 || n.size <= 0
                || (x1 - x0) * (y1 - y0) < 2;
        record(n, x0, y0, x1, y1, isLeaf);
        if (!isLeaf) {
            double[] r = new double[n.children.length * 4];
            int laid = layout(n, x, y, w, h, r);
            for (int i = 0; i < laid; i++) {
                draw(n.children[i], r[4 * i], r[4 * i + 1], r[4 * i + 2], r[4 * i + 3], s, ridge * o.scale);
            }
        } else {
            paint(x0, y0, x1, y1, n.kind == Node.DIR ? 0x5a5a5a : colors.color(n), s);
        }
    }

    /**
     * Squarified layout of a folder's children (biggest first) inside x, y, w, h. Writes
     * x, y, w, h per child to out and returns how many got a rectangle (empty ones don't).
     */
    static int layout(Node dir, double x, double y, double w, double h, double[] out) {
        Node[] c = dir.children;
        int n = 0;
        while (n < c.length && c[n].size > 0) n++;
        if (n == 0) return 0;
        double remaining = 0;
        for (int i = 0; i < n; i++) remaining += c[i].size;
        int i = 0;
        while (i < n) {
            boolean horizontal = w >= h; // the row runs down the left side, or across the top
            double side = horizontal ? h : w;
            double scale = (w * h) / remaining; // pixels per byte
            double rowSum = 0, worst = Double.MAX_VALUE;
            int j = i;
            while (j < n) {
                double next = rowSum + c[j].size;
                double thick = next * scale / side;
                double big = c[i].size * scale / thick, small = c[j].size * scale / thick;
                double r = Math.max(aspect(thick, big), aspect(thick, small));
                if (j > i && r > worst) break;
                worst = r;
                rowSum = next;
                j++;
            }
            boolean last = j >= n;
            double thick = last ? (horizontal ? w : h) : rowSum * scale / side;
            double pos = horizontal ? y : x;
            double end = pos + side;
            for (int k = i; k < j; k++) {
                double len = k == j - 1 ? end - pos : side * c[k].size / rowSum;
                if (horizontal) {
                    out[4 * k] = x;
                    out[4 * k + 1] = pos;
                    out[4 * k + 2] = thick;
                    out[4 * k + 3] = len;
                } else {
                    out[4 * k] = pos;
                    out[4 * k + 1] = y;
                    out[4 * k + 2] = len;
                    out[4 * k + 3] = thick;
                }
                pos += len;
            }
            if (horizontal) {
                x += thick;
                w -= thick;
            } else {
                y += thick;
                h -= thick;
            }
            if (w < 0) w = 0;
            if (h < 0) h = 0;
            remaining -= rowSum;
            i = j;
        }
        return n;
    }

    private static double aspect(double a, double b) {
        if (a <= 0 || b <= 0) return Double.MAX_VALUE;
        return a > b ? a / b : b / a;
    }

    /** Adds this rectangle's bump to the cushion surface z = s0·x² + s1·y² + s2·x + s3·y. */
    private static void addRidges(double[] s, double x, double y, double w, double h, double ridge) {
        if (w > 0) {
            double x2 = x + w;
            s[2] += 4 * ridge * (x2 + x) / w;
            s[0] -= 4 * ridge / w;
        }
        if (h > 0) {
            double y2 = y + h;
            s[3] += 4 * ridge * (y2 + y) / h;
            s[1] -= 4 * ridge / h;
        }
    }

    private void paint(int x0, int y0, int x1, int y1, int rgb, double[] s) {
        double[] c = normalize(rgb);
        double gain = o.brightness / PALETTE_BRIGHTNESS;
        double ia = o.ambient, is = 1 - ia;
        int gx = o.grid && x1 - x0 > 2 ? x1 - 1 : Integer.MAX_VALUE;
        int gy = o.grid && y1 - y0 > 2 ? y1 - 1 : Integer.MAX_VALUE;
        for (int py = y0; py < y1; py++) {
            int row = py * width;
            double fy = py + 0.5;
            double ny = -(2 * s[1] * fy + s[3]);
            for (int pxl = x0; pxl < x1; pxl++) {
                if (pxl == gx || py == gy) {
                    px[row + pxl] = 0xff000000 | GRID_COLOR;
                    continue;
                }
                double light;
                if (o.cushion) {
                    double nx = -(2 * s[0] * (pxl + 0.5) + s[2]);
                    double cos = (nx * LX + ny * LY + LZ) / Math.sqrt(nx * nx + ny * ny + 1);
                    if (cos > 1) cos = 1;
                    light = (is * Math.max(cos, 0) + ia) * gain;
                } else {
                    light = gain * 0.85;
                }
                px[row + pxl] = 0xff000000 | (clamp(c[0] * light) << 16) | (clamp(c[1] * light) << 8)
                        | clamp(c[2] * light);
            }
        }
    }

    private static int clamp(double v) {
        return v <= 0 ? 0 : v >= 255 ? 255 : (int) v;
    }

    /**
     * Scales a colour to WinDirStat's palette brightness so every file type shades alike,
     * passing what spills over 255 in one channel on to the others.
     */
    static double[] normalize(int rgb) {
        double[] c = {(rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff};
        double sum = c[0] + c[1] + c[2];
        if (sum <= 0) return new double[] {0, 0, 0};
        double f = PALETTE_BRIGHTNESS * 3 * 255 / sum;
        for (int i = 0; i < 3; i++) c[i] *= f;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < 3; i++) {
                if (c[i] > 255) {
                    double over = c[i] - 255;
                    c[i] = 255;
                    c[(i + 1) % 3] += over / 2;
                    c[(i + 2) % 3] += over / 2;
                }
            }
        }
        return c;
    }

    private void record(Node n, int x0, int y0, int x1, int y1, boolean isLeaf) {
        if (count == nodes.length) {
            nodes = Arrays.copyOf(nodes, count * 2);
            leaf = Arrays.copyOf(leaf, count * 2);
            rects = Arrays.copyOf(rects, count * 8);
        }
        nodes[count] = n;
        leaf[count] = isLeaf;
        rects[4 * count] = x0;
        rects[4 * count + 1] = y0;
        rects[4 * count + 2] = x1;
        rects[4 * count + 3] = y1;
        count++;
    }

    /** The file (or too-small-to-split folder) drawn at a pixel, or null. */
    Node leafAt(int x, int y) {
        for (int i = 0; i < count; i++) {
            if (leaf[i] && x >= rects[4 * i] && x < rects[4 * i + 2] && y >= rects[4 * i + 1] && y < rects[4 * i + 3]) {
                return nodes[i];
            }
        }
        return null;
    }

    /** Where a node was drawn, as x0, y0, x1, y1, or null if it was too small to show. */
    int[] rectOf(Node n) {
        for (int i = 0; i < count; i++) {
            if (nodes[i] == n) return Arrays.copyOfRange(rects, 4 * i, 4 * i + 4);
        }
        return null;
    }

    /** Calls back with the rectangle of every drawn file whose extension is ext. */
    interface RectVisitor {
        void visit(int x0, int y0, int x1, int y1);
    }

    void forEachWithExt(String ext, RectVisitor v) {
        for (int i = 0; i < count; i++) {
            Node n = nodes[i];
            if (leaf[i] && n.kind == Node.FILE && eq(ext, n.ext)) {
                v.visit(rects[4 * i], rects[4 * i + 1], rects[4 * i + 2], rects[4 * i + 3]);
            }
        }
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
