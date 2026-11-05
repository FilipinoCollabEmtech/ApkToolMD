package com.fluxin.apktoolmd.support.components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

public class MarkView extends View {
    public MarkView(Context context) {
        super(context);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Theme.ACCENT);
        Path path = new Path();
        path.moveTo(w * 0.55f, 0);
        path.lineTo(w, 0);
        path.lineTo(w - w * 0.55f, h);
        path.lineTo(0, h);
        path.close();
        canvas.drawPath(path, paint);
    }
}
