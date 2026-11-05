package com.fluxin.apktoolmd.support.components;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.fluxin.apktoolmd.support.entity.FeatureEntity;
import com.fluxin.apktoolmd.support.Preferences;

public class ISwitch {
    private Ui ui;

    public ISwitch(Ui ui) { this.ui = ui; }

    public View build(final FeatureEntity item) {
        Context ctx = ui.getContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(ctx);
        label.setText(item.name);
        label.setTextColor(Theme.TEXT);
        label.setTextSize(14);
        label.setTypeface(ui.regular);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = (int) ui.dp(4);
        label.setLayoutParams(lp);

        Switch sw = new Switch(ctx);
        sw.setChecked(item.value != 0);
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            item.value = isChecked ? 1 : 0;
            Preferences.Changes(ctx, item.id, item.name, item.value, item.floatValue, isChecked, null);
        });

        row.addView(label);
        row.addView(sw);
        return row;
    }
}
