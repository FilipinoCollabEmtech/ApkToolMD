package com.fluxin.apktoolmd.ui

import android.content.Context

/**
 * The engine profile the app works with.
 *
 * The profile decides what the analyzer expects, which assets the
 * "dump assets" button extracts (Unity bundles, Cocos2d-x resources, ...)
 * and which tools make sense: the live UE4 dumper (and the aimbot that
 * rides inside it) only exist for Unreal Engine, so those buttons are
 * hidden for the other engines.
 * The choice is remembered between launches.
 */
enum class EngineProfile(val id: String, val label: String, val note: String) {
    UNITY("unity4", "UNITY ENGINE 4", "Unity asset bundles and metadata"),
    COCOS2D("cocos2d", "COCO2D Engine", "Cocos2d-x resources, scripts and plists"),
    UE4("ue4", "UE4 ENGINE", "Unreal Engine assets plus the live dumper and the aimbot");

    /** The live UE4 dumper / aimbot / auto-inject only exist for Unreal. */
    val isUnreal: Boolean
        get() = this == UE4

    companion object {
        private const val PREFS = "apktoolmd_prefs"
        private const val KEY_ENGINE = "engine_profile"

        fun current(context: Context): EngineProfile {
            val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ENGINE, null)
            return EngineProfile.values().firstOrNull { it.id == id } ?: UNITY
        }

        fun save(context: Context, profile: EngineProfile) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ENGINE, profile.id)
                .apply()
        }
    }
}
