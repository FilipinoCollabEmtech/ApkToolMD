package com.fluxin.apktoolmd.support.components;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fluxin.apktoolmd.support.entity.FeatureEntity;
import com.fluxin.apktoolmd.support.Preferences;

public class IInputText {
    private Ui ui;

    public IInputText(Ui ui) { this.ui = ui; }

    public View build(final FeatureEntity item) {
        Context ctx = ui.getContext();
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

        EditText et = new EditText(ctx);
        et.setText("");
        et.setTextColor(Theme.TEXT);
        et.setTextSize(14);
        et.setSingleLine(true);
        et.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                String val = et.getText().toString();
                Preferences.Changes(ctx, item.id, item.name, item.value, item.floatValue, false, val);
            }
        });

        row.addView(label);
        row.addView(et);
        return row;
    }
}
