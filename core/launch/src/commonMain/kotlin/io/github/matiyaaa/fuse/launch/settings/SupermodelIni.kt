package io.github.matiyaaa.fuse.launch.settings

/**
 * Supermodel.ini's values as Supermodel reads them (Src/Util/ConfigBuilders.cpp `FromINIFile`):
 * what follows a `;` outside quotes is a comment (`StripComment`), and one pair of quotes around a
 * value is taken off (`ParseAssignment`), as its own file writes its inputs
 * (`InputStart1 = "KEY_1,JOY1_BUTTON9"`). A switch is on as `true`, `on`, `yes` or `1`, off as
 * `false`, `off`, `no` or `0` (Src/Util/GenericValue.h `ParseBool`), whatever their case.
 */
object SupermodelIni {
    private val ON = setOf("true", "on", "yes", "1")
    private val OFF = setOf("false", "off", "no", "0")

    /** A value as written in the file ([raw], everything after `=`) as Supermodel reads it. */
    fun value(raw: String): String {
        var quoted = false
        var end = raw.length
        for ((i, c) in raw.withIndex()) {
            if (c == '"') quoted = !quoted
            if (!quoted && c == ';') {
                end = i
                break
            }
        }
        val v = raw.substring(0, end).trim()
        return if (v.length >= 2 && v.first() == '"' && v.last() == '"') v.substring(1, v.length - 1) else v
    }

    /** A switch's value as `true` or `false`, from any way Supermodel reads one; else as it is. */
    fun switch(value: String?): String? = when (value?.trim()?.lowercase()) {
        in ON -> "true"
        in OFF -> "false"
        else -> value
    }

    /** Text as Fuse writes it: in quotes, as Supermodel's own file writes its inputs, so a `;` in it stays. */
    fun text(value: String): String = "\"$value\""
}
