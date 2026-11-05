package com.fluxin.apktoolmd.support.components;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fluxin.apktoolmd.support.FeatureEntity;

public class ICollapse {
    private Ui ui;

    public ICollapse(Ui ui) { this.ui = ui; }

    public View build(final FeatureEntity item) {
        Context ctx = ui.getContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);

        TextView header = new TextView(ctx);
        header.setText(item.name);
        header.setTextColor(Theme.TEXT);
        header.setTextSize(14);
        header.setTypeface(ui.bold);
        row.addView(header);

        return row;
    }
}
