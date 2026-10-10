package com.thordirstat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.View;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Spinner;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/** Sliders and switches for the screens, the treemap, scanning and haptics. */
public class SettingsActivity extends Activity {
    private Prefs prefs;
    private LinearLayout list;
    private float d;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        d = getResources().getDisplayMetrics().density;

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * d);
        list.setPadding(pad, pad, pad, pad);
        scroll.addView(list);

        list.addView(text("ThorDirStat settings", 20));

        list.addView(text("\nScreens", 17));
        toggle("Use both screens (treemap on the top screen, lists on the bottom). Reopen "
                + "ThorDirStat after changing.", Prefs.KEY_BOTH_SCREENS, prefs.bothScreens());
        toggle("Swap screens (treemap on the bottom, lists on the top). Reopen ThorDirStat after changing.",
                Prefs.KEY_SWAP_SCREENS, prefs.swapScreens());

        list.addView(text("\nTreemap", 17));
        toggle("Cushion shading (each folder shows as a bump, like WinDirStat)",
                Prefs.KEY_CUSHION, prefs.treemapOptions().cushion);
        toggle("Grid lines between files", Prefs.KEY_GRID, prefs.treemapOptions().grid);
        slider("Brightness", Prefs.KEY_BRIGHTNESS, prefs.treemapOptions().brightness, 0.5f, 1.5f);
        slider("Cushion height", Prefs.KEY_CUSHION_HEIGHT, prefs.treemapOptions().height, 0f, 1f);
        slider("Cushion scale (how much flatter each level gets)", Prefs.KEY_CUSHION_SCALE,
                prefs.treemapOptions().scale, 0.1f, 1f);
        Button reset = new Button(this);
        reset.setText("Reset treemap to WinDirStat's defaults");
        reset.setAllCaps(false);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.raw().edit().remove(Prefs.KEY_CUSHION).remove(Prefs.KEY_GRID).remove(Prefs.KEY_BRIGHTNESS)
                        .remove(Prefs.KEY_CUSHION_HEIGHT).remove(Prefs.KEY_CUSHION_SCALE).apply();
                Session.get().settingsChanged(SettingsActivity.this);
                recreate();
            }
        });
        list.addView(reset, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        list.addView(text("\nScanning", 17));
        toggle("Show <Unknown> (used space no folder accounts for: apps, their private data and Android)",
                Prefs.KEY_SHOW_UNKNOWN, prefs.showUnknown());
        toggle("Count size on disk instead of file length (takes effect on the next scan)",
                Prefs.KEY_SIZE_ON_DISK, prefs.sizeOnDisk());

        hapticsSection();

        TextView help = text("\nControls\n"
                + "• Tap an item: select it (the treemap and the list follow each other)\n"
                + "• Tap ▸, or tap a selected folder: open or close it\n"
                + "• Press and hold: actions (zoom, open, share, properties, copy path, rescan, delete)\n"
                + "• Double-tap the treemap: zoom into that folder\n"
                + "• Tap a file type: outline all its files in the treemap\n"
                + "• D-pad: move, ◀ ▶ close and open folders\n"
                + "• A: open or close · X: actions · Y: zoom in · B: zoom out · L1 / R1: Folders / File types", 14);
        help.setTextColor(Color.argb(170, 255, 255, 255));
        list.addView(help);

        updateSection();

        FrameLayout root = new FrameLayout(this);
        root.addView(scroll);
        previewPill = new TextView(this);
        previewPill.setTextSize(15);
        previewPill.setTextColor(Color.rgb(0xbf, 0xe6, 0xff));
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(Color.rgb(0x1d, 0x2a, 0x36));
        pillBg.setCornerRadius(30 * d);
        previewPill.setBackground(pillBg);
        previewPill.setPadding((int) (14 * d), (int) (7 * d), (int) (14 * d), (int) (7 * d));
        previewPill.setVisibility(View.GONE);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        plp.bottomMargin = (int) (14 * d);
        root.addView(previewPill, plp);
        setContentView(root);
    }

    // ---- Updates -------------------------------------------------------------------------------

    private void updateSection() {
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        list.addView(text("\nUpdates", 17));
        final TextView status = text("ThorDirStat " + version, 14);
        status.setTextColor(Color.argb(190, 255, 255, 255));
        list.addView(status);
        final Button check = new Button(this);
        check.setText("Check for updates");
        check.setAllCaps(false);
        check.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                check.setEnabled(false);
                final Updater[] updater = new Updater[1];
                updater[0] = new Updater(SettingsActivity.this, new Updater.Listener() {
                    @Override public void onUpdateStatus(String s, boolean done) {
                        status.setText(s);
                        if (done) check.setEnabled(true);
                    }

                    @Override public void onUpdateAvailable(Updater.Release r) {
                        showWhatsNew(r, updater[0]);
                    }
                });
                updater[0].check();
            }
        });
        list.addView(check, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Installs the latest release again even if it's this version, e.g. after its APK
        // was replaced on GitHub without a new version number.
        final Button reinstall = new Button(this);
        reinstall.setText("Reinstall");
        reinstall.setAllCaps(false);
        reinstall.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                reinstall.setEnabled(false);
                final Updater[] updater = new Updater[1];
                updater[0] = new Updater(SettingsActivity.this, new Updater.Listener() {
                    @Override public void onUpdateStatus(String s, boolean done) {
                        status.setText(s);
                        if (done) reinstall.setEnabled(true);
                    }

                    @Override public void onUpdateAvailable(Updater.Release r) {
                        showWhatsNew(r, updater[0]);
                    }
                });
                updater[0].check(true);
            }
        });
        list.addView(reinstall, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // Room so the preview pill never covers the button.
        list.addView(new View(this), new LinearLayout.LayoutParams(1, (int) (50 * d)));
    }

    /** "ThorDirStat x is available": the release notes, with Update and Not now. */
    private void showWhatsNew(final Updater.Release r, final Updater updater) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (22 * d);
        box.setPadding(pad, (int) (18 * d), pad, 0);

        TextView title = new TextView(this);
        final boolean same = r.version.equals(r.current);
        title.setText(same ? "🔁 Reinstall ThorDirStat " + r.version + "?" : "🎉 ThorDirStat " + r.version + " is available");
        title.setTextSize(20);
        title.setTextColor(Color.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        box.addView(title);
        TextView sub = new TextView(this);
        sub.setText((same ? "Downloads this version again and installs it over the one you have. Settings are kept"
                : "You have " + r.current) + (r.apkSize > 0 ? " · " + (r.apkSize / 1024) + " KB download" : ""));
        sub.setTextSize(13);
        sub.setTextColor(Color.rgb(0x8a, 0x90, 0x9b));
        sub.setPadding(0, (int) (4 * d), 0, (int) (10 * d));
        box.addView(sub);

        // Long notes scroll, so the buttons always stay on screen.
        final int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.55f);
        ScrollView scroll = new ScrollView(this) {
            @Override protected void onMeasure(int w, int h) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST));
            }
        };
        TextView notes = new TextView(this);
        notes.setText(ReleaseNotes.format(r.notes));
        notes.setTextSize(14);
        notes.setTextColor(Color.rgb(0xc9, 0xce, 0xd8));
        notes.setLineSpacing(0, 1.15f);
        scroll.addView(notes);
        box.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setView(box)
                .setNegativeButton("Not now", null)
                .setPositiveButton(same ? "Reinstall" : "Update", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { updater.install(r); }
                })
                .show();
    }

    // ---- Haptics -------------------------------------------------------------------------------

    private TextView previewPill;

    private final Runnable hidePill = new Runnable() {
        @Override public void run() { previewPill.setVisibility(View.GONE); }
    };

    private void hapticsSection() {
        list.addView(text("\nHaptics", 17));
        TextView hint = text("Pick a feel and strength for each action. Every change plays a preview.", 13);
        hint.setTextColor(Color.argb(170, 255, 255, 255));
        list.addView(hint);
        if (!Haptics.hasStrengthControl(this)) {
            TextView motor = text("This device's vibration motor can't buzz softer or harder, so strength "
                    + "changes how long each buzz lasts instead.", 13);
            motor.setTextColor(Color.argb(170, 255, 255, 255));
            list.addView(motor);
        }
        for (int a = 0; a < Haptics.ACTION_NAMES.length; a++) hapticRow(a);
    }

    private void hapticRow(final int action) {
        final SharedPreferences sp = prefs.raw();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight((int) (46 * d));

        TextView name = new TextView(this);
        name.setText(Haptics.ACTION_NAMES[action]);
        name.setTextSize(15);
        name.setTextColor(Color.WHITE);
        row.addView(name, new LinearLayout.LayoutParams((int) (150 * d), ViewGroup.LayoutParams.WRAP_CONTENT));

        final Spinner feel = new Spinner(this, Spinner.MODE_DROPDOWN);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, Haptics.FEEL_NAMES) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView t = (TextView) super.getView(position, convertView, parent);
                t.setTextColor(Color.rgb(0xcf, 0xe9, 0xff));
                t.setTextSize(15);
                return t;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        feel.setAdapter(adapter);
        feel.setSelection(Haptics.feel(sp, action), false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(0x1c, 0x1e, 0x24));
        bg.setCornerRadius(8 * d);
        feel.setBackground(bg);
        row.addView(feel, new LinearLayout.LayoutParams((int) (110 * d), (int) (36 * d)));

        final SeekBar strength = new SeekBar(this);
        strength.setMax(100);
        strength.setProgress(Haptics.strength(sp, action));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, (int) (40 * d), 1f);
        slp.leftMargin = (int) (12 * d);
        row.addView(strength, slp);

        final TextView pct = new TextView(this);
        pct.setTextSize(14);
        pct.setTextColor(Color.rgb(0xaa, 0xb3, 0xc0));
        pct.setGravity(Gravity.END);
        pct.setText(strength.getProgress() + "%");
        row.addView(pct, new LinearLayout.LayoutParams((int) (48 * d), ViewGroup.LayoutParams.WRAP_CONTENT));

        feel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == Haptics.feel(sp, action)) return; // not a change
                sp.edit().putInt(Haptics.feelKey(action), position).apply();
                preview(position, strength.getProgress());
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        strength.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                pct.setText(p + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {
                sp.edit().putInt(Haptics.strengthKey(action), sb.getProgress()).apply();
                preview(feel.getSelectedItemPosition(), sb.getProgress());
            }
        });
        list.addView(row);
    }

    private void preview(int feel, int strength) {
        Haptics.play(this, feel, strength);
        previewPill.setText(feel == Haptics.OFF || strength == 0
                ? "Off" : "▶ Preview: " + Haptics.FEEL_NAMES[feel] + ", " + strength + "%");
        previewPill.setVisibility(View.VISIBLE);
        previewPill.removeCallbacks(hidePill);
        previewPill.postDelayed(hidePill, 1500);
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.WHITE);
        t.setPadding(0, (int) (10 * d), 0, (int) (4 * d));
        return t;
    }

    private void slider(final String label, final String key, float value, final float min, final float max) {
        final TextView t = text(label + ": " + fmt(value), 15);
        list.addView(t);
        SeekBar bar = new SeekBar(this);
        bar.setMax(100);
        bar.setProgress(Math.round((value - min) / (max - min) * 100));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                float v = min + (max - min) * p / 100f;
                t.setText(label + ": " + fmt(v));
                prefs.raw().edit().putFloat(key, v).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {
                Session.get().settingsChanged(SettingsActivity.this);
            }
        });
        list.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (40 * d)));
    }

    private void toggle(String label, final String key, boolean value) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextColor(Color.WHITE);
        sw.setTextSize(15);
        sw.setChecked(value);
        sw.setPadding(0, (int) (12 * d), 0, (int) (12 * d));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                prefs.raw().edit().putBoolean(key, checked).apply();
                Session.get().settingsChanged(SettingsActivity.this);
            }
        });
        list.addView(sw);
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }
}
