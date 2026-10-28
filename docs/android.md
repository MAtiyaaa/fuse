# Fuse on Android: what works, and what can't

Fuse runs the same interface on Android as on computers, but Android keeps every app in a sandbox:
one app can't read another app's private folders, start a program it didn't install, or change a
system setting without asking. This page says, feature by feature, what Fuse 0.4.2 does on Android,
and why where it doesn't. "Works" means built and tested in Fuse's automated suites; nothing here
was tried on every Android device.

## Works the same on Android

| Feature | Notes |
| --- | --- |
| Library, Systems, Home, search, collections, For You and Spotlight | Worked out on the device from the library alone. |
| Fuse One: profiles, each person's saves kept on the device, offline PIN, local-only games | No host needed. Saves are switched where the emulator keeps them in a folder Fuse can reach (see below). |
| Fuse One with a household: Save Sync, Fuse Library, keys shared, artwork fetched once, trophy history | As a member of a household. |
| Transfer rules (Wi-Fi only, charging only, battery level, quiet hours) | Android's own battery saver can still pause Fuse; Settings, Fuse One says when and links to the fix. |
| RomM, Cartridge, Jellyfin and Fuse Player | Cartridge runs on Android and Linux only. |
| Streaming with Moonlight, per game or per system | Needs Moonlight for Android. |
| Haptics, the second screen (two-screen handhelds), screen calibration | The second screen goes dark with Standby. |
| Accessibility: colour palettes, right to left, screen reader labels, text size | Right to left follows the system's language. |
| ROM patches (IPS, UPS, BPS, xdelta, PPF) and game manuals (PDF, CBZ) | Manuals are drawn by Android itself. |
| GameBanana: finding and downloading a game's mods | Installing them depends on reaching the emulator's folder (below). |
| Phone Link and its permissions | |
| Fuse's own updates | A stopped download resumes and is checked before installing. |
| Performance Governor | Optional work pauses while a game runs. With the screen off, Fuse asks nothing of Fuse One's host, RomM or Jellyfin until it comes back, unless a transfer or an art search is going (those keep the device awake until they finish). |
| Refresh rate | Fuse asks for the screen's highest rate while you use it. Left alone with a moving background, it asks for about 60 Hz after ten seconds, where the screen can switch without a blink (Android 12 and later); never while a film plays. |

## Works where the emulator keeps its files in shared storage

Android lets Fuse into folders like `/storage/emulated/0/...` (with "All files" access, or a folder
granted once), never into another app's private `Android/data` or internal storage. Whether a
feature below works depends on where each emulator keeps that file, and on the emulator's settings:

| Feature | When it works |
| --- | --- |
| Save switching between profiles, Save Sync | The emulator's save folder is in shared storage, or chosen in the emulator to be (RetroArch, PPSSPP, DuckStation, Dolphin and others let you choose it). Eden on Android can be pointed at a save folder from its settings; Fuse names the setting. Where Fuse can't reach the folder, it says so rather than claiming the save is kept. |
| Emulator settings, cheats, texture packs and mods inside Fuse | Only where the emulator's config and content folders are in shared storage. On computers they are always reachable. |
| Bringing data over from another emulator of the family | Both emulators' data must be reachable; on Android they usually aren't. |

## Doesn't work on Android, and why

| Feature | Why |
| --- | --- |
| Steam integration, Steam games in the Library, Steam achievements | There is no Steam client on Android to read. Launchers that run Steam games on Android (GameNative, GameHub, Winlator) keep their data in their own private storage and offer no way for another app to read achievements. The documented Steam Web API can return achievements, but only for profiles whose game details are public, and Fuse never asks anyone to make a profile public. A game's global rarity is public and could be shown, but without the person's own unlocks it says little. |
| The Fuse Decky plugin | Decky runs inside Steam's Game Mode on Linux only. |
| Being a Fuse One host or backup host | A host must stay reachable and awake; Android stops background apps. A computer, a NAS or the Fuse One Host (Docker) hosts instead. |
| AnyPS5 | AnyPS5 makes PC programs (Linux, Windows, macOS). |
| Going back to an earlier Fuse, or an earlier emulator | Android refuses to install an older version of an app over a newer one without uninstalling it, which would delete the app's data. |
| Launch diagnostics from the emulator's output | Android doesn't give one app another app's output. Fuse can still tell an emulator that is missing or didn't start. |
| The emulator update rollback in the Store | Same as above: Android doesn't install an older version over a newer one. |
| Rumble on a controller from Fuse | Android gives apps the phone's own vibration; Fuse uses that. |
| Switching to HDR or variable refresh | Settings, Displays shows the HDR formats and refresh rates each display reports; Fuse doesn't switch the display's mode itself. |

Fuse never pretends a limit isn't there: where something can't work on this device, its setting
says why instead of offering a button that does nothing.
