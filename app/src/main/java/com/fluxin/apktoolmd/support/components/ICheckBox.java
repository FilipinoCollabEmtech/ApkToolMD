package com.fluxin.apktoolmd.support.components;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fluxin.apktoolmd.support.FeatureEntity;

public class ICheckBox {
    private Ui ui;

    public ICheckBox(Ui ui) { this.ui = ui; }

    public View build(final FeatureEntity item) {
        Context ctx = ui.context;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(ctx);
        label.setText(item.name);
        label.setTextColor(Theme.TEXT);
        label.setTextSize(14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = (int) ui.dp(4);
        label.setLayoutParams(lp);

        CheckBox cb = new CheckBox(ctx);
        cb.setChecked(item.value != 0);
        cb.setOnCheckedChangeListener((buttonView, isChecked) -> {
            item.value = isChecked ? 1 : 0;
            Preferences.Changes(ctx, item.id, item.name, item.value, item.floatValue, isChecked, null);
        });

        row.addView(label);
        row.addView(cb);
        return row;
    }
}
