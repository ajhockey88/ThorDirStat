package com.thordirstat;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import moe.shizuku.api.BinderContainer;

/** Shizuku delivers its binder to apps through a provider at "<package>.shizuku". */
public class ShizukuBinderProvider extends ContentProvider {
    private static final String EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER";

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        super.attachInfo(context, info);
        if (info.multiprocess) throw new IllegalStateException("android:multiprocess must be false");
        if (!info.exported) throw new IllegalStateException("android:exported must be true");
    }

    @Override
    public boolean onCreate() { return true; }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (extras == null) return null;
        extras.setClassLoader(BinderContainer.class.getClassLoader());
        if ("sendBinder".equals(method)) {
            BinderContainer container = extras.getParcelable(EXTRA_BINDER);
            if (container != null && container.binder != null) {
                ShizukuClient.onBinderReceived(container.binder, getContext().getPackageName());
            }
        }
        return new Bundle();
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
