package com.thordirstat;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;

import java.io.File;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The scan everything shows: the tree, the file types, what's selected and which folder the
 * treemap is zoomed into. The list screen and the treemap screen (which can sit on different
 * displays) both watch it, so a tap on either one shows on the other.
 *
 * Only the main thread changes the tree once a scan has finished.
 */
final class Session {
    interface Listener {
        void onSessionChanged(int what);
    }

    static final int SCAN = 1, TREE = 2, SELECTION = 4, ZOOM = 8, EXT = 16, OPTIONS = 32, CLOSE = 64, UNDO = 128;

    /** How long a quick delete from the treemap can still be undone. */
    static final long UNDO_MS = 5000;

    private static final Session INSTANCE = new Session();

    static Session get() { return INSTANCE; }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private Node root;
    private FileTypes types = new FileTypes(null);
    private Node zoom;
    private Node selected;
    private FileTypes.Type selectedType;
    private final Map<Node, Volumes.Volume> volumes = new IdentityHashMap<>();

    private volatile Scanner scanner;
    private long scanStarted;
    private String scanLabel = "";
    /** Rescans and deletes in progress, so the screens can say "working". */
    private int busy;

    private Session() {}

    void addListener(Listener l) { listeners.add(l); }

    void removeListener(Listener l) { listeners.remove(l); }

    private void changed(final int what) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            for (Listener l : listeners) l.onSessionChanged(what);
        } else {
            main.post(new Runnable() {
                @Override public void run() { changed(what); }
            });
        }
    }

    Node root() { return root; }

    FileTypes types() { return types; }

    /** The folder the treemap shows (the root unless zoomed in). */
    Node zoom() { return zoom != null ? zoom : root; }

    Node selected() { return selected; }

    FileTypes.Type selectedType() { return selectedType; }

    boolean isBusy() { return busy > 0; }

    // ---- Scanning ---------------------------------------------------------------------------

    Scanner scanner() { return scanner; }

    boolean isScanning() { return scanner != null; }

    long scanStarted() { return scanStarted; }

    String scanLabel() { return scanLabel; }

    /** Scans the given volumes; with more than one, they go under an "All storage" root. */
    void scan(final Context context, final List<Volumes.Volume> vols) {
        if (vols.isEmpty()) return;
        commitPending();
        cancelScan();
        final Prefs prefs = new Prefs(context);
        final Scanner s = new Scanner(prefs.sizeOnDisk(), true);
        scanner = s;
        scanStarted = System.currentTimeMillis();
        scanLabel = vols.size() == 1 ? vols.get(0).label : "All storage";
        changed(SCAN);
        new Thread(new Runnable() {
            @Override public void run() {
                final Map<Node, Volumes.Volume> found = new IdentityHashMap<>();
                Node top = null;
                if (vols.size() > 1) {
                    top = new Node("", Node.DIR, null);
                    top.label = "All storage";
                }
                List<Node> roots = new ArrayList<>();
                for (Volumes.Volume v : vols) {
                    Node r = s.isCancelled() ? null : scanOne(s, v, top);
                    if (r == null) break;
                    found.put(r, v);
                    roots.add(r);
                }
                if (s.isCancelled() || roots.size() != vols.size()) {
                    finishScan(s, null, null, prefs);
                    return;
                }
                if (top != null) {
                    top.children = roots.toArray(Node.NONE);
                    top.aggregate();
                } else {
                    top = roots.get(0);
                }
                finishScan(s, top, found, prefs);
            }
        }, "ThorDirStat scan").start();
    }

    /** Scanner runs one tree at a time, so each volume gets its own pass with shared counters. */
    private static Node scanOne(Scanner shared, Volumes.Volume v, Node parent) {
        Node r = shared.scanVolume(v.path, parent);
        if (r != null) r.label = v.label;
        return r;
    }

    private void finishScan(final Scanner s, final Node newRoot, final Map<Node, Volumes.Volume> found,
                            final Prefs prefs) {
        final FileTypes t = newRoot != null ? new FileTypes(newRoot) : null;
        main.post(new Runnable() {
            @Override public void run() {
                if (scanner != s) return; // replaced by a newer scan
                scanner = null;
                if (newRoot != null) {
                    root = newRoot;
                    volumes.clear();
                    volumes.putAll(found);
                    types = t;
                    zoom = null;
                    selected = root;
                    selectedType = null;
                    applyPseudoItems(prefs);
                }
                changed(SCAN | TREE | SELECTION | ZOOM | EXT);
            }
        });
    }

    void cancelScan() {
        Scanner s = scanner;
        if (s != null) {
            s.cancel();
            scanner = null;
            changed(SCAN);
        }
    }

    // ---- Free space and unknown -------------------------------------------------------------

    /** Adds or removes each volume's <Free space> and <Unknown> items to match Settings. */
    private void applyPseudoItems(Prefs prefs) {
        boolean showFree = prefs.showFree();
        boolean showUnknown = prefs.showUnknown();
        for (Map.Entry<Node, Volumes.Volume> e : volumes.entrySet()) {
            Node r = e.getKey();
            Volumes.Volume v = e.getValue();
            List<Node> real = new ArrayList<>();
            long scanned = 0;
            // Space a quick delete is about to free already counts as free, not as <Unknown>.
            long pendingBytes = pending != null && pending.volumeRoot == r ? pending.node.size : 0;
            long used = Math.max(0, v.used() - pendingBytes);
            for (Node c : r.children) {
                if (c.isReal()) {
                    real.add(c);
                    scanned += c.size;
                }
            }
            if (showUnknown && used > scanned) {
                Node u = new Node("", Node.UNKNOWN, r);
                u.size = used - scanned;
                real.add(u);
            }
            if (showFree && v.free + pendingBytes > 0) {
                Node f = new Node("", Node.FREE, r);
                f.size = v.free + pendingBytes;
                real.add(f);
            }
            r.children = real.toArray(Node.NONE);
            r.reaggregateUp();
        }
        if (selected != null && !selected.isReal() && selected.parent == null) selected = root;
    }

    /** Settings changed what's shown. */
    void settingsChanged(Context context) {
        applyPseudoItems(new Prefs(context));
        changed(TREE | OPTIONS);
    }

    // ---- Selection and zoom -------------------------------------------------------------------

    void select(Node n) {
        if (n == selected) return;
        selected = n;
        changed(SELECTION);
    }

    /** Highlights every file of a type in the treemap (tap again to clear). */
    void selectType(FileTypes.Type t) {
        selectedType = t == selectedType ? null : t;
        changed(EXT);
    }

    void zoomTo(Node dir) {
        if (dir == null || dir.kind != Node.DIR) return;
        zoom = dir == root ? null : dir;
        changed(ZOOM);
    }

    /** One folder up; false if already showing everything. */
    boolean zoomOut() {
        if (zoom == null || zoom.parent == null) {
            if (zoom == null) return false;
            zoom = null;
        } else {
            zoom = zoom.parent == root ? null : zoom.parent;
        }
        changed(ZOOM);
        return true;
    }

    void closeAll() {
        commitPending();
        changed(CLOSE);
    }

    // ---- Rescan and delete ----------------------------------------------------------------------

    interface Done {
        void done(boolean ok, String message);
    }

    /** Scans one folder again and swaps it into the tree. */
    void rescan(final Context context, final Node dir, final Done done) {
        if (dir == null || dir.kind != Node.DIR) return;
        if (dir == root || dir.parent == null || volumes.containsKey(dir)) {
            List<Volumes.Volume> vols = new ArrayList<>();
            List<Volumes.Volume> now = Volumes.list(context);
            for (Volumes.Volume old : volumes.values()) {
                for (Volumes.Volume v : now) if (v.path.equals(old.path)) vols.add(v);
            }
            if (dir != root && volumes.containsKey(dir)) {
                // Just that volume: rescan it, then put it back in place.
                rescanVolume(context, dir, done);
                return;
            }
            scan(context, vols.isEmpty() ? now : vols);
            return;
        }
        final String path = dir.path();
        final Prefs prefs = new Prefs(context);
        busy++;
        changed(TREE);
        new Thread(new Runnable() {
            @Override public void run() {
                final Node fresh = new Scanner(prefs.sizeOnDisk(), true).scan(path, dir.name, dir.parent);
                main.post(new Runnable() {
                    @Override public void run() {
                        busy--;
                        Node parent = dir.parent;
                        if (fresh == null || parent == null) {
                            changed(TREE);
                            done.done(false, "Couldn't rescan " + dir.name);
                            return;
                        }
                        fresh.label = dir.label;
                        parent.replaceChild(dir, fresh);
                        swapped(dir, fresh, context);
                        done.done(true, "Rescanned " + dir.name);
                    }
                });
            }
        }, "ThorDirStat rescan").start();
    }

    private void rescanVolume(final Context context, final Node volRoot, final Done done) {
        final Volumes.Volume old = volumes.get(volRoot);
        final Prefs prefs = new Prefs(context);
        busy++;
        changed(TREE);
        new Thread(new Runnable() {
            @Override public void run() {
                Volumes.Volume v = old;
                for (Volumes.Volume now : Volumes.list(context)) if (now.path.equals(old.path)) v = now;
                final Volumes.Volume vol = v;
                final Node fresh = new Scanner(prefs.sizeOnDisk(), true).scanVolume(vol.path, volRoot.parent);
                main.post(new Runnable() {
                    @Override public void run() {
                        busy--;
                        if (fresh == null || volRoot.parent == null) {
                            changed(TREE);
                            done.done(false, "Couldn't rescan " + vol.label);
                            return;
                        }
                        fresh.label = vol.label;
                        volumes.remove(volRoot);
                        volumes.put(fresh, vol);
                        volRoot.parent.replaceChild(volRoot, fresh);
                        swapped(volRoot, fresh, context);
                        done.done(true, "Rescanned " + vol.label);
                    }
                });
            }
        }, "ThorDirStat rescan").start();
    }

    /** After old was replaced by fresh (or removed, fresh == its parent): fix selection and zoom. */
    private void swapped(Node old, Node fresh, Context context) {
        if (selected != null && old.isAncestorOf(selected)) selected = fresh;
        if (zoom != null && old.isAncestorOf(zoom)) zoom = fresh.kind == Node.DIR ? fresh : fresh.parent;
        refreshFreeSpace();
        applyPseudoItems(new Prefs(context));
        types = new FileTypes(root);
        if (selectedType != null) selectedType = types.of(selectedType.ext);
        changed(TREE | SELECTION | ZOOM | EXT);
    }

    private void refreshFreeSpace() {
        for (Map.Entry<Node, Volumes.Volume> e : volumes.entrySet()) {
            Volumes.Volume v = e.getValue();
            try {
                StatFs fs = new StatFs(v.path);
                e.setValue(new Volumes.Volume(v.path, v.label, v.removable, fs.getTotalBytes(), fs.getAvailableBytes()));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    /** A file or folder (and everything in it), not a volume or free space / unknown. */
    boolean canDelete(Node n) {
        return n != null && n.isReal() && n.parent != null && n.label == null && !volumes.containsKey(n);
    }

    /** Deletes a file or a folder and everything in it, for good. */
    void delete(final Context context, final Node n, final Done done) {
        if (!canDelete(n)) return;
        final String path = n.path();
        final boolean shell = n.viaShell;
        busy++;
        changed(TREE);
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean deleted = deleteNow(path, shell);
                main.post(new Runnable() {
                    @Override public void run() {
                        busy--;
                        Node parent = n.parent;
                        if (!deleted || parent == null) {
                            changed(TREE);
                            done.done(false, "Couldn't delete " + n.name
                                    + (ShizukuClient.hasPermission() ? "" : " (allowing Shizuku may help)"));
                            return;
                        }
                        parent.removeChild(n);
                        swapped(n, parent, context);
                        done.done(true, "Deleted " + n.name);
                    }
                });
            }
        }, "ThorDirStat delete").start();
    }

    /** Deletes from storage. Call off the main thread. */
    private static boolean deleteNow(String path, boolean shell) {
        boolean ok = !shell && deleteTree(new File(path));
        if (!ok && ShizukuClient.hasPermission()) {
            ok = ShizukuClient.runShell("rm -rf -- " + Scanner.quote(path));
        }
        // Apps can't see into the folders Shizuku handles, so trust rm's answer there.
        return ok && (shell || !new File(path).exists());
    }

    // ---- Quick delete with undo -----------------------------------------------------------------

    /** A quick delete waiting out its undo time: gone from the tree, still on storage. */
    private static final class Pending {
        final Node node, parent, volumeRoot;
        final String path;
        final boolean shell;
        final Context context;
        final Runnable commit;

        Pending(Node node, Node parent, Node volumeRoot, Context context, Runnable commit) {
            this.node = node;
            this.parent = parent;
            this.volumeRoot = volumeRoot;
            this.path = node.path();
            this.shell = node.viaShell;
            this.context = context.getApplicationContext();
            this.commit = commit;
        }
    }

    private Pending pending;

    /** The item a quick delete is about to remove, or null. */
    Node pendingDelete() { return pending != null ? pending.node : null; }

    /**
     * Removes an item from the tree straight away and deletes it from storage once the undo
     * time is up (or as soon as another quick delete starts).
     */
    void quickDelete(Context context, Node n) {
        if (!canDelete(n)) return;
        commitPending();
        Node volumeRoot = n;
        while (volumeRoot != null && !volumes.containsKey(volumeRoot)) volumeRoot = volumeRoot.parent;
        Node parent = n.parent;
        final Pending[] holder = new Pending[1];
        Runnable commit = new Runnable() {
            @Override public void run() {
                if (pending == holder[0]) commitPending();
            }
        };
        holder[0] = new Pending(n, parent, volumeRoot, context, commit);
        pending = holder[0];
        parent.removeChild(n);
        swapped(n, parent, context);
        main.postDelayed(commit, UNDO_MS);
        changed(UNDO);
    }

    /** Puts the item back; nothing was deleted. */
    void undoDelete() {
        final Pending p = pending;
        if (p == null) return;
        pending = null;
        main.removeCallbacks(p.commit);
        if (p.parent.parent != null || p.parent == root) {
            p.parent.addChild(p.node);
            applyPseudoItems(new Prefs(p.context));
            types = new FileTypes(root);
            if (selectedType != null) selectedType = types.of(selectedType.ext);
            selected = p.node;
        }
        changed(TREE | SELECTION | EXT | UNDO);
    }

    /** Deletes the waiting item from storage now. */
    private void commitPending() {
        final Pending p = pending;
        if (p == null) return;
        pending = null;
        main.removeCallbacks(p.commit);
        busy++;
        changed(UNDO);
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean ok = deleteNow(p.path, p.shell);
                main.post(new Runnable() {
                    @Override public void run() {
                        busy--;
                        if (!ok && (p.parent.parent != null || p.parent == root)) {
                            p.parent.addChild(p.node); // still there: show it again
                            types = new FileTypes(root);
                        }
                        refreshFreeSpace();
                        applyPseudoItems(new Prefs(p.context));
                        changed(TREE | EXT);
                        android.widget.Toast.makeText(p.context, ok ? "Deleted " + p.node.name
                                : "Couldn't delete " + p.node.name
                                + (ShizukuClient.hasPermission() ? "" : " (allowing Shizuku may help)"),
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "ThorDirStat delete").start();
    }

    private static boolean deleteTree(File f) {
        // Never follow a link into the folder it points at; just remove the link.
        if (f.isDirectory() && !isSymlink(f)) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        return f.delete() || !f.exists();
    }

    private static boolean isSymlink(File f) {
        try {
            return android.system.OsConstants.S_ISLNK(android.system.Os.lstat(f.getPath()).st_mode);
        } catch (Exception e) {
            return false;
        }
    }
}
