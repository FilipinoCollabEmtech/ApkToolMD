package com.fluxin.apktoolmd.support;

import android.content.Context;
import android.content.Intent;

public class Main {

    public static final String OVERLAY_PERMISSION_KEY = "OVERLAY_PERMISSION_KEY";

    static {
        System.loadLibrary("GameHelper");
    }

    public static void Start(Context context) {
        CrashHandler.init(context, false);
        Intent intent = new Intent(context, Launcher.class);
        intent.putExtra(OVERLAY_PERMISSION_KEY, Utils.CheckOverlayPermissions(context));
        if (!Utils.CheckOverlayPermissions(context)) {
            Utils.RequestOverlayPermissions(context);
        }
        context.startService(intent);
    }
}
