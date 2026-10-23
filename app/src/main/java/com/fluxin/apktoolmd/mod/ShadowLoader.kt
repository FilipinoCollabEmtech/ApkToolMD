package com.fluxin.apktoolmd.mod

import android.content.Context
import android.content.SharedPreferences
import dalvik.system.DexClassLoader
import java.io.File

const val SHADOW_PREFS = "apktoolmd_shadow"
const val KEY_SHADOW_PATH = "shadow_path"
const val KEY_REMAP_RULES = "shadow_remap_rules"

/**
 * A class loader that loads classes from a "shadow" DEX/APK overlay with
 * optional package/class remapping, falling back to the parent class loader
 * (the host app) for everything else.
 *
 * Resolution order for [loadClass]:
 *   1. Each remap rule's target name   -> shadow dex
 *   2. The raw requested name          -> shadow dex
 *   3. Parent class loader             -> host app
 *
 * This lets a mod ship modified classes under a different package prefix so
 * they coexist with the original app without class-name collisions — the
 * "shadow + remap" mod architecture.
 */
class ShadowLoader(
    context: Context,
    val shadowFile: File,
    val remapRules: List<RemapRule> = emptyList(),
    parent: ClassLoader? = null
) : DexClassLoader(
    shadowFile.absolutePath,
    File(context.cacheDir, "shadow_dex_opt").apply { mkdirs() }.absolutePath,
    null,
    parent
) {

    /** Save the shadow location + rules so they survive process death. */
    fun persist(context: Context) {
        val prefs: SharedPreferences = context.getSharedPreferences(SHADOW_PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_SHADOW_PATH, shadowFile.absolutePath)
            .putString(KEY_REMAP_RULES, remapRules.joinToString("\n") { "${it.from}=${it.to}" })
            .apply()
    }

    companion object {
        private const val PREFS = SHADOW_PREFS

        /** Restore the previously persisted shadow loader (or null if none). */
        fun restore(context: Context): ShadowLoader? {
            val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val path = prefs.getString(KEY_SHADOW_PATH, null) ?: return null
            val file = File(path)
            if (!file.exists()) return null
            val rules = (prefs.getString(KEY_REMAP_RULES, "") ?: "")
                .lines()
                .mapNotNull { RemapRule.parse(it) }
            return ShadowLoader(context, file, rules, context.classLoader)
        }

        /** Clear the persisted shadow. */
        fun clear(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }

    /** Load a class, applying remap rules before searching the shadow dex. */
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        // 1. remapped shadow names first
        for (rule in remapRules) {
            rule.apply(name)?.let { remapped ->
                try {
                    return findClass(remapped).also { if (resolve) resolveClass(it) }
                } catch (_: ClassNotFoundException) {
                    // try the next rule
                }
            }
        }
        // 2. raw name in the shadow dex
        return try {
            findClass(name).also { if (resolve) resolveClass(it) }
        } catch (_: ClassNotFoundException) {
            // 3. fall back to the parent (host app) class loader
            super.loadClass(name, resolve)
        }
    }

    /** Try to load a class by name; null if it cannot be resolved. */
    fun tryLoad(name: String): Class<*>? {
        return try { loadClass(name, true) } catch (e: Exception) {
            null
        }
    }

    /** Instantiate a remapped shadow class by name (no-arg constructor). */
    fun newInstance(className: String): Any? {
        return try { tryLoad(className)?.let { cls ->
            val ctor = cls.getDeclaredConstructor()
            ctor.isAccessible = true
            ctor.newInstance()
        } } catch (e: Exception) { null }
    }

    /** Call a static method on a remapped shadow class. */
    fun callStatic(className: String, methodName: String, vararg args: Any): Any? {
        return try {
            val cls = tryLoad(className) ?: return null
            val paramTypes = args.map { it.javaClass }.toTypedArray()
            val method = cls.getDeclaredMethod(methodName, *paramTypes)
            method.isAccessible = true
            method.invoke(null, *args)
        } catch (e: Exception) { null }
    }
}
