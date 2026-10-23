package com.fluxin.apktoolmd.mod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemapRuleTest {

    @Test
    fun literalPrefixReplacesPackage() {
        val rule = RemapRule("com.game.", "com.mod.shadow.")
        assertEquals("com.mod.shadow.PlayerController", rule.apply("com.game.PlayerController"))
        assertEquals("com.mod.shadow.Man", rule.apply("com.game.Man"))
        // a class that does not start with the prefix is untouched
        assertNull(rule.apply("com.other.Game"))
        assertNull(rule.apply("android.app.Activity"))
    }

    @Test
    fun literalPrefixIsNotRegex() {
        // "." in a package name must be treated literally, not as "any char"
        val rule = RemapRule("com.game.", "com.mod.shadow.")
        assertEquals("com.mod.shadow.PlayerController", rule.apply("com.game.PlayerController"))
        assertNull(rule.apply("comXgameYPlayerController"))
    }

    @Test
    fun regexRuleApplies() {
        val rule = RemapRule("com\\.game.", "com.mod.shadow.", true)
        assertEquals("com.mod.shadow.PlayerController", rule.apply("com.game.PlayerController"))
        assertNull(rule.apply("com.other.Game"))
    }

    @Test
    fun parseHandlesLiteralAndRegex() {
        val literal = RemapRule.parse("com.game.=com.mod.shadow.")
        assertTrue(literal != null && literal.isRegex == false)
        assertEquals("com.mod.shadow.PlayerController", literal!!.apply("com.game.PlayerController"))

        val regex = RemapRule.parse("r:com\\.game.=com.mod.shadow.")
        assertTrue(regex != null && regex.isRegex == true)
        assertEquals("com.mod.shadow.PlayerController", regex!!.apply("com.game.PlayerController"))

        assertNull(RemapRule.parse(""))
        assertNull(RemapRule.parse("# comment"))
        assertNull(RemapRule.parse("noequalsign"))
        assertNull(RemapRule.parse("=target"))
    }

    @Test
    fun blankNameReturnsNull() {
        val rule = RemapRule("com.game.", "com.mod.shadow.")
        assertNull(rule.apply(""))
        assertNull(rule.apply("   "))
        assertNull(rule.apply(null))
    }
}
