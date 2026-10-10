package com.thordirstat;

import java.util.Arrays;
import java.util.Comparator;

/**
 * One file or folder in a scan. Folders add up their children's sizes and counts once the
 * scan is done, and keep their children sorted biggest first, like WinDirStat's tree.
 *
 * Kept free of Android classes so the layout and treemap can be tested on a desktop JVM.
 */
final class Node {
    static final byte FILE = 0, DIR = 1, FREE = 2, UNKNOWN = 3;
    static final Node[] NONE = new Node[0];

    /** For the root of a volume, its full path; otherwise the file or folder name. */
    final String name;
    final byte kind;
    Node parent;
    /** Folders only, sorted biggest first once the scan is done. */
    Node[] children;
    /** Bytes (the file's size, or the size on disk if Settings asks for it). */
    long size;
    /** Files and folders below this one (a file counts itself as one file). */
    int files, dirs;
    /** Last change, in ms since 1970; for folders, the newest change anywhere below. */
    long modified;
    /** Lower-case extension without the dot, interned, or null. Files only. */
    String ext;
    /** Folders: a display name to show instead of name (volume roots). */
    String label;
    /** Measured through Shizuku: deleting it needs Shizuku too. */
    boolean viaShell;
    /** A folder Android hides from apps (Android/data, Android/obb) that couldn't be measured. */
    boolean restricted;

    Node(String name, byte kind, Node parent) {
        this.name = name;
        this.kind = kind;
        this.parent = parent;
        if (kind == DIR) children = NONE;
    }

    boolean isDir() { return kind == DIR; }

    /** A real file or folder (not free space or the unknown remainder). */
    boolean isReal() { return kind == FILE || kind == DIR; }

    String displayName() {
        if (label != null) return label;
        switch (kind) {
            case FREE: return "<Free space>";
            case UNKNOWN: return "<Unknown>";
            default: return name;
        }
    }

    /** Full path on the device; pseudo items return their volume's path. */
    String path() {
        if (!isReal() && parent != null) return parent.path();
        StringBuilder sb = new StringBuilder();
        build(sb, this);
        return sb.toString();
    }

    private static void build(StringBuilder sb, Node n) {
        // Volume roots carry their full path; above them there's at most the "all storage" root.
        if (n.parent == null || (n.parent.parent == null && n.parent.name.isEmpty())) {
            sb.append(n.name);
            return;
        }
        build(sb, n.parent);
        sb.append('/').append(n.name);
    }

    int depth() {
        int d = 0;
        for (Node p = parent; p != null; p = p.parent) d++;
        return d;
    }

    boolean isAncestorOf(Node n) {
        for (Node p = n; p != null; p = p.parent) if (p == this) return true;
        return false;
    }

    /** Items below this one, as WinDirStat counts them: files plus folders. */
    int items() { return files + dirs; }

    static final Comparator<Node> BIGGEST_FIRST = new Comparator<Node>() {
        @Override public int compare(Node a, Node b) {
            if (a.size != b.size) return a.size > b.size ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        }
    };

    /** Adds up sizes, counts and dates below this folder and sorts every child list. */
    void aggregate() {
        if (kind != DIR) return;
        long s = 0, m = modified;
        int f = 0, d = 0;
        for (Node c : children) {
            c.aggregate();
            s += c.size;
            if (c.kind == DIR) {
                d += 1 + c.dirs;
                f += c.files;
            } else if (c.kind == FILE) {
                f += 1;
            }
            if (c.isReal() && c.modified > m) m = c.modified;
        }
        size = s;
        files = f;
        dirs = d;
        modified = m;
        Arrays.sort(children, BIGGEST_FIRST);
    }

    /** Re-adds this folder's direct children (after one changed) and does the same upwards. */
    void reaggregateUp() {
        for (Node n = this; n != null; n = n.parent) {
            long s = 0;
            int f = 0, d = 0;
            for (Node c : n.children) {
                s += c.size;
                if (c.kind == DIR) {
                    d += 1 + c.dirs;
                    f += c.files;
                } else if (c.kind == FILE) {
                    f += 1;
                }
            }
            n.size = s;
            n.files = f;
            n.dirs = d;
            Arrays.sort(n.children, BIGGEST_FIRST);
        }
    }

    /** Removes a child (after it was deleted) and updates every folder above. */
    void removeChild(Node child) {
        int i = indexOf(child);
        if (i < 0) return;
        Node[] next = new Node[children.length - 1];
        System.arraycopy(children, 0, next, 0, i);
        System.arraycopy(children, i + 1, next, i, children.length - i - 1);
        children = next;
        child.parent = null;
        reaggregateUp();
    }

    /** Puts a child back (a delete was undone) and updates every folder above. */
    void addChild(Node child) {
        Node[] next = Arrays.copyOf(children, children.length + 1);
        next[children.length] = child;
        children = next;
        child.parent = this;
        reaggregateUp();
    }

    /** Swaps a child for a freshly scanned copy of it and updates every folder above. */
    void replaceChild(Node old, Node fresh) {
        int i = indexOf(old);
        if (i < 0) return;
        fresh.parent = this;
        children[i] = fresh;
        old.parent = null;
        reaggregateUp();
    }

    private int indexOf(Node child) {
        for (int i = 0; i < children.length; i++) if (children[i] == child) return i;
        return -1;
    }

    /** The child by name, or null. */
    Node child(String childName) {
        if (children == null) return null;
        for (Node c : children) if (c.name.equals(childName)) return c;
        return null;
    }
}
