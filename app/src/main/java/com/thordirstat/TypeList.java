package com.thordirstat;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;

import java.util.List;

/**
 * WinDirStat's extension list: each file type's colour, description, bytes, share and file
 * count, biggest first. Tap one to outline all its files in the treemap; tap it again to clear.
 */
final class TypeList extends ListView {
    private final float d;
    private final Adapter adapter = new Adapter();

    TypeList(Context context) {
        super(context);
        d = getResources().getDisplayMetrics().density;
        setDivider(null);
        setBackgroundColor(Color.BLACK);
        setFocusable(false);
        setFastScrollEnabled(true);
        setAdapter(adapter);
        setOnItemClickListener(new OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Haptics.play(getContext(), Haptics.SELECT);
                Session.get().selectType(types().get(position));
            }
        });
    }

    private List<FileTypes.Type> types() { return Session.get().types().sorted(); }

    void refresh() { adapter.notifyDataSetChanged(); }

    /** Moves the highlight up or down a row, for the gamepad. */
    void step(int dir) {
        List<FileTypes.Type> all = types();
        if (all.isEmpty()) return;
        int i = all.indexOf(Session.get().selectedType());
        int j = i < 0 ? 0 : Math.max(0, Math.min(all.size() - 1, i + dir));
        if (j == i) return;
        Session.get().selectType(all.get(j));
        if (j <= getFirstVisiblePosition() || j >= getLastVisiblePosition()) {
            setSelectionFromTop(j, getHeight() / 2);
        }
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return types().size(); }
        @Override public Object getItem(int position) { return types().get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            Row r = convertView instanceof Row ? (Row) convertView : new Row(getContext());
            r.bind(types().get(position));
            return r;
        }
    }

    private final class Row extends View {
        private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint();
        private FileTypes.Type type;

        Row(Context c) {
            super(c);
            text.setTextSize(14 * d);
            text.setColor(Color.WHITE);
            small.setTextSize(12.5f * d);
            small.setColor(Color.rgb(0xaa, 0xb3, 0xc0));
            small.setTextAlign(Paint.Align.RIGHT);
        }

        void bind(FileTypes.Type t) {
            type = t;
            invalidate();
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), (int) (34 * d));
        }

        @Override
        protected void onDraw(Canvas c) {
            if (type == null) return;
            int w = getWidth(), h = getHeight();
            if (type == Session.get().selectedType()) {
                fill.setColor(Color.rgb(0x4a, 0x42, 0x10));
                c.drawRect(0, 0, w, h, fill);
            }
            float base = h / 2f - (text.descent() + text.ascent()) / 2;
            float x = 10 * d;
            fill.setColor(0xff000000 | type.color);
            float sw = 14 * d;
            c.drawRect(x, h / 2f - sw / 2, x + sw, h / 2f + sw / 2, fill);
            x += sw + 10 * d;

            float right = w - 8 * d;
            float filesX = right, sizeX = filesX - 84 * d, barRight = sizeX - 84 * d, barLeft = barRight - 74 * d;
            float extW = 80 * d;
            CharSequence ext = TextUtils.ellipsize(type.label(), text, extW - 6 * d, TextUtils.TruncateAt.END);
            c.drawText(ext, 0, ext.length(), x, base, text);
            text.setColor(Color.rgb(0xc9, 0xce, 0xd8));
            CharSequence desc = TextUtils.ellipsize(type.description(), text,
                    Math.max(0, barLeft - 8 * d - x - extW), TextUtils.TruncateAt.END);
            c.drawText(desc, 0, desc.length(), x + extW, base, text);
            text.setColor(Color.WHITE);

            long total = Session.get().types().totalBytes();
            if (total > 0) {
                float top = h * 0.28f, bottom = h * 0.72f;
                fill.setColor(Color.rgb(0x22, 0x24, 0x2a));
                c.drawRect(barLeft, top, barRight, bottom, fill);
                fill.setColor(0xff000000 | type.color);
                fill.setAlpha(170);
                c.drawRect(barLeft, top, barLeft + (barRight - barLeft) * type.bytes / (float) total, bottom, fill);
                fill.setAlpha(255);
                small.setTextAlign(Paint.Align.LEFT);
                small.setColor(Color.WHITE);
                c.drawText(Format.percent(type.bytes, total), barLeft + 4 * d, base, small);
                small.setTextAlign(Paint.Align.RIGHT);
                small.setColor(Color.rgb(0xaa, 0xb3, 0xc0));
            }
            c.drawText(Format.size(type.bytes), sizeX, base, small);
            c.drawText(Format.count(type.files) + (type.files == 1 ? " file" : " files"), filesX, base, small);
        }
    }
}
