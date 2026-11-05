package com.fluxin.apktoolmd.support.components;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fluxin.apktoolmd.support.FeatureEntity;

public class IInputInt {
    private Ui ui;
    private IInputText iInputText;

    public IInputInt(IInputText iInputText) { this.iInputText = iInputText; this.ui = iInputText.ui; }

    public View build(final FeatureEntity item) {
        return iInputText.build(item);
    }
}
