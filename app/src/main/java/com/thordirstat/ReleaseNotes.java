package com.thordirstat;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;

/** Turns a GitHub release's Markdown notes into styled text: headers, bullets and bold. */
final class ReleaseNotes {
    private ReleaseNotes() {}

    static CharSequence format(String markdown) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        String[] lines = markdown.replace("\r", "").split("\n");
        boolean skipping = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("## 📲")) { // install steps don't matter inside the app
                skipping = true;
                continue;
            }
            boolean version = line.startsWith("# "); // one release among several
            if (skipping && !version) continue;
            skipping = false;
            if (out.length() > 0) out.append(version ? "\n\n" : "\n");
            int start = out.length();
            if (version) {
                appendInline(out, line.substring(2));
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new RelativeSizeSpan(1.35f), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(Color.WHITE), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (line.startsWith("#")) {
                appendInline(out, line.replaceFirst("^#+\\s*", ""));
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new RelativeSizeSpan(1.15f), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(Color.rgb(0x78, 0xc8, 0xff)), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                out.append("•  ");
                appendInline(out, line.substring(2));
                out.setSpan(new LeadingMarginSpan.Standard(0, 40), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                appendInline(out, line);
            }
        }
        return out;
    }

    /** Adds text with **bold** shown bold and `code` marks dropped. */
    private static void appendInline(SpannableStringBuilder out, String s) {
        s = s.replace("`", "");
        boolean bold = false;
        int i = 0;
        while (i < s.length()) {
            int next = s.indexOf("**", i);
            if (next < 0) next = s.length();
            int start = out.length();
            out.append(s, i, next);
            if (bold) {
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(Color.WHITE), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            bold = !bold;
            i = next + 2;
        }
    }
}
