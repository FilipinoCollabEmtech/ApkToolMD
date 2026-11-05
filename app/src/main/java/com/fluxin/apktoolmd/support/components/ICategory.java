package com.fluxin.apktoolmd.support.components;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fluxin.apktoolmd.support.FeatureEntity;

public class ICategory {
    private Ui ui;

    public ICategory(Ui ui) { this.ui = ui; }

    public View build(String name) {
        Context ctx = ui.getContext();
        TextView tv = new TextView(ctx);
        tv.setText(name);
        tv.setTextColor(Theme.TEXT_DIM);
        tv.setTextSize(12);
        tv.setTypeface(ui.bold);
        return tv;
    }

    public View build(final FeatureEntity item) {
        return build(item.name);
    }
}
