package com.thordirstat;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/** WinDirStat's scanning Pac-Man, chomping along a row of dots while a scan runs. */
final class PacmanView extends View {
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private boolean running;

    PacmanView(Context context) {
        super(context);
        body.setColor(Color.rgb(0xff, 0xd8, 0x00));
        dot.setColor(Color.argb(200, 255, 255, 255));
    }

    void setRunning(boolean on) {
        running = on;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float r = h * 0.4f;
        long t = SystemClock.uptimeMillis();
        float span = w - 2 * r;
        float x = r + (t / 6f) % Math.max(1, span);
        float gap = r * 1.6f;
        for (float dx = x + gap; dx < w; dx += gap) c.drawCircle(dx, h / 2f, r * 0.18f, dot);
        float mouth = running ? 40 * Math.abs((float) Math.sin(t / 90.0)) : 30;
        oval.set(x - r, h / 2f - r, x + r, h / 2f + r);
        c.drawArc(oval, mouth, 360 - 2 * mouth, true, body);
        if (running) postInvalidateOnAnimation();
    }
}
