package com.thordirstat;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import moe.shizuku.server.IRemoteProcess;

/**
 * Walks folders on a few threads at once, building a {@link Node} tree. Symbolic links are
 * counted as small files and never followed. The folders Android hides from apps
 * (Android/data and Android/obb) are measured through Shizuku when it's allowed.
 */
final class Scanner {
    private static final int THREADS = 4;

    final AtomicLong files = new AtomicLong();
    final AtomicLong bytes = new AtomicLong();
    volatile String current = "";
    private volatile boolean cancelled;

    private final boolean sizeOnDisk;
    private final boolean useShizuku;
    private ExecutorService pool;
    private final AtomicInteger pending = new AtomicInteger();
    private final Object doneLock = new Object();

    Scanner(boolean sizeOnDisk, boolean useShizuku) {
        this.sizeOnDisk = sizeOnDisk;
        this.useShizuku = useShizuku;
    }

    void cancel() {
        cancelled = true;
        synchronized (doneLock) {
            doneLock.notifyAll();
        }
    }

    boolean isCancelled() { return cancelled; }

    /** Scans a whole volume; its root node is named by its full path. */
    Node scanVolume(String path, Node parent) {
        return scan(path, path, parent);
    }

    /**
     * Scans one folder and everything below it. Blocks; call off the main thread. Returns null
     * if cancelled. Can be called again for another folder; the counters keep adding up.
     */
    Node scan(String path, String name, Node parent) {
        pool = Executors.newFixedThreadPool(THREADS);
        pending.set(0);
        Node root = new Node(name, Node.DIR, parent);
        try {
            root.modified = Os.lstat(path).st_mtime * 1000L;
        } catch (ErrnoException ignored) {
        }
        submit(root, path);
        synchronized (doneLock) {
            while (pending.get() > 0 && !cancelled) {
                try {
                    doneLock.wait(250);
                } catch (InterruptedException e) {
                    cancelled = true;
                }
            }
        }
        pool.shutdownNow();
        if (cancelled) return null;
        root.aggregate();
        return root;
    }

    private void submit(final Node dir, final String path) {
        pending.incrementAndGet();
        pool.execute(new Runnable() {
            @Override public void run() {
                try {
                    if (!cancelled) list(dir, path);
                } finally {
                    if (pending.decrementAndGet() == 0) {
                        synchronized (doneLock) {
                            doneLock.notifyAll();
                        }
                    }
                }
            }
        });
    }

    private void list(Node dir, String path) {
        current = path;
        if (isHidden(path)) {
            if (useShizuku && ShizukuClient.hasPermission() && listWithShizuku(dir, path)) return;
            dir.restricted = true; // what we can see of it is still listed below
        }
        String[] names = new File(path).list();
        if (names == null) return;
        ArrayList<Node> kids = new ArrayList<>(names.length);
        for (String n : names) {
            if (cancelled) return;
            String p = path + "/" + n;
            StructStat st;
            try {
                st = Os.lstat(p);
            } catch (ErrnoException e) {
                continue;
            }
            boolean isDir = OsConstants.S_ISDIR(st.st_mode);
            Node c = new Node(n, isDir ? Node.DIR : Node.FILE, dir);
            c.modified = st.st_mtime * 1000L;
            if (isDir) {
                submit(c, p);
            } else {
                c.size = sizeOnDisk ? st.st_blocks * 512L : st.st_size;
                c.ext = FileTypes.extensionOf(n);
                if (c.ext != null) c.ext = c.ext.intern();
                files.incrementAndGet();
                bytes.addAndGet(c.size);
            }
            kids.add(c);
        }
        dir.children = kids.toArray(Node.NONE);
    }

    /** Android/data and Android/obb on any volume: apps can't look inside since Android 11. */
    static boolean isHidden(String path) {
        return path.endsWith("/Android/data") || path.endsWith("/Android/obb");
    }

    /**
     * Lists a hidden folder as the shell user, with one find + stat for the whole subtree.
     * Each line: size, blocks, block size, mtime, raw mode (hex), ./relative/path.
     */
    private boolean listWithShizuku(Node dir, String path) {
        Map<String, Node> dirs = new HashMap<>();
        Map<Node, List<Node>> kids = new HashMap<>();
        dirs.put(".", dir);
        kids.put(dir, new ArrayList<Node>());
        try {
            IRemoteProcess p = ShizukuClient.newProcess(new String[] {"sh", "-c",
                    "cd " + quote(path) + " && find . -mindepth 1 -exec stat -c '%s %b %B %Y %f %n' {} +"});
            BufferedReader in = new BufferedReader(new InputStreamReader(
                    new ParcelFileDescriptor.AutoCloseInputStream(p.getInputStream()), "UTF-8"));
            String line;
            int lines = 0;
            while ((line = in.readLine()) != null) {
                if (cancelled) break;
                String[] f = line.split(" ", 6);
                if (f.length < 6 || !f[5].startsWith("./")) continue;
                lines++;
                String rel = f[5];
                int slash = rel.lastIndexOf('/');
                Node parent = dirs.get(rel.substring(0, slash));
                if (parent == null) continue;
                int mode = (int) Long.parseLong(f[4], 16);
                boolean isDir = (mode & 0xf000) == 0x4000;
                Node c = new Node(rel.substring(slash + 1), isDir ? Node.DIR : Node.FILE, parent);
                c.viaShell = true;
                c.modified = Long.parseLong(f[3]) * 1000L;
                if (isDir) {
                    dirs.put(rel, c);
                    kids.put(c, new ArrayList<Node>());
                    current = path + rel.substring(1);
                } else {
                    c.size = sizeOnDisk ? Long.parseLong(f[1]) * Long.parseLong(f[2]) : Long.parseLong(f[0]);
                    c.ext = FileTypes.extensionOf(c.name);
                    if (c.ext != null) c.ext = c.ext.intern();
                    files.incrementAndGet();
                    bytes.addAndGet(c.size);
                }
                kids.get(parent).add(c);
            }
            in.close();
            int code = p.waitFor();
            p.destroy();
            if (lines == 0 && code != 0) return false;
        } catch (Exception e) {
            return false;
        }
        for (Map.Entry<Node, List<Node>> e : kids.entrySet()) {
            e.getKey().children = e.getValue().toArray(Node.NONE);
        }
        dir.viaShell = true;
        return true;
    }

    /** Wraps a path in single quotes for sh. */
    static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
