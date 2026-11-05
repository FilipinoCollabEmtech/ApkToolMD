package com.fluxin.apktoolmd.support.components;

import android.content.Context;
import android.widget.TextView;

public class IconView extends TextView {
    public IconView(Context context, String name, int size, int color) {
        super(context);
        setText(name);
        setTextSize(size);
        setTextColor(color);
    }

    public void setColor(int color) {
        setTextColor(color);
    }
}
