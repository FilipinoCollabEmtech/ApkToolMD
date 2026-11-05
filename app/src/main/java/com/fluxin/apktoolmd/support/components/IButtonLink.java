package com.fluxin.apktoolmd.support.components;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.fluxin.apktoolmd.support.entity.FeatureEntity;
import com.fluxin.apktoolmd.support.Preferences;

public class IButtonLink {
    private Ui ui;

    public IButtonLink(Ui ui) { this.ui = ui; }

    public View build(final FeatureEntity item) {
        Context ctx = ui.getContext();
        Button btn = new Button(ctx);
        btn.setText(item.name);
        btn.setTextColor(Theme.ACCENT);
        btn.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(item.url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            Preferences.Changes(ctx, item.id, item.name, item.value, item.floatValue, false, item.url);
        });
        return btn;
    }
}
