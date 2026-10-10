package com.thordirstat;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The main screen: a bar of buttons, then WinDirStat's folder tree and file type list. With
 * both screens in use it sits on the bottom screen and the treemap fills the top screen
 * ({@link TreemapActivity}); on one screen the treemap sits beside the lists.
 *
 * Also hosts the drive picker (with the permissions it needs) and the scan progress.
 */
public class MainActivity extends Activity
        implements Session.Listener, ShizukuClient.Listener, FolderList.Listener {
    static final String EXTRA_DUAL = "dual";

    private static MainActivity current;

    private float d;
    private boolean dual;
    private FolderList folders;
    private TypeList typeList;
    private Button foldersTab, typesTab;
    private TextView selectionLine;
    private View results;
    private ScrollView drivesPanel;
    private LinearLayout drivesList;
    private View scanPanel;
    private TextView scanText, scanPath;
    private PacmanView pacman;
    private Button zoomOutButton;
    private boolean showingTypes;
    private boolean drivesWanted = true;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** The main screen if it's open, so the treemap screen can pass it gamepad buttons. */
    static MainActivity current() { return current; }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = this;
        d = getResources().getDisplayMetrics().density;
        dual = getIntent().getBooleanExtra(EXTRA_DUAL, false);
        getWindow().getDecorView().setBackgroundColor(Color.BLACK);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding((int) (6 * d), (int) (6 * d), (int) (6 * d), 0);
        bar.addView(barButton("💽 Drives", new View.OnClickListener() {
            @Override public void onClick(View v) { showDrives(true); }
        }), weight());
        bar.addView(barButton("🔍 Zoom in", new View.OnClickListener() {
            @Override public void onClick(View v) { zoomIn(); }
        }), weight());
        zoomOutButton = barButton("↩️ Zoom out", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (Session.get().zoomOut()) Haptics.play(MainActivity.this, Haptics.ZOOM);
            }
        });
        bar.addView(zoomOutButton, weight());
        bar.addView(barButton("☰ Actions", new View.OnClickListener() {
            @Override public void onClick(View v) { Actions.show(MainActivity.this, Session.get().selected()); }
        }), weight());
        bar.addView(barButton("⚙️ Settings", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        }), weight());
        bar.addView(barButton("✖️ Close", new View.OnClickListener() {
            @Override public void onClick(View v) {
                Session.get().cancelScan();
                Session.get().closeAll();
                finishAndRemoveTask();
            }
        }), weight());
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (46 * d)));

        FrameLayout content = new FrameLayout(this);
        results = buildResults();
        content.addView(results, match());
        scanPanel = buildScanPanel();
        content.addView(scanPanel, match());
        drivesPanel = buildDrivesPanel();
        content.addView(drivesPanel, match());
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        Session.get().addListener(this);
        ShizukuClient.addListener(this);
        drivesWanted = Session.get().root() == null;
        refreshAll();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        boolean wantDual = intent.getBooleanExtra(EXTRA_DUAL, dual);
        setIntent(intent);
        // Reopened after "Use both screens" changed: lay the screen out again.
        if (wantDual != dual) recreate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        current = this;
        refreshDrives();
        updatePanels();
    }

    @Override
    protected void onDestroy() {
        Session.get().removeListener(this);
        ShizukuClient.removeListener(this);
        handler.removeCallbacksAndMessages(null);
        if (current == this) current = null;
        super.onDestroy();
    }

    // ---- Layout ---------------------------------------------------------------------------------

    private View buildResults() {
        LinearLayout lists = new LinearLayout(this);
        lists.setOrientation(LinearLayout.VERTICAL);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding((int) (6 * d), (int) (6 * d), (int) (6 * d), 0);
        foldersTab = barButton("📁 Folders", new View.OnClickListener() {
            @Override public void onClick(View v) { showTypes(false); }
        });
        typesTab = barButton("🎨 File types", new View.OnClickListener() {
            @Override public void onClick(View v) { showTypes(true); }
        });
        tabs.addView(foldersTab, weight());
        tabs.addView(typesTab, weight());
        lists.addView(tabs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (40 * d)));

        selectionLine = new TextView(this);
        selectionLine.setTextSize(13);
        selectionLine.setTextColor(Color.argb(190, 255, 255, 255));
        selectionLine.setSingleLine(true);
        selectionLine.setEllipsize(android.text.TextUtils.TruncateAt.START);
        selectionLine.setPadding((int) (10 * d), (int) (5 * d), (int) (10 * d), (int) (5 * d));
        lists.addView(selectionLine);

        FrameLayout listFrame = new FrameLayout(this);
        folders = new FolderList(this);
        folders.setListener(this);
        typeList = new TypeList(this);
        typeList.setVisibility(View.GONE);
        listFrame.addView(folders, match());
        listFrame.addView(typeList, match());
        lists.addView(listFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        if (dual) return lists;
        // One screen: lists on the left, treemap on the right.
        LinearLayout both = new LinearLayout(this);
        both.setOrientation(LinearLayout.HORIZONTAL);
        both.addView(lists, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        both.addView(new TreemapPane(this), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.25f));
        return both;
    }

    private View buildScanPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setBackgroundColor(Color.BLACK);
        panel.setClickable(true);
        int pad = (int) (24 * d);
        panel.setPadding(pad, pad, pad, pad);
        pacman = new PacmanView(this);
        panel.addView(pacman, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (44 * d)));
        scanText = panelText("", 17, 255);
        scanText.setGravity(Gravity.CENTER);
        scanText.setPadding(0, (int) (14 * d), 0, (int) (6 * d));
        panel.addView(scanText);
        scanPath = panelText("", 13, 150);
        scanPath.setGravity(Gravity.CENTER);
        scanPath.setSingleLine(true);
        scanPath.setEllipsize(android.text.TextUtils.TruncateAt.START);
        panel.addView(scanPath, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button cancel = panelButton("Cancel scan", new View.OnClickListener() {
            @Override public void onClick(View v) {
                Session.get().cancelScan();
                drivesWanted = Session.get().root() == null;
                updatePanels();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (16 * d);
        panel.addView(cancel, lp);
        return panel;
    }

    private ScrollView buildDrivesPanel() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.setClickable(true);
        drivesList = new LinearLayout(this);
        drivesList.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (18 * d);
        drivesList.setPadding(pad, (int) (8 * d), pad, pad);
        scroll.addView(drivesList);
        return scroll;
    }

    /** The drive picker, like WinDirStat's "Select drives", plus what ThorDirStat needs to see everything. */
    private void refreshDrives() {
        drivesList.removeAllViews();
        drivesList.addView(panelText("Choose what to scan", 19, 255));

        if (!Environment.isExternalStorageManager()) {
            drivesList.addView(card(
                    "🔓 Allow access to all files",
                    "ThorDirStat needs Android's \"All files access\" to measure every folder. It only reads "
                            + "sizes and names, and changes nothing unless you delete something yourself.",
                    "Allow access", new View.OnClickListener() {
                        @Override public void onClick(View v) { requestAllFiles(); }
                    }));
        }

        String shizuku;
        String shizukuButton;
        if (ShizukuClient.hasPermission()) {
            shizuku = "Shizuku is on: Android/data and Android/obb (game data, emulator files, downloads "
                    + "inside apps) are measured too.";
            shizukuButton = null;
        } else if (ShizukuClient.isRunning()) {
            shizuku = "Optional: allow ThorDirStat in Shizuku to also measure Android/data and Android/obb, "
                    + "which Android hides from apps. Often the biggest folders on a gaming handheld.";
            shizukuButton = "Allow ThorDirStat in Shizuku";
        } else {
            shizuku = "Optional: with Shizuku running, ThorDirStat can also measure Android/data and "
                    + "Android/obb, which Android hides from apps. Start Shizuku, then come back.";
            shizukuButton = "Open Shizuku";
        }
        drivesList.addView(card("🧩 Hidden app folders", shizuku, shizukuButton, new View.OnClickListener() {
            @Override public void onClick(View v) { shizukuClicked(); }
        }));

        final List<Volumes.Volume> vols = Volumes.list(this);
        for (final Volumes.Volume v : vols) {
            drivesList.addView(volumeRow(v));
        }
        if (vols.size() > 1) {
            long used = 0, total = 0;
            for (Volumes.Volume v : vols) {
                used += v.used();
                total += v.total;
            }
            drivesList.addView(volumeRow(new Volumes.Volume("", "All storage (" + vols.size() + " drives)", false, total, total - used), vols));
        }
        if (vols.isEmpty()) drivesList.addView(panelText("No storage found.", 15, 190));

        if (Session.get().root() != null) {
            Button back = panelButton("Back to the results", new View.OnClickListener() {
                @Override public void onClick(View v) { showDrives(false); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) (14 * d);
            drivesList.addView(back, lp);
        }
    }

    private View volumeRow(Volumes.Volume v) {
        return volumeRow(v, Collections.singletonList(v));
    }

    private View volumeRow(Volumes.Volume v, final List<Volumes.Volume> toScan) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (12 * d);
        row.setPadding(pad, pad, pad, pad);
        row.setBackground(rounded(Color.rgb(0x16, 0x18, 0x1d)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        String icon = toScan.size() > 1 ? "🗄️ " : v.removable ? "💾 " : "📱 ";
        info.addView(panelText(icon + v.label, 16, 255));
        if (!v.path.isEmpty()) info.addView(panelText(v.path, 12, 140));
        UsageBar bar = new UsageBar(this, v.total > 0 ? (float) v.used() / v.total : 0);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (10 * d));
        blp.topMargin = (int) (6 * d);
        blp.bottomMargin = (int) (4 * d);
        info.addView(bar, blp);
        info.addView(panelText(Format.size(v.used()) + " used of " + Format.size(v.total)
                + "  ·  " + Format.size(v.free) + " free", 13, 190));
        row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button scan = panelButton("Scan", new View.OnClickListener() {
            @Override public void onClick(View view) { startScan(toScan); }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams((int) (96 * d), (int) (44 * d));
        slp.leftMargin = (int) (12 * d);
        row.addView(scan, slp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (10 * d);
        row.setLayoutParams(lp);
        return row;
    }

    private View card(String title, String body, String button, View.OnClickListener onClick) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * d);
        c.setPadding(pad, pad, pad, pad);
        c.setBackground(rounded(Color.rgb(0x1a, 0x22, 0x2c)));
        c.addView(panelText(title, 16, 255));
        TextView b = panelText(body, 13, 190);
        b.setPadding(0, (int) (4 * d), 0, 0);
        c.addView(b);
        if (button != null) {
            Button btn = panelButton(button, onClick);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) (8 * d);
            c.addView(btn, lp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (10 * d);
        c.setLayoutParams(lp);
        return c;
    }

    /** A volume's used space, as a bar. */
    private static final class UsageBar extends View {
        private final float frac;
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        UsageBar(Context c, float frac) {
            super(c);
            this.frac = Math.max(0, Math.min(1, frac));
        }

        @Override protected void onDraw(android.graphics.Canvas c) {
            float r = getHeight() / 2f;
            p.setColor(Color.rgb(0x2a, 0x2d, 0x35));
            c.drawRoundRect(0, 0, getWidth(), getHeight(), r, r, p);
            p.setColor(frac > 0.9f ? Color.rgb(0xe0, 0x50, 0x40) : Color.rgb(0x46, 0x82, 0xd2));
            c.drawRoundRect(0, 0, Math.max(getHeight(), getWidth() * frac), getHeight(), r, r, p);
        }
    }

    // ---- Permissions --------------------------------------------------------------------------

    private void requestAllFiles() {
        if (ShizukuClient.hasPermission()) {
            // Shizuku can switch it on directly, no trip to Settings.
            new Thread(new Runnable() {
                @Override public void run() {
                    ShizukuClient.runShell("appops set " + getPackageName() + " MANAGE_EXTERNAL_STORAGE allow");
                    runOnUiThread(new Runnable() {
                        @Override public void run() { refreshDrives(); }
                    });
                }
            }, "ThorDirStat grant").start();
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (RuntimeException e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    private void shizukuClicked() {
        if (ShizukuClient.isRunning()) {
            ShizukuClient.requestPermission();
            return;
        }
        Intent i = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
        if (i == null) {
            i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RikkaApps/Shizuku/releases"));
        }
        try {
            startActivity(i);
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void onShizukuChanged() {
        refreshDrives();
    }

    // ---- Scanning -----------------------------------------------------------------------------

    private void startScan(List<Volumes.Volume> vols) {
        if (!Environment.isExternalStorageManager()) {
            android.widget.Toast.makeText(this, "Allow access to all files first", android.widget.Toast.LENGTH_SHORT).show();
            requestAllFiles();
            return;
        }
        Haptics.play(this, Haptics.BAR);
        drivesWanted = false;
        Session.get().scan(this, new ArrayList<>(vols));
        if (dual && !TreemapActivity.isOpen()) openTreemapScreen(this);
    }

    private final Runnable progress = new Runnable() {
        @Override public void run() {
            Session s = Session.get();
            Scanner sc = s.scanner();
            if (sc == null) return;
            long secs = (System.currentTimeMillis() - s.scanStarted()) / 1000;
            scanText.setText("Scanning " + s.scanLabel() + "…\n" + Format.count(sc.files.get()) + " files  ·  "
                    + Format.size(sc.bytes.get()) + "  ·  " + String.format(java.util.Locale.US, "%d:%02d", secs / 60, secs % 60));
            scanPath.setText(sc.current);
            handler.postDelayed(this, 200);
        }
    };

    private void showDrives(boolean show) {
        drivesWanted = show;
        if (show) refreshDrives();
        updatePanels();
    }

    private void updatePanels() {
        Session s = Session.get();
        boolean scanning = s.isScanning();
        boolean drives = !scanning && (drivesWanted || s.root() == null);
        drivesPanel.setVisibility(drives ? View.VISIBLE : View.GONE);
        scanPanel.setVisibility(scanning ? View.VISIBLE : View.GONE);
        pacman.setRunning(scanning);
        handler.removeCallbacks(progress);
        if (scanning) handler.post(progress);
        zoomOutButton.setEnabled(s.zoom() != s.root());
        zoomOutButton.setAlpha(s.zoom() != s.root() ? 1f : 0.45f);
    }

    // ---- Session ------------------------------------------------------------------------------

    @Override
    public void onSessionChanged(int what) {
        if ((what & Session.CLOSE) != 0) {
            finishAndRemoveTask();
            return;
        }
        boolean scanFinished = (what & Session.SCAN) != 0 && (what & Session.TREE) != 0;
        if (scanFinished && !Session.get().isScanning() && Session.get().root() != null) {
            Haptics.play(this, Haptics.SCAN_DONE);
        }
        if ((what & (Session.TREE | Session.SCAN)) != 0) {
            folders.refresh();
            typeList.refresh();
        }
        if ((what & Session.SELECTION) != 0) folders.reveal(Session.get().selected());
        if ((what & (Session.EXT | Session.ZOOM)) != 0) {
            typeList.refresh();
            folders.reveal(null);
        }
        if ((what & Session.EXT) != 0 && Session.get().selectedType() != null && !showingTypes) showTypes(true);
        refreshSelectionLine();
        updatePanels();
    }

    private void refreshAll() {
        folders.refresh();
        typeList.refresh();
        showTypes(false);
        refreshSelectionLine();
        updatePanels();
    }

    private void refreshSelectionLine() {
        Session s = Session.get();
        Node n = s.selected();
        if (s.isBusy()) {
            selectionLine.setText("⏳ Working…");
        } else if (n == null) {
            selectionLine.setText(s.root() == null ? "" : "Tap an item to select it, hold it for actions");
        } else {
            String where = n.isReal() && n.parent != null ? n.path() : n.displayName();
            selectionLine.setText(where + "  ·  " + Format.size(n.size));
        }
    }

    private void showTypes(boolean types) {
        showingTypes = types;
        folders.setVisibility(types ? View.GONE : View.VISIBLE);
        typeList.setVisibility(types ? View.VISIBLE : View.GONE);
        styleTab(foldersTab, !types);
        styleTab(typesTab, types);
    }

    private void zoomIn() {
        Session s = Session.get();
        Node n = s.selected();
        if (n == null) return;
        Node dir = n.kind == Node.DIR ? n : n.parent;
        if (dir == null || dir == s.zoom()) return;
        Haptics.play(this, Haptics.ZOOM);
        s.zoomTo(dir);
    }

    @Override
    public void onRowLongPress(Node n) {
        Actions.show(this, n);
    }

    @Override
    public void onBackPressed() {
        // Back from the drive picker returns to the results; otherwise it closes both screens.
        if (drivesPanel.getVisibility() == View.VISIBLE && Session.get().root() != null && !Session.get().isScanning()) {
            showDrives(false);
            return;
        }
        Session.get().cancelScan();
        Session.get().closeAll();
        finishAndRemoveTask();
    }

    // ---- Gamepad --------------------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (handleKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    /**
     * D-pad: move through the list (left/right close and open folders). A: open or close the
     * folder. X: actions. Y: zoom in. B: zoom out (then back). L1 / R1: Folders / File types.
     */
    boolean handleKey(KeyEvent e) {
        if (drivesPanel.getVisibility() == View.VISIBLE || scanPanel.getVisibility() == View.VISIBLE) return false;
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        Session s = Session.get();
        Node sel = s.selected();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                if (!down) return true;
                int step = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                if (showingTypes) {
                    typeList.step(step);
                } else {
                    Node next = folders.neighbour(sel, step);
                    if (next != null) s.select(next);
                }
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (!down || showingTypes || sel == null) return true;
                if (sel.kind == Node.DIR && sel.children.length > 0) {
                    if (!folders.isOpen(sel)) folders.setOpen(sel, true);
                    else s.select(sel.children[0]);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (!down || showingTypes || sel == null) return true;
                if (sel.kind == Node.DIR && folders.isOpen(sel) && sel != s.root()) folders.setOpen(sel, false);
                else if (sel.parent != null) s.select(sel.parent);
                return true;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                if (!down) return true;
                if (showingTypes) {
                    typeList.step(0);
                } else if (sel != null && sel.kind == Node.DIR && sel.children.length > 0) {
                    Haptics.play(this, Haptics.EXPAND);
                    folders.toggle(sel);
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_X:
                if (down) Actions.show(this, sel);
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
                if (down) zoomIn();
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
                if (!down) return true;
                if (s.zoomOut()) {
                    Haptics.play(this, Haptics.ZOOM);
                    return true;
                }
                return false;
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_R1:
                if (down) showTypes(code == KeyEvent.KEYCODE_BUTTON_R1);
                return true;
            default:
                return false;
        }
    }

    // ---- Opening the treemap screen ---------------------------------------------------------------

    /** Opens the treemap on the other screen (the top one, unless Settings swaps them). */
    static void openTreemapScreen(Context context) {
        Intent intent = new Intent(context, TreemapActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Display target = Displays.treemapDisplay(context);
        try {
            ActivityOptions options = ActivityOptions.makeBasic();
            if (target != null) options.setLaunchDisplayId(target.getDisplayId());
            context.startActivity(intent, options.toBundle());
        } catch (RuntimeException e) {
            try {
                context.startActivity(intent);
            } catch (RuntimeException ignored) {
            }
        }
    }

    // ---- Small helpers ---------------------------------------------------------------------------

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp.setMargins(4, 0, 4, 0);
        return lp;
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(10 * d);
        return bg;
    }

    private Button barButton(String text, final View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(Color.argb(200, 255, 255, 255));
        b.setPadding(0, 0, 0, 0);
        b.setBackground(rounded(Color.rgb(28, 28, 32)));
        b.setFocusable(false);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.play(MainActivity.this, Haptics.BAR);
                listener.onClick(v);
            }
        });
        return b;
    }

    private void styleTab(Button tab, boolean active) {
        tab.setBackground(rounded(active ? Color.rgb(0x1d, 0x3a, 0x5c) : Color.rgb(28, 28, 32)));
        tab.setTextColor(active ? Color.WHITE : Color.argb(170, 255, 255, 255));
    }

    private TextView panelText(String s, int sp, int alpha) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.argb(alpha, 255, 255, 255));
        return t;
    }

    private Button panelButton(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(Color.WHITE);
        b.setBackground(rounded(Color.rgb(0x2a, 0x4a, 0x70)));
        int p = (int) (14 * d);
        b.setPadding(p, 0, p, 0);
        b.setOnClickListener(listener);
        return b;
    }
}
