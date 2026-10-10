package com.thordirstat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * WinDirStat's extension list: bytes and file counts per file extension, biggest first, and
 * the colour each extension gets in the treemap. Like WinDirStat, the biggest extensions get
 * the palette's colours and the rest share grey. No Android classes here.
 */
final class FileTypes implements Treemap.Colors {
    /** WinDirStat's default palette, in its order. */
    static final int[] PALETTE = {
            0x0000ff, 0xff0000, 0x00ff00, 0x00ffff, 0xff00ff, 0xffff00,
            0x9696ff, 0xff9696, 0x96ff96, 0x96ffff, 0xff96ff, 0xffff96, 0xffffff};
    static final int OTHER = 0x969696;
    static final int UNKNOWN = 0x787878;
    /** Installed apps, in <Apps>. */
    static final int APP = 0xb4a078;

    static final class Type {
        final String ext;
        long bytes;
        int files;
        int color = OTHER;

        Type(String ext) { this.ext = ext; }

        String label() { return ext == null ? "(no extension)" : "." + ext; }

        String description() { return describe(ext); }
    }

    private final Map<String, Type> byExt = new HashMap<>();
    private final List<Type> sorted = new ArrayList<>();
    private Type none;
    private long total;

    /** Counts every file under root. */
    FileTypes(Node root) {
        if (root != null) count(root);
        sorted.addAll(byExt.values());
        if (none != null) sorted.add(none);
        Collections.sort(sorted, new Comparator<Type>() {
            @Override public int compare(Type a, Type b) {
                if (a.bytes != b.bytes) return a.bytes > b.bytes ? -1 : 1;
                return a.label().compareTo(b.label());
            }
        });
        for (int i = 0; i < sorted.size() && i < PALETTE.length; i++) sorted.get(i).color = PALETTE[i];
    }

    private void count(Node n) {
        if (n.kind == Node.FILE) {
            Type t;
            if (n.ext == null) {
                if (none == null) none = new Type(null);
                t = none;
            } else {
                t = byExt.get(n.ext);
                if (t == null) byExt.put(n.ext, t = new Type(n.ext));
            }
            t.bytes += n.size;
            t.files++;
            total += n.size;
        } else if (n.kind == Node.DIR) {
            for (Node c : n.children) count(c);
        }
    }

    List<Type> sorted() { return sorted; }

    long totalBytes() { return total; }

    Type of(String ext) { return ext == null ? none : byExt.get(ext); }

    @Override public int color(Node leaf) {
        switch (leaf.kind) {
            case Node.UNKNOWN: return UNKNOWN;
            case Node.APP: return APP;
            case Node.FILE:
                Type t = of(leaf.ext);
                return t != null ? t.color : OTHER;
            default: return OTHER;
        }
    }

    /** "photo.JPG" -> "jpg"; no extension, or a dot file like ".nomedia", -> null. */
    static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1 || name.length() - dot > 12) return null;
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static final Map<String, String> DESCRIPTIONS = new HashMap<>();

    private static void d(String description, String... exts) {
        for (String e : exts) DESCRIPTIONS.put(e, description);
    }

    static {
        d("Android app", "apk", "apks", "xapk", "apkm");
        d("App expansion file", "obb");
        d("Video", "mp4", "mkv", "webm", "avi", "mov", "m4v", "3gp", "ts", "wmv", "flv", "mpg", "mpeg");
        d("Music or audio", "mp3", "flac", "ogg", "opus", "m4a", "aac", "wav", "wma", "amr", "mid");
        d("Picture", "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "tif", "tiff", "dng", "svg", "avif");
        d("Archive", "zip", "7z", "rar", "tar", "gz", "xz", "bz2", "zst", "tgz", "lz4");
        d("Disc image (game)", "iso", "chd", "cso", "zso", "rvz", "wbfs", "gcz", "nkit", "cue", "bin", "img", "ecm", "pbp", "mds", "mdf", "cdi", "gdi");
        d("Nintendo Switch game", "nsp", "xci", "nsz", "xcz");
        d("Nintendo 3DS game", "3ds", "cia", "cci", "cxi", "3dsx");
        d("Nintendo DS game", "nds");
        d("Game Boy game", "gb", "gbc", "gba");
        d("Nintendo 64 game", "n64", "z64", "v64");
        d("SNES / NES game", "sfc", "smc", "nes", "fds");
        d("Wii U game", "wua", "wud", "wux", "rpx");
        d("Sega game", "md", "gen", "smd", "sms", "gg", "32x");
        d("PlayStation 2 / PSP save or data", "ps2", "vmc", "mcd");
        d("PlayStation Vita game", "vpk");
        d("Windows program", "exe", "msi", "dll");
        d("Save state", "state", "ss0", "ss1", "ss2", "ss3", "st0", "srm", "sav", "dsv");
        d("Document", "pdf", "doc", "docx", "odt", "rtf", "epub", "mobi", "cbz", "cbr");
        d("Spreadsheet", "xls", "xlsx", "ods", "csv");
        d("Text", "txt", "log", "md", "ini", "cfg", "conf", "json", "xml", "yml", "yaml");
        d("Database", "db", "sqlite", "db-wal", "db-shm", "realm");
        d("Cache or temporary file", "tmp", "temp", "cache", "bak", "part", "crdownload");
        d("Code or library", "so", "jar", "dex", "odex", "vdex", "oat", "js", "py", "lua", "sh");
        d("Shader cache", "shadercache", "pso", "spv");
        d("Font", "ttf", "otf", "woff", "woff2");
        d("BIOS or firmware", "rom");
    }

    static String describe(String ext) {
        if (ext == null) return "Files without an extension";
        String s = DESCRIPTIONS.get(ext);
        return s != null ? s : ext.toUpperCase(Locale.ROOT) + " file";
    }
}
