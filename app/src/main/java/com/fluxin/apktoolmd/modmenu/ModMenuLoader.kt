package com.fluxin.apktoolmd.modmenu

import android.content.Context
import android.util.Log

/**
 * R3DNETWORK Mod Menu entry point.
 *
 * Loads libmodmenu.so and exposes JNI bindings to the feature variables
 * maintained in native code (god mode, aim assist, ESP, speed hack, etc.).
 *
 * The mod menu UI itself lives in the target game's overlay (shown via a
 * floating bubble). This class is the bridge between the Java/Kotlin side
 * and the native hook library.
 */
object ModMenuLoader {

    private const val TAG = "R3DModMenu"

    // Load the native library
    init {
        try {
            System.loadLibrary("modmenu")
            Log.i(TAG, "libmodmenu.so loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load libmodmenu.so: ${e.message}")
        }
    }

    //region Master switch
    fun isHooked(): Boolean {
        return nativeIsHooked()
    }

    fun setEnabled(enabled: Boolean) {
        nativeSetEnabled(enabled)
    }

    fun isEnabled(): Boolean {
        return nativeIsEnabled()
    }
    //endregion

    //region God Mode
    fun setGodMode(enabled: Boolean) {
        nativeSetGodMode(enabled)
    }

    fun isGodMode(): Boolean {
        return nativeIsGodMode()
    }
    //endregion

    //region Aim Assist
    fun setAimAssist(enabled: Boolean) {
        nativeSetAimAssist(enabled)
    }

    fun isAimAssist(): Boolean {
        return nativeIsAimAssist()
    }
    //endregion

    //region ESP
    fun setESP(enabled: Boolean) {
        nativeSetESP(enabled)
    }

    fun isESP(): Boolean {
        return nativeIsESP()
    }
    //endregion

    //region Speed Hack
    fun setSpeedHack(enabled: Boolean) {
        nativeSetSpeedHack(enabled)
    }

    fun isSpeedHack(): Boolean {
        return nativeIsSpeedHack()
    }

    fun setSpeedMult(mult: Float) {
        nativeSetSpeedMult(mult)
    }

    fun getSpeedMult(): Float {
        return nativeGetSpeedMult()
    }
    //endregion

    //region No Spread
    fun setNoSpread(enabled: Boolean) {
        nativeSetNoSpread(enabled)
    }

    fun isNoSpread(): Boolean {
        return nativeIsNoSpread()
    }
    //endregion

    //region Auto Fire
    fun setAutoFire(enabled: Boolean) {
        nativeSetAutoFire(enabled)
    }

    fun isAutoFire(): Boolean {
        return nativeIsAutoFire()
    }
    //endregion

    //region Infinite Ammo
    fun setInfiniteAmmo(enabled: Boolean) {
        nativeSetInfiniteAmmo(enabled)
    }

    fun isInfiniteAmmo(): Boolean {
        return nativeIsInfiniteAmmo()
    }
    //endregion

    //region FOV
    fun setFOV(fov: Float) {
        nativeSetFOV(fov)
    }

    fun getFOV(): Float {
        return nativeGetFOV()
    }
    //endregion

    //region Native declarations
    private external fun nativeIsHooked(): Boolean
    private external fun nativeSetEnabled(enabled: Boolean)
    private external fun nativeIsEnabled(): Boolean
    private external fun nativeSetGodMode(enabled: Boolean)
    private external fun nativeIsGodMode(): Boolean
    private external fun nativeSetAimAssist(enabled: Boolean)
    private external fun nativeIsAimAssist(): Boolean
    private external fun nativeSetESP(enabled: Boolean)
    private external fun nativeIsESP(): Boolean
    private external fun nativeSetSpeedHack(enabled: Boolean)
    private external fun nativeIsSpeedHack(): Boolean
    private external fun nativeSetSpeedMult(mult: Float)
    private external fun nativeGetSpeedMult(): Float
    private external fun nativeSetNoSpread(enabled: Boolean)
    private external fun nativeIsNoSpread(): Boolean
    private external fun nativeSetAutoFire(enabled: Boolean)
    private external fun nativeIsAutoFire(): Boolean
    private external fun nativeSetInfiniteAmmo(enabled: Boolean)
    private external fun nativeIsInfiniteAmmo(): Boolean
    private external fun nativeSetFOV(fov: Float)
    private external fun nativeGetFOV(): Float
    //endregion

    @JvmStatic
    fun start(context: Context) {
        // Library is loaded in init block; hook thread starts in JNI_OnLoad
        Log.i(TAG, "ModMenuLoader.start called")
    }
}
