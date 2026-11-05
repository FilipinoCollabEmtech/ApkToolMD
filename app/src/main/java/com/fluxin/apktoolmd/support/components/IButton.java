package com.fluxin.apktoolmd.support.components;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fluxin.apktoolmd.support.entity.FeatureEntity;

public class IButton {
    private Ui ui;

    public IButton(Ui ui) { this.ui = ui; }

    public View build(final FeatureEntity item) {
        Context ctx = ui.getContext();
        Button btn = new Button(ctx);
        btn.setText(item.name);
        btn.setTextColor(Theme.TEXT);
        btn.setOnClickListener(v -> {
            Preferences.Changes(ctx, item.id, item.name, item.value, item.floatValue, false, null);
        });
        return btn;
    }
}
