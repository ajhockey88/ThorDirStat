package com.thordirstat;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Hands a single file to another app for Open and Share, read-only, through a
 * content:// link that only that app is allowed to read.
 */
public class FilesProvider extends ContentProvider {
    static final String AUTHORITY = "com.thordirstat.files";

    static Uri uriFor(String path) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).encodedPath(Uri.encode(path, "/")).build();
    }

    static String mimeOf(String name) {
        String ext = FileTypes.extensionOf(name);
        String mime = ext == null ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime != null ? mime : "application/octet-stream";
    }

    /** An intent for another app to open (view) or share (send) a file. */
    static Intent intentFor(Context context, String path, boolean share) {
        Uri uri = uriFor(path);
        String mime = mimeOf(path);
        Intent i;
        if (share) {
            i = new Intent(Intent.ACTION_SEND);
            i.setType(mime);
            i.putExtra(Intent.EXTRA_STREAM, uri);
        } else {
            i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, mime);
        }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(i, share ? "Share with" : "Open with");
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return chooser;
    }

    private static File fileOf(Uri uri) throws FileNotFoundException {
        String path = uri.getPath();
        if (path == null || path.contains("/../")) throw new FileNotFoundException(String.valueOf(uri));
        File f = new File(path);
        if (!f.isFile()) throw new FileNotFoundException(path);
        return f;
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new SecurityException("read-only");
        return ParcelFileDescriptor.open(fileOf(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String sel, String[] args, String order) {
        File f;
        try {
            f = fileOf(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        MatrixCursor c = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[] {f.getName(), f.length()});
        return c;
    }

    @Override public String getType(Uri uri) { return mimeOf(String.valueOf(uri.getPath())); }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
