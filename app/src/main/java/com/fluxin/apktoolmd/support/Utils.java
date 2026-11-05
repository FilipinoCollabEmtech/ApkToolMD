package com.fluxin.apktoolmd.support;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

public class Utils {

    public static boolean CheckOverlayPermissions(Context context) {
        try {
            return Settings.canDrawOverlays(context);
        } catch (Exception e) {
            return false;
        }
    }

    public static void RequestOverlayPermissions(Context context) {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:" + context.getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
