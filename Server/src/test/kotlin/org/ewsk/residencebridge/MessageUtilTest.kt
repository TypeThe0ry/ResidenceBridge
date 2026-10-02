package org.ewsk.residencebridge

import kotlin.test.Test
import kotlin.test.assertEquals

class MessageUtilTest {

    @Test
    fun `translates legacy ampersand codes`() {
        assertEquals("§aHello §lWorld & co", MessageUtil.color("&aHello &LWorld & co"))
    }

    @Test
    fun `translates hex colors`() {
        assertEquals("§x§f§f§0§0§0§0Red", MessageUtil.color("<#ff0000>Red"))
        assertEquals("§x§0§0§f§f§0§0Green", MessageUtil.color("&#00ff00Green"))
    }

    @Test
    fun `ignores a trailing ampersand`() {
        assertEquals("Tom &", MessageUtil.color("Tom &"))
    }
}
