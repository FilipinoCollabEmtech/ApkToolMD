package com.fluxin.apktoolmd.support;

import android.content.Context;
import android.content.SharedPreferences;

public class Preferences {
    public static Context context;
    private static final String PREFS_NAME = "r3dnetwork_prefs";

    private static SharedPreferences getPrefs() {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static native void Changes(Context context, int id, String name, int value, float floatValue, boolean booleanValue, String strValue);

    public static void loadPrefBool(String key, int id, boolean def) {
        boolean val = getPrefs().getBoolean(key, def);
        Changes(context, id, key, 0, 0.0f, val, "");
    }

    public static void savePrefBool(String key, boolean val) {
        getPrefs().edit().putBoolean(key, val).apply();
    }
}
