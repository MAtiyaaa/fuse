package io.github.matiyaaa.fuse.launch.settings

import kotlin.test.Test
import kotlin.test.assertEquals

/** Supermodel.ini's values read as its FromINIFile reads them. */
class SupermodelIniTest {
    @Test
    fun quotesAndCommentsComeOffAsSupermodelTakesThemOff() {
        assertEquals("KEY_1,JOY1_BUTTON9", SupermodelIni.value("\"KEY_1,JOY1_BUTTON9\""))
        assertEquals("2", SupermodelIni.value("2 ; bilinear"))
        assertEquals("a;b", SupermodelIni.value("\"a;b\" ; a comment"), "a ; in quotes is the value's")
        assertEquals("", SupermodelIni.value(""))
        assertEquals("\"", SupermodelIni.value("\""), "one quote alone stays")
    }

    @Test
    fun aSwitchIsReadEveryWaySupermodelReadsOne() {
        assertEquals(listOf("true", "true", "true", "true"), listOf("true", "On", "YES", "1").map { SupermodelIni.switch(it) })
        assertEquals(listOf("false", "false", "false", "false"), listOf("False", "off", "no", "0").map { SupermodelIni.switch(it) })
        assertEquals("maybe", SupermodelIni.switch("maybe"))
        assertEquals(null, SupermodelIni.switch(null))
        assertEquals("\"KEY_A\"", SupermodelIni.text("KEY_A"))
    }
}
