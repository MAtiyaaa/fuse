package io.github.matiyaaa.fuse.desktop

/**
 * Fuse is a window: started where there is no screen (over SSH, from a service, a desktop that
 * hasn't started yet), it says so in one sentence and stops, instead of the toolkit's long error.
 */
internal object NoDisplay {
    const val EXIT_CODE = 3

    /** Why no window can open here, or null when one can. */
    fun why(env: (String) -> String? = System::getenv, headless: () -> Boolean = java.awt.GraphicsEnvironment::isHeadless, os: String = System.getProperty("os.name").orEmpty()): String? {
        if (os.lowercase().let { "linux" in it || "bsd" in it } && env("DISPLAY").isNullOrBlank() && env("WAYLAND_DISPLAY").isNullOrBlank()) {
            return "no display is set, DISPLAY and WAYLAND_DISPLAY are both empty"
        }
        if (headless()) return "Java reports no display here"
        return try {
            if (java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.isEmpty()) "no screen was found" else null
        } catch (e: Throwable) {
            if (isDisplayError(e)) e.message ?: "the display couldn't be reached" else null
        }
    }

    /** The toolkit's ways of saying it has no display. */
    fun isDisplayError(e: Throwable): Boolean = generateSequence(e) { it.cause }.any {
        it is java.awt.HeadlessException || it is java.awt.AWTError ||
            (it is InternalError && it.message.orEmpty().contains("display", ignoreCase = true)) ||
            it.message.orEmpty().let { m -> m.contains("Can't connect to X11", ignoreCase = true) || m.contains("cannot open display", ignoreCase = true) }
    }

    fun message(why: String): String =
        "Fuse needs a screen to open its window, and there isn't one here: $why. Start Fuse from your desktop or Game Mode."
}
