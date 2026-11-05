package com.fluxin.apktoolmd.support.components;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fluxin.apktoolmd.support.entity.FeatureEntity;

public class IInputInt {
    private IInputText iInputText;

    public IInputInt(IInputText iInputText) { this.iInputText = iInputText; }

    public View build(final FeatureEntity item) {
        return iInputText.build(item);
    }
}
