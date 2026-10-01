package io.github.matiyaaa.fuse.desktop.input

import io.github.matiyaaa.fuse.desktop.system.DesktopOs

/** Reads controllers until closed. */
interface Gamepads : AutoCloseable {
    fun start()

    companion object {
        /**
         * Linux reads `/dev/input` directly; Windows and macOS use SDL, which only their builds carry
         * (so [SdlGamepads] is never loaded on Linux).
         */
        fun forOs(os: DesktopOs, sink: GamepadSink): Gamepads = when (os) {
            DesktopOs.LINUX -> object : Gamepads {
                private val reader = LinuxGamepads(sink)
                override fun start() = reader.start()
                override fun close() = reader.close()
            }
            DesktopOs.WINDOWS, DesktopOs.MACOS -> sdl(sink)
        }

        private fun sdl(sink: GamepadSink): Gamepads = object : Gamepads {
            private val reader = SdlGamepads(sink)
            override fun start() = reader.start()
            override fun close() = reader.close()
        }
    }
}
