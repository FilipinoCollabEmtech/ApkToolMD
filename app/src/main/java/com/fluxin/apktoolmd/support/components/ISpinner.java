package com.fluxin.apktoolmd.support.components;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.fluxin.apktoolmd.support.FeatureEntity;

public class ISpinner {
    private Ui ui;

    public ISpinner(Ui ui) { this.ui = ui; }

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

        Spinner sp = new Spinner(ctx);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(ctx, android.R.layout.simple_spinner_item, item.options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(adapter);
        sp.setSelection(item.value);
        sp.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                item.value = pos;
                Preferences.Changes(ctx, item.id, item.name, item.value, item.floatValue, false, null);
            }
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        row.addView(label);
        row.addView(sp);
        return row;
    }
}
