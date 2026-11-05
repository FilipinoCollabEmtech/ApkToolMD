package com.fluxin.apktoolmd.support.components;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fluxin.apktoolmd.support.entity.FeatureEntity;

public class Ui {
    public interface Host {
        void beginEdit(android.widget.EditText field);
        void endEdit();
        void openPage(String title, java.util.List<FeatureEntity> children);
        void closePage();
    }

    private Context context;
    private Typeface typeface;
    private Host host;

    public Typeface bold = Typeface.DEFAULT_BOLD;
    public Typeface regular = Typeface.DEFAULT;
    public Typeface medium = Typeface.DEFAULT;

    public Ui(Context context, Typeface typeface, Host host) {
        this.context = context;
        this.typeface = typeface;
        this.host = host;
    }

    public Context getContext() {
        return context;
    }

    public float dp(float value) {
        return value * context.getResources().getDisplayMetrics().density;
    }

    public TextView text(String text, int size, int color, Typeface tf) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(size);
        tv.setTextColor(color);
        tv.setTypeface(tf != null ? tf : typeface);
        return tv;
    }

    public IconView icon(String name, int size, int color) {
        return new IconView(context, name, size, color);
    }

    public LinearLayout card(boolean stroke, boolean padding) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        return card;
    }

    public LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        return lp;
    }

    public static String html(String s) {
        return s;
    }
}
