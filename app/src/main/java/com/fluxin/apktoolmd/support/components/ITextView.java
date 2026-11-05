package com.fluxin.apktoolmd.support.components;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fluxin.apktoolmd.support.FeatureEntity;

public class ITextView {
    private Ui ui;

    public ITextView(Ui ui) { this.ui = ui; }

    public View build(final FeatureEntity item) {
        Context ctx = ui.context;
        TextView tv = new TextView(ctx);
        tv.setText(item.name);
        tv.setTextColor(Theme.TEXT);
        tv.setTextSize(14);
        tv.setTypeface(ui.regular);
        return tv;
    }
}
