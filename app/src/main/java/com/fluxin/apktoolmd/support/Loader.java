package com.fluxin.apktoolmd.support;

import android.content.Context;
import android.util.Log;

public class Loader {

    static {
        System.loadLibrary("Loader");
    }

    private static native boolean isRemapSuccess();

    public static void Start(Context context) {
        if (isRemapSuccess()) {
            Main.Start(context);
            Log.i("Loader", "Remap success");
        } else {
            Log.e("Loader", "Failed to remap library");
        }
    }
}
