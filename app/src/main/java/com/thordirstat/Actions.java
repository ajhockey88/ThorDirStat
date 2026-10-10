package com.thordirstat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * WinDirStat's right-click menu for a file or folder: zoom, open, share, properties, copy
 * path, rescan and delete. Shown on press and hold in either list or the treemap.
 */
final class Actions {
    private Actions() {}

    static void show(final Activity activity, final Node n) {
        if (n == null) return;
        final Session s = Session.get();
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        if (n.kind == Node.DIR && n != s.zoom()) {
            add(labels, actions, "🔍 Zoom treemap into this folder", new Runnable() {
                @Override public void run() {
                    Haptics.play(activity, Haptics.ZOOM);
                    s.zoomTo(n);
                }
            });
        }
        if (s.zoom() != s.root()) {
            add(labels, actions, "↩️ Zoom out", new Runnable() {
                @Override public void run() {
                    Haptics.play(activity, Haptics.ZOOM);
                    s.zoomOut();
                }
            });
        }
        if (n.kind == Node.FILE && !n.viaShell) {
            add(labels, actions, "📂 Open", new Runnable() {
                @Override public void run() { launch(activity, n, false); }
            });
            add(labels, actions, "📤 Share", new Runnable() {
                @Override public void run() { launch(activity, n, true); }
            });
        }
        add(labels, actions, "ℹ️ Properties", new Runnable() {
            @Override public void run() { properties(activity, n); }
        });
        if (n.isReal() && !n.path().isEmpty()) {
            add(labels, actions, "📋 Copy path", new Runnable() {
                @Override public void run() {
                    ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("Path", n.path()));
                    Toast.makeText(activity, "Copied " + n.path(), Toast.LENGTH_SHORT).show();
                }
            });
        }
        if (n.kind == Node.APP) {
            add(labels, actions, "⚙️ App info (clear cache, uninstall)", new Runnable() {
                @Override public void run() {
                    try {
                        activity.startActivity(new android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:" + n.name)));
                    } catch (ActivityNotFoundException e) {
                        Toast.makeText(activity, "Couldn't open app info", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }
        if (n.kind == Node.DIR && n.isReal()) {
            add(labels, actions, "🔄 Rescan this folder", new Runnable() {
                @Override public void run() {
                    s.rescan(activity, n, toast(activity));
                }
            });
        }
        if (n.isReal() && n.parent != null && n.label == null) {
            add(labels, actions, "🗑️ Delete…", new Runnable() {
                @Override public void run() { confirmDelete(activity, n); }
            });
        }
        new AlertDialog.Builder(activity)
                .setTitle(n.displayName() + "  ·  " + Format.size(n.size))
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        actions.get(which).run();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void add(List<String> labels, List<Runnable> actions, String label, Runnable r) {
        labels.add(label);
        actions.add(r);
    }

    private static void launch(Activity activity, Node n, boolean share) {
        try {
            activity.startActivity(FilesProvider.intentFor(activity, n.path(), share));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, "No app can " + (share ? "share" : "open") + " this file", Toast.LENGTH_SHORT).show();
        }
    }

    static Session.Done toast(final Activity activity) {
        return new Session.Done() {
            @Override public void done(boolean ok, String message) {
                Toast.makeText(activity.getApplicationContext(), message, Toast.LENGTH_SHORT).show();
            }
        };
    }

    private static void properties(Activity activity, Node n) {
        StringBuilder sb = new StringBuilder();
        String path = n.path();
        sb.append(path.isEmpty() ? n.displayName() : n.isReal() ? path : n.displayName() + " on " + path).append("\n\n");
        sb.append("Size: ").append(Format.size(n.size)).append(" (").append(Format.count(n.size)).append(" bytes)\n");
        if (n.parent != null) sb.append("Share of its folder: ").append(Format.percent(n.size, n.parent.size)).append('\n');
        Node root = Session.get().root();
        if (root != null && root != n) sb.append("Share of everything scanned: ").append(Format.percent(n.size, root.size)).append('\n');
        if (n.kind == Node.DIR && n.pseudo) {
            sb.append("Apps: ").append(Format.count(n.children.length)).append('\n');
        } else if (n.kind == Node.DIR) {
            sb.append("Files: ").append(Format.count(n.files)).append('\n');
            sb.append("Folders: ").append(Format.count(n.dirs)).append('\n');
        } else if (n.kind == Node.FILE) {
            sb.append("Type: ").append(FileTypes.describe(n.ext)).append('\n');
        } else if (n.kind == Node.APP && n.info != null) {
            sb.append(n.info).append('\n');
        }
        if (n.isReal()) sb.append("Last change: ").append(Format.date(n.modified)).append('\n');
        if (n.restricted) {
            sb.append("\nAndroid hides this folder's contents from apps. Allow ThorDirStat in Shizuku "
                    + "and scan again to measure it.\n");
        }
        if (n.kind == Node.UNKNOWN) {
            sb.append(Session.get().hasApps()
                    ? "\nSpace in use that no folder or app here accounts for: mostly Android itself.\n"
                    : "\nSpace in use that no folder here accounts for: installed apps, their private "
                            + "data, and Android itself. Allow usage access in Drives to see each app's size.\n");
        }
        new AlertDialog.Builder(activity)
                .setTitle(n.displayName())
                .setMessage(sb.toString().trim())
                .setPositiveButton("OK", null)
                .show();
    }

    private static void confirmDelete(final Activity activity, final Node n) {
        String what = n.kind == Node.DIR
                ? "the folder " + n.name + " and everything in it (" + Format.count(n.items()) + " items, "
                        + Format.size(n.size) + ")"
                : n.name + " (" + Format.size(n.size) + ")";
        new AlertDialog.Builder(activity)
                .setTitle("🗑️ Delete for good?")
                .setMessage("This deletes " + what + ".\n\n" + n.path()
                        + "\n\nAndroid has no recycle bin, so this can't be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        Haptics.play(activity, Haptics.DELETE);
                        Session.get().delete(activity, n, toast(activity));
                    }
                })
                .show();
    }
}
