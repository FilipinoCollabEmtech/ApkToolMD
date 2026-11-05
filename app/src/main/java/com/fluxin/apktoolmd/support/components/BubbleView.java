package com.fluxin.apktoolmd.support.components;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

public class BubbleView extends View {
    private Paint paint;
    private String text;
    private Bitmap icon;

    public BubbleView(Context context, Typeface tf, String label, Bitmap iconBitmap) {
        super(context);
        this.text = label;
        this.icon = iconBitmap;
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Theme.ACCENT);
        paint.setTextSize(20);
        paint.setTypeface(tf != null ? tf : Typeface.DEFAULT_BOLD);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        canvas.drawCircle(w / 2f, h / 2f, Math.min(w, h) / 2f, paint);
        if (icon != null) {
            canvas.drawBitmap(icon, 0, 0, null);
        } else {
            paint.setColor(Theme.TEXT);
            canvas.drawText(text, w / 2f - paint.measureText(text) / 2f, h / 2f + paint.getTextSize() / 3f, paint);
        }
    }
}
