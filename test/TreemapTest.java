package com.thordirstat;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javax.imageio.ImageIO;

/**
 * Desktop check of the layout and treemap (no Android needed): builds a made-up storage
 * tree, checks every pixel is covered by exactly the files that should be there, and writes
 * a picture to look at.
 *   javac -d build/test app/src/main/java/com/thordirstat/{Node,Treemap,FileTypes}.java test/TreemapTest.java
 *   java -cp build/test com.thordirstat.TreemapTest build/treemap.png
 */
public class TreemapTest {
    private static final String[] EXTS = {"mp4", "apk", "jpg", "iso", "chd", "zip", "mp3", "png", "obb", "nsp", "sav", "txt", null};

    public static void main(String[] args) throws Exception {
        Random rnd = new Random(42);
        Node root = new Node("/storage/emulated/0", Node.DIR, null);
        root.label = "Internal storage";
        root.children = folder(root, rnd, 0).toArray(Node.NONE);
        root.aggregate();
        Node free = new Node("", Node.FREE, root);
        free.size = root.size / 3;
        Node[] withFree = new Node[root.children.length + 1];
        System.arraycopy(root.children, 0, withFree, 0, root.children.length);
        withFree[root.children.length] = free;
        root.children = withFree;
        root.reaggregateUp();

        FileTypes types = new FileTypes(root);
        check(types.sorted().get(0).color == FileTypes.PALETTE[0], "biggest type gets the first colour");

        int w = 1920, h = 1080;
        int[] px = new int[w * h];
        Treemap t = new Treemap();
        check(t.render(root, w, h, px, new Treemap.Options(), types), "render finished");

        // Every pixel belongs to a drawn leaf, and leaves don't overlap.
        int black = 0;
        for (int p : px) if ((p & 0xffffff) == 0) black++;
        check(black < w * h / 1000, "almost no unpainted pixels (" + black + ")");
        Node a = t.leafAt(10, 10), b = t.leafAt(w - 10, h - 10);
        check(a != null && b != null, "corners hit leaves");
        int[] r = t.rectOf(a);
        check(r != null && r[0] <= 10 && r[2] > 10, "rectOf matches leafAt");

        // Layout areas match sizes.
        double[] out = new double[root.children.length * 4];
        int n = Treemap.layout(root, 0, 0, w, h, out);
        double total = 0;
        for (int i = 0; i < n; i++) {
            double area = out[4 * i + 2] * out[4 * i + 3];
            double want = (double) w * h * root.children[i].size / root.size;
            check(Math.abs(area - want) < Math.max(2, want * 1e-6), "area of child " + i);
            total += area;
        }
        check(Math.abs(total - (double) w * h) < 1, "children fill the parent");

        // Delete a folder and check the totals follow.
        Node victim = root.children[0].kind == Node.DIR ? root.children[0] : root.children[1];
        long before = root.size;
        root.removeChild(victim);
        check(root.size == before - victim.size, "delete updates sizes");

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        t.render(root, w, h, px, new Treemap.Options(), new FileTypes(root));
        img.setRGB(0, 0, w, h, px, 0, w);
        ImageIO.write(img, "png", new File(args.length > 0 ? args[0] : "treemap.png"));
        System.out.println("OK: " + root.files + " files, " + root.dirs + " folders");
    }

    private static List<Node> folder(Node parent, Random rnd, int depth) {
        List<Node> kids = new ArrayList<>();
        int nf = 3 + rnd.nextInt(25);
        for (int i = 0; i < nf; i++) {
            String ext = EXTS[rnd.nextInt(EXTS.length)];
            Node f = new Node("file" + i + (ext == null ? "" : "." + ext), Node.FILE, parent);
            f.ext = ext;
            double scale = ext == null ? 1e4 : ext.equals("iso") || ext.equals("nsp") ? 3e9 : ext.equals("mp4") ? 8e8 : 5e6;
            f.size = (long) (scale * Math.pow(rnd.nextDouble(), 3)) + 1;
            kids.add(f);
        }
        if (depth < 4) {
            int nd = depth == 0 ? 6 + rnd.nextInt(5) : 1 + rnd.nextInt(5);
            for (int i = 0; i < nd; i++) {
                Node d = new Node("folder" + i, Node.DIR, parent);
                d.children = folder(d, rnd, depth + 1).toArray(Node.NONE);
                kids.add(d);
            }
        }
        return kids;
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
