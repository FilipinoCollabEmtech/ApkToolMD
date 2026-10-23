package com.fluxin.apktoolmd.mod

/**
 * A single source -> target remapping rule applied by [ShadowLoader].
 *
 * `from` is a package/class prefix (e.g. "com.game."). When a requested class
 * starts with `from`, the prefix is replaced by `to` (e.g. "com.mod.shadow.")
 * so a modded class such as "com.game.PlayerController" becomes
 * "com.mod.shadow.PlayerController" and can coexist with the original.
 *
 * When `isRegex` is true, `from` is treated as a regular expression and
 * `to` as the replacement string.
 */
data class RemapRule(
    val from: String,
    val to: String,
    val isRegex: Boolean = false
) {

    /** Return the remapped name, or null if this rule does not apply. */
    fun apply(className: String?): String? {
        return if (className == null || className.isBlank()) null else if (isRegex) {
            val replaced = from.toRegex().replace(className, to)
            if (replaced == className) null else replaced
        } else {
            if (className.startsWith(from)) to + className.substring(from.length) else null
        }
    }

    companion object {
        /** Parse a "from=to" line (regex lines start with "r:"). */
        fun parse(line: String): RemapRule? {
            val t = line.trim()
            if (t.isBlank() || t.startsWith("#")) return null
            var regex = false
            var body = t
            if (body.startsWith("r:")) {
                regex = true
                body = body.substring(2)
            }
            val eq = body.indexOf('=')
            if (eq <= 0) return null
            val from = body.substring(0, eq).trim()
            val to = body.substring(eq + 1).trim()
            return if (from.isBlank()) null else RemapRule(from, to, regex)
        }
    }
}
