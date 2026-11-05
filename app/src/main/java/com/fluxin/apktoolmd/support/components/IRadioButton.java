package com.fluxin.apktoolmd.support.components;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import com.fluxin.apktoolmd.support.FeatureEntity;
import com.fluxin.apktoolmd.support.Preferences;

public class IRadioButton {
    private Ui ui;

    public IRadioButton(Ui ui) { this.ui = ui; }

    public View build(final FeatureEntity item) {
        Context ctx = ui.getContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);

        TextView label = new TextView(ctx);
        label.setText(item.name);
        label.setTextColor(Theme.TEXT);
        label.setTextSize(14);
        row.addView(label);

        RadioGroup rg = new RadioGroup(ctx);
        for (int i = 0; i < item.options.size(); i++) {
            RadioButton rb = new RadioButton(ctx);
            rb.setText(item.options.get(i));
            rb.setTextColor(Theme.TEXT);
            final int index = i;
            rb.setOnClickListener(v -> {
                item.value = index;
                Preferences.Changes(ctx, item.id, item.name, item.value, item.floatValue, false, null);
            });
            if (i == item.value) rb.setChecked(true);
            rg.addView(rb);
        }
        row.addView(rg);
        return row;
    }
}
