package com.fluxin.apktoolmd.support.components;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.fluxin.apktoolmd.support.entity.FeatureEntity;
import com.fluxin.apktoolmd.support.Preferences;

public class ISlider {
    private Ui ui;

    public ISlider(Ui ui) { this.ui = ui; }

    public View build(final FeatureEntity item) {
        Context ctx = ui.getContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);

        TextView label = new TextView(ctx);
        label.setText(item.name + " " + item.value);
        label.setTextColor(Theme.TEXT);
        label.setTextSize(14);

        SeekBar sb = new SeekBar(ctx);
        sb.setMax(item.options.size() > 0 ? Integer.parseInt(item.options.get(1)) : 100);
        sb.setProgress(item.value);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                item.value = progress;
                label.setText(item.name + " " + progress);
                Preferences.Changes(ctx, item.id, item.name, item.value, item.floatValue, false, null);
            }
            public void onStartTrackingTouch(SeekBar seekBar) {}
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        row.addView(label);
        row.addView(sb);
        return row;
    }
}
