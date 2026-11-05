package com.fluxin.apktoolmd.support.components;

import android.content.Context;
import android.widget.TextView;

public class StatusView extends TextView {
    public StatusView(Ui ui) {
        super(ui.context);
        setText("R3D");
        setTextSize(12);
        setTextColor(Theme.TEXT_DIM);
    }
}
