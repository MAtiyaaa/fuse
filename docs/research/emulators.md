# Fuse research: verified launch mechanisms for Android emulators and PC-game launchers

Research date: 2026-09-30. I pulled everything live from the upstream repositories (ES-DE GitLab, emulator source on GitHub and Forgejo).

## 0. Key takeaways for Fuse

1. **ES-DE is the best single source, and its config is MIT-licensed.** The Android `es_find_rules.xml` and `es_systems.xml` are in the public repo under MIT (see §1).
   - I used ES-DE `master` at commit `e0ae6ba77d` (2026-09-16).
   - The latest release tag is `v3.4.1` (2026-04-10).
   - Master adds ARMSX1, ARMSX3, BachataS4, EmuCoreC/V/X, NetherSX2(+Turnip variants), SeedlessDS, Starboard, WatermelonDS, WinNative, XenDroid and Xenra.
   - Master renames the `AETHERSX2` rule to `NETHERSX2`.
2. **ES-DE passes a ROM in one of three ways** (§2): an absolute path (`%ROM%`), a SAF tree-document URI (`%ROMSAF%`), or a FileProvider URI (`%ROMPROVIDER%`). Each emulator accepts only a specific one.
   - I confirmed the real `%ROMSAF%` shape from an ES-DE log in Azahar issue #1484:
     `content://com.android.externalstorage.documents/tree/primary%3AROMs%2Fn3ds/document/primary%3AROMs%2Fn3ds%2FAnimal%20Crossing%20New%20Leaf.cci`
   - The tree part is the **per-system folder**. That is why emulators must be granted access to `ROMs/<system>` and not to `ROMs/`.
3. **FileProvider needs file ownership.** ES-DE's FAQ says "you can't provide access to files you don't own, even if you have read/write access to them using scoped storage permissions".
   - ES-DE therefore holds `MANAGE_EXTERNAL_STORAGE`.
   - Fuse needs the same if it wants `%ROMPROVIDER%`-style launches (Eden, Skyline, the EX+ Alpha emulators, and others).
4. **Launching by an ID stored in a text file is a common pattern.** ES-DE's `%INJECT%` reads a small file and passes its content:
   - `.steam`/`.epic`/`.gog`/`.amazon`/`.pcgame` hold a numeric ID (GameNative, GameHub Lite).
   - `.psvita` holds a Vita title ID (Vita3K).
   - `.ps3` holds a PS3 serial (aPS3e, ARMSX3).
   - `.app` holds `package[/activity]` (native Android apps).
5. **Winlator frontend support means Winlator Cmod, or forks built on its code.**
   - Mainline Winlator (brunodev85) declares `XServerDisplayActivity` with `android:exported="false"`. Frontends cannot launch games directly: **LIMITED: app launch only**.
   - Cmod-derived builds take `-e shortcut_path <abs path to .desktop>` on an exported `XServerDisplayActivity`.
6. **Spoofed package names are shared by unrelated apps.** `com.ludashi.benchmark`, `com.tencent.ig`, `com.antutu.ABenchMark` and `com.miHoYo.Yuanshen` are each used by several different apps (Winlator Cmod, WinNative, Bannerlator, GameHub Lite, Eden, Citron forks).
   - Always resolve **package plus activity class**, as ES-DE does with `checkEmulatorInstalled(package, activity)`.
   - Never trust the package name alone.

---

## 1. Sources and licensing

| Item | URL | License / status |
|---|---|---|
| ES-DE find rules (Android) | https://gitlab.com/es-de/emulationstation-de/-/raw/master/resources/systems/android/es_find_rules.xml | MIT |
| ES-DE systems (Android) | https://gitlab.com/es-de/emulationstation-de/-/raw/master/resources/systems/android/es_systems.xml | MIT |
| ES-DE import rules (Android) | https://gitlab.com/es-de/emulationstation-de/-/raw/master/resources/systems/android/es_import_rules.xml | MIT |
| Stable comparison | https://gitlab.com/es-de/emulationstation-de/-/raw/v3.4.1/resources/systems/android/es_systems.xml | MIT |
| ANDROID.md | https://gitlab.com/es-de/emulationstation-de/-/blob/master/ANDROID.md | MIT |
| INSTALL.md, section "es_find_rules.xml and es_systems.xml on Android" (variable semantics) | https://gitlab.com/es-de/emulationstation-de/-/blob/master/INSTALL.md | MIT |
| USERGUIDE.md: Directories interpreted as files, PlayStation 3, PS Vita, Steam, Game importer | https://gitlab.com/es-de/emulationstation-de/-/blob/master/USERGUIDE.md | MIT |
| FAQ-ANDROID.md | https://gitlab.com/es-de/emulationstation-de/-/blob/master/FAQ-ANDROID.md | MIT |
| ES-DE LICENSE | https://gitlab.com/es-de/emulationstation-de/-/blob/master/LICENSE | **MIT** |
| ES-DE C++ intent parsing | https://gitlab.com/es-de/emulationstation-de/-/blob/master/es-app/src/FileData.cpp | MIT |

**ES-DE license text.** MIT. Copyright (c) 2024-2026 Northwestern Software AB, (c) 2020-2024 Leon Styhre, (c) 2014 Alec Lofquist.
- Package and activity names are factual interoperability data.
- If you copy the XML files or large parts of them, keep the MIT notice. Attribution in your docs is good practice anyway.
- Caveat: FAQ-ANDROID says the Android APK is paid and that "there is some Android-specific code that is copyrighted and closed source".
  - The Java side that builds the intent (`Utils::Platform::Android::launchGame`) is **not** public.
  - `FileData.cpp` only shows how the command string is parsed into action, category, MIME type, data, typed extras and flags.

**Other sources and their licenses:**
- GameNative: GPL-3.0.
- WinNative: GPL-3.0.
- Winlator Cmod (coffincolors): MIT.
- Daijishō (TapiocaFox/Daijishou): closed-source app. The GitHub asset repo has **no LICENSE file**, so use its platform JSONs only as reference and do not copy them wholesale.
- GameHub Lite patches repo: no LICENSE file.
- GlazedBelmont community ES-DE configs: no LICENSE file.

**Confidence labels used below:**
- **V-ESDE**: present in ES-DE master config.
- **V-SRC**: confirmed in the emulator's own source (manifest and/or intent-parsing code).
- **X-DJ**: independently matches Daijishō's published player JSON.
- **UNVERIFIED**: I could not confirm it from a primary source.

---

## 2. ES-DE Android launch model (what the variables mean)

Source: INSTALL.md, section "es_find_rules.xml and es_systems.xml on Android", plus `FileData.cpp`.

**Find rules.** Each rule is `<emulator name="X"><rule type="androidpackage"><entry>package/activity</entry>…`.
- A leading `.` on the activity is relative to the package.
- Entries are tried in order, and the first installed one wins.
- The activity is optional, but "usually a good idea to explicitly set".

**Command variables** (`<command>` in es_systems.xml):

| Variable | Meaning (ES-DE docs) |
|---|---|
| `%EMULATOR_X%` | Resolves X through the find rules to package/activity (an explicit component) |
| `%ACTION%=` | `Intent.setAction()` |
| `%CATEGORY%=` | `addCategory()`. It is parsed in FileData.cpp but not listed in the docs; used by the Dolphin and PPSSPP entries |
| `%MIMETYPE%=` | Explicit MIME type; the default is `application/octet-stream` |
| `%DATA%=` | `setData()`. Only one per intent. Can also be `%DATA%=%INJECT%=%ROM%` (file content becomes the data string) |
| `%EXTRA_name%=` | String extra. Inside the value you may use `%BASENAME%`, `%GAMEDIRRAW%`, `%ROMPATHRAW%`, `%ROMRAW%`, `%ROMRAWWIN%`. ES-DE collapses `//` to `/`, so escape a real double slash as `\/\/` |
| `%EXTRAARRAY_name%=a,b,c` | `String[]` extra; commas are escaped as `\,` |
| `%EXTRAINTEGER_name%=` | int extra |
| `%EXTRABOOL_name%=` | boolean extra (`true`/`1`/`false`/`0`) |
| `%ACTIVITY_CLEAR_TASK%`, `%ACTIVITY_CLEAR_TOP%`, `%ACTIVITY_NO_HISTORY%` | `FLAG_ACTIVITY_CLEAR_TASK`, `FLAG_ACTIVITY_CLEAR_TOP`, `FLAG_ACTIVITY_NO_HISTORY` |
| `%INTERNALDATA%` / `%EXTERNALDATA%` | `/data/user/<uid>` and `/storage/emulated/<uid>` (for multi-user setups) |
| `%ANDROIDPACKAGE%` | Package of the resolved emulator, used in RetroArch core/config paths |
| `%INJECT%=file` | Injects the file's content (at most 4096 bytes). Commonly `%INJECT%=%ROM%` or `%INJECT%=%BASENAME%.ps3` |
| `%ANDROIDAPP%=%FILEINJECT%` | Launches a native app. The file contains `package` or `package/activity` |

**The three ways to pass a ROM:**

| Token | What the emulator receives | Where it may be used | Notes |
|---|---|---|---|
| `%ROM%` | Absolute path, e.g. `/storage/emulated/0/ROMs/psx/x.chd` | data or extras | "Traditional". The emulator needs All-files access or a legacy storage model (RetroArch, ePSXe, Citra MMJ, Winlator, aPS3e directory mode) |
| `%ROMSAF%` | SAF document URI **under a tree**: `content://com.android.externalstorage.documents/tree/<vol>%3AROMs%2F<system>/document/<vol>%3AROMs%2F<system>%2F<file>` | data or extras | The emulator must already hold a persisted tree grant for **that system folder**. `<vol>` is `primary` for internal storage or the SD card's volume UUID. Shape confirmed from an ES-DE log in https://github.com/azahar-emu/azahar/issues/1484 |
| `%ROMPROVIDER%` | FileProvider `content://` URI with a temporary read grant | **data only** | Single file only (not usable for .cue/.bin sets). Needs the frontend to own the file, hence MANAGE_EXTERNAL_STORAGE |

**Emulators ES-DE configures for FileProvider** (ANDROID.md list):
- 2600.emu, DroidArcadia, EKA2L1, FPseNG, FPse (these two still need scoped storage set up), GBA.emu, GBC.emu, Infinity, J2ME Loader, JL-Mod, Lynx.emu, MAME4droid (Current and legacy), MD.emu, NES.emu, NGP.emu, Panda3DS, PCE.emu, Ruffle, SkyEmu, Skyline, Swan.emu, SWF Player, Virtual Virtual Boy.
- Eden also uses `%ROMPROVIDER%` in es_systems.xml even though the doc list hasn't been updated.

**Directories interpreted as files** (USERGUIDE):
- If a directory is renamed to carry a supported extension, ES-DE shows it as one entry. There are two behaviours:
  - (a) If the directory contains a file with the same name (e.g. `Jet Grind Radio.cue/Jet Grind Radio.cue`, or `Final Fantasy VII.m3u/Final Fantasy VII.m3u`), **that inner file** is passed.
  - (b) Otherwise the **directory itself** is passed. This is the case for PS3 (`ROMs/ps3/Gran Turismo 5.ps3/` containing the disc structure) with the "Directory" emulator entries.
- The directory form also works with `%INJECT%=%ROM%`.
- Recommended layout: Dreamcast and PSX multi-disc go in a directory named like the `.m3u`/`.cue`.
- On Android, Xbox 360 XBLA files are extensionless; es_systems lists `.` as an extension for xbox360.

**Native apps** (the `androidapps`, `androidgames`, `emulators`, `epic`, `n64` and `ports` systems):
- Each game is a `.app` text file containing `com.pkg.name` or `com.pkg.name/com.pkg.Activity`.
- Command: `%ANDROIDAPP%=%FILEINJECT%`.
- ES-DE's "Game importer" (`es_import_rules.xml`, rule type `androidpackage`, extension `.app`) creates these files and can import the icon, banner or logo.
- Carriage returns are stripped, and the content is split on `/` into package and activity (`FileData.cpp`).
- The `n64` system uses this for native recompilation ports ("Native port" command). `epic` uses it for native Android Epic games.

---

## 3. Per-emulator tables

Terminology in the tables:
- **PATH** = `%ROM%` absolute path. **SAF** = `%ROMSAF%`. **FP** = `%ROMPROVIDER%`.
- **CT / CTop** = CLEAR_TASK / CLEAR_TOP flags.
- "Dir" = folder or directory launch supported.
- All components are exported, so explicit-component intents work. In most emulators the action string does not affect resolution; it matters only where the app's code checks it (called out below).

### 3.1 RetroArch (all libretro cores)

| Field | Value |
|---|---|
| Packages | `com.retroarch.aarch64` (website 64-bit), `com.retroarch.ra32` (website 32-bit), `com.retroarch` (Play/F-Droid/Galaxy). Order per ES-DE; flavors confirmed in `pkg/android/phoenix/build.gradle` (`normal`, `aarch64` → `.aarch64`, `ra32` → `.ra32`, namespace `com.retroarch`) |
| Activity | `com.retroarch.browser.retroactivity.RetroActivityFuture`: exported, `launchMode="singleInstance"`. The launcher entry is an activity-alias `com.retroarch.browser.mainmenu.MainMenuActivity` |
| Action | none |
| ES-DE command | `%EMULATOR_RETROARCH% %EXTRA_CONFIGFILE%=%EXTERNALDATA%/Android/data/%ANDROIDPACKAGE%/files/retroarch.cfg %EXTRA_LIBRETRO%=%INTERNALDATA%/%ANDROIDPACKAGE%/cores/<core>_libretro_android.so %EXTRA_ROM%=%ROM%` |
| Extras (String) | `ROM` = absolute ROM path<br>`LIBRETRO` = absolute core path, e.g. `/data/user/0/com.retroarch.aarch64/cores/mgba_libretro_android.so`<br>`CONFIGFILE` = `/storage/emulated/0/Android/data/<pkg>/files/retroarch.cfg`<br>`QUITFOCUS` = presence only (`hasExtra`), which makes RA call `System.exit(0)` in `onStop`<br>`REFRESH` = preferred refresh rate as an integer string<br>The native code also reads `IME`, `USED`, `SDCARD`, `APK`, `EXTERNAL`, `DATADIR`, `AUDIO_RATE`, `AUDIO_FRAMES`, `VERSIONCODE`; RA's own launcher sets these, so frontends should not |
| ROM passing | PATH only (legacy storage) |
| Dir | No (single file, including .m3u/.cue inside a directory-as-file) |
| Caveats | If `onNewIntent` receives a different ROM or LIBRETRO, RA restarts itself (`NEW_TASK\|CLEAR_TASK` then `System.exit(0)`). ES-DE says the Play build is problematic and recommends the website 64-bit build. A missing core or BIOS gives a black screen or hang. ES-DE does not pass QUITFOCUS |
| Sources | https://github.com/libretro/RetroArch/blob/master/pkg/android/phoenix/src/com/retroarch/browser/retroactivity/RetroActivityFuture.java<br>https://github.com/libretro/RetroArch/blob/master/frontend/drivers/platform_unix.c<br>https://github.com/libretro/RetroArch/blob/master/pkg/android/phoenix/AndroidManifest.xml |
| Confidence | **V-ESDE + V-SRC** |

### 3.2 PlayStation 1

| Emulator | Package → Activity | Action | ROM / extras | Flags | Dir | Notes / confidence |
|---|---|---|---|---|---|---|
| DuckStation | `com.github.stenzek.duckstation` → `.EmulationActivity` | none | `bootPath`=SAF (String), `resumeState`=false (boolean) | CT, CTop | No (use .m3u or directory-as-file) | Android source is **not public**; the upstream README only links the Play Store. Needs a scoped-storage grant on `ROMs/psx`. **V-ESDE + X-DJ.** Daijishō also has a legacy variant passing `bootPath` as a file path |
| ePSXe | `com.epsxe.ePSXe` → `.ePSXe` | `MAIN` | `com.epsxe.ePSXe.isoName`=PATH | none | No | **V-ESDE + X-DJ** |
| FPseNG / FPse | `com.emulator.fpse64` / `com.emulator.fpse` → `.Main` | `VIEW` | data=FP | none | No | Also needs scoped storage in the emulator; no .chd support. **V-ESDE** |
| ARMSX1 | ES-DE: `com.nanodata.armsx` → `com.armsx2.Main` | none | data=SAF | none | No | **Conflict:** the public ARMSX1 repo manifest (github.com/ARMSX2/ARMSX1 `android/app/src/main/AndroidManifest.xml`) only declares `com.nanodata.armsx.EmulatorActivity` (MAIN and VIEW). Daijishō also uses `com.armsx2.Main`, so release APKs may differ from repo HEAD. **V-ESDE, source disagrees; test on device** |

### 3.3 PlayStation 2

| Emulator | Package → Activity | Action | ROM / extras | Flags | Notes / confidence |
|---|---|---|---|---|---|
| NetherSX2 / AetherSX2 | `xyz.aethersx2.android` → `.EmulationActivity` | `MAIN` | `bootPath`=SAF | CT, CTop | Closed source. ES-DE recommends the patched NetherSX2 `15210-v1.5-4248-noads.apk` (patch: https://github.com/Trixarian/NetherSX2-patch). The Play AetherSX2 "probably can't be used". **V-ESDE + X-DJ** |
| NetherSX2-Turnip | `xyz.aethersx2.tturnip` → `xyz.aethersx2.android.EmulationActivity` | `MAIN` | `bootPath`=SAF | CT, CTop | **V-ESDE + X-DJ** |
| NetherSX2-Turnip Classic | `xyz.aethersx2.cturnip` → `xyz.aethersx2.android.EmulationActivity` | `MAIN` | `bootPath`=SAF | CT, CTop | **V-ESDE + X-DJ** |
| ARMSX2 | `come.nanodata.armsx2` (the "come" typo is real) → `com.armsx2.MainActivity`<br>`com.armsx2` → `.MainActivity`<br>`com.armsx2.nightly` → `com.armsx2.MainActivity`<br>`come.nanodata.armsx2` → `kr.co.iefriends.pcsx2.MainActivity`<br>`come.nanodata.armsx2.debug` → `kr.co.iefriends.pcsx2.MainActivity` | `VIEW` | data=SAF | none | Daijishō instead uses `com.armsx2.Main` and `kr.co.iefriends.pcsx2.activities.MainActivity`. The sibling ARMSX3 source exports **both** `com.armsx2.Main` and `com.armsx2.MainActivity`, so both likely exist. **V-ESDE; source repo not reachable** |
| EmuCoreX | `com.sbro.emucorex` → `.MainActivity` | `VIEW` | data=SAF | CT | **V-ESDE** only |
| Play! | `com.virtualapplications.play` → `.MainActivity` | `VIEW` | data=SAF | none | **V-ESDE** |

### 3.4 PSP: PPSSPP

| Field | Value |
|---|---|
| Packages → activity | `org.ppsspp.ppssppgold` → `org.ppsspp.ppsspp.PpssppActivity`; `org.ppsspp.ppsspp` → `.PpssppActivity` (exported, `singleInstance`) |
| ES-DE command | `%ACTION%=android.intent.action.VIEW %CATEGORY%=android.intent.category.DEFAULT %DATA%=%ROMSAF%` |
| What PPSSPP accepts (source) | `intent.getData()` (a file or `content://` URI, as a string) **or** the String extra `org.ppsspp.ppsspp.Shortcuts` (a path). There is also a debug args extra `org.ppsspp.ppsspp.Args`. `onNewIntent` while running forwards the new value to native code as `shortcutParam` |
| Dir | No |
| Caveats | ES-DE: press **Browse** in PPSSPP when granting scoped storage to the psp folder, or launches fail |
| Sources | https://github.com/hrydgard/ppsspp/blob/master/android/src/org/ppsspp/ppsspp/PpssppActivity.java (`parseIntent`)<br>https://github.com/hrydgard/ppsspp/blob/master/android/AndroidManifest.xml |
| Confidence | **V-ESDE + V-SRC** |

### 3.5 GameCube / Wii: Dolphin and forks

| Emulator | Package → Activity | Action / category | ROM / extras | Notes / confidence |
|---|---|---|---|---|
| Dolphin (official) | `org.dolphinemu.dolphinemu` → `.ui.main.TvMainActivity` (ES-DE); Daijishō uses `.ui.main.MainActivity` | `MAIN` + category `LEANBACK_LAUNCHER` | `AutoStartFile`=SAF (String) | See the input-priority list below. **V-ESDE + V-SRC + X-DJ** |
| Dolphin MMJR | `org.mm.jr` → `org.dolphinemu.dolphinemu.ui.main.MainActivity` | `VIEW` | `AutoStartFile`=SAF | ES-DE supports only `Dolphin.MMJR.v11505.apk`. **V-ESDE** |
| Dolphin MMJR2 | `org.dolphinemu.mmjr` → `org.dolphinemu.dolphinemu.ui.main.MainActivity` | `VIEW` | `AutoStartFile`=SAF | `MMJR.v2.0-17878.apk` (archive.org). **V-ESDE + X-DJ** |

What `StartupHandler.kt` (the source) accepts, in priority order:
1. `ClipData` URIs (multi-disc).
2. `intent.data` (a single content URI).
3. `String[]` extra `AutoStartFiles`.
4. String extra `AutoStartFile`.

Scoped-storage content URIs are preferred over raw paths.

- Dir: No. For multi-disc, Fuse could use ClipData or `AutoStartFiles`; ES-DE doesn't.
- Source: https://github.com/dolphin-emu/dolphin/blob/master/Source/Android/app/src/main/java/org/dolphinemu/dolphinemu/utils/StartupHandler.kt

### 3.6 Nintendo DS

| Emulator | Package → Activity | Action | ROM / extras | Flags | Notes / confidence |
|---|---|---|---|---|---|
| melonDS | `me.magnum.melonds` → `.ui.emulator.EmulatorActivity`; fork `me.magnum.melondualds` → `me.magnum.melonds.ui.emulator.EmulatorActivity` | `me.magnum.melonds.LAUNCH_ROM` | `uri`=SAF (String) | none | Source `LaunchArgs.fromIntent`: parcelable `rom`, then `intent.data`, then String `PATH` (file path), then `uri` (String or Uri). The manifest filter action is `${applicationId}.LAUNCH_ROM`, but the code does not check the action. **V-ESDE + V-SRC + X-DJ** |
| melonDS Nightly | `me.magnum.melonds.nightly` → `me.magnum.melonds.ui.emulator.EmulatorActivity` | `me.magnum.melonds.nightly.LAUNCH_ROM` | `uri`=SAF | none | **V-ESDE + X-DJ** |
| WatermelonDS | `me.magnum.melondualds` (same activity) | `me.magnum.melonds.LAUNCH_ROM` | `uri`=SAF | none | **V-ESDE** |
| DraStic | `com.dsemu.drastic` → `.DraSticActivity` | none | data=SAF | CT, CTop | Closed; **no zipped ROMs**; removed from Play. **V-ESDE + X-DJ** |
| NooDS | `com.hydra.noods` → `.FileBrowser` | none | `LaunchPath`=PATH | CT, CTop | Only the GitHub build launches from frontends; no zip support. **V-ESDE** |
| SeedlessDS | `com.seedlessds.app` → `.LaunchGame` | none | `rom_path`=PATH | none | **V-ESDE** |
| SkyEmu | `com.sky.SkyEmu` → `.EnhancedNativeActivity` | `VIEW` | data=FP | CT, CTop | **V-ESDE** |

melonDS sources:
- https://github.com/rafaelvcaetano/melonDS-android/blob/HEAD/app/src/main/java/me/magnum/melonds/ui/emulator/model/LaunchArgs.kt
- https://github.com/rafaelvcaetano/melonDS-android/blob/HEAD/app/src/main/AndroidManifest.xml

### 3.7 Nintendo 3DS

ES-DE currently lists Azahar, AzaharPlus, Citra, Citra Canary, Citra MMJ, Mandarine, Lime3DS and Panda3DS. **Borked3DS is not listed** in ES-DE master or v3.4.1.

| Emulator | Package → Activity | Action | ROM | Flags | Notes / confidence |
|---|---|---|---|---|---|
| Azahar | `org.azahar_emu.azahar` → `org.citra.citra_emu.activities.EmulationActivity` | none (the manifest filter is VIEW, `content`, `application/octet-stream`) | data=SAF | CT, CTop | See the source note below. **V-ESDE + V-SRC + X-DJ** |
| Azahar (Play build) | `io.github.lime3ds.android` → `org.citra.citra_emu.activities.EmulationActivity` | none | data=SAF | CT, CTop | The `googlePlay` flavor keeps the old Lime3DS applicationId (`build.gradle.kts`). **V-SRC** |
| AzaharPlus | `io.github.azaharplus.android` → `org.citra.citra_emu.activities.EmulationActivity` | none | data=SAF | CT, CTop | Use the APK marked `coexists_with_azahar`. **V-ESDE + X-DJ** |
| Citra (PabloMK7 fork) | `org.citra.citra_emu` → `.activities.EmulationActivity` (fallback `.ui.main.MainActivity`) | none | data=SAF | CT, CTop | **V-ESDE + X-DJ** |
| Citra Canary | `org.citra.citra_emu.canary` → `org.citra.citra_emu.activities.EmulationActivity` | none | data=SAF | CT, CTop | **V-ESDE + X-DJ** |
| Citra MMJ | `org.citra.emu` → `.ui.EmulationActivity`; spoofed `com.antutu.ABenchMark` → `org.citra.emu.ui.EmulationActivity` | none | `GamePath`=PATH | none | **V-ESDE + X-DJ** |
| Mandarine | `io.github.mandarine3ds.mandarine` → `.activities.EmulationActivity` | none | data=SAF | CT, CTop | **V-ESDE + X-DJ** |
| Lime3DS (legacy) | `io.github.lime3ds.android` → `.activities.EmulationActivity` | none | data=SAF | CT, CTop | Discontinued and merged into Azahar. **V-ESDE** |
| Panda3DS (pandroid) | `com.panda3ds.pandroid` → `.app.MainActivity` | none | data=FP | none | ES-DE: "no way to run a game directly"; the emulator GUI opens instead. **V-ESDE** |

Azahar source note (`EmulationFragment.kt`): reads `intent.data`, falling back to the legacy String extra `SelectedGame`. Non-Play builds open the URI as a file descriptor (`fd://`).
- If the emulator has no grant, it fails with "Permission Denial … requires that you obtain access using ACTION_OPEN_DOCUMENT" (issue #1484).
- Sources: https://github.com/azahar-emu/azahar/blob/master/src/android/app/src/main/java/org/citra/citra_emu/fragments/EmulationFragment.kt and https://github.com/azahar-emu/azahar/blob/master/src/android/app/build.gradle.kts

### 3.8 Nintendo Switch

**Listed in ES-DE master:** Eden, Eden Nightly, Kenji-NX, Skyline.
- v3.4.0 had only Kenji-NX and Skyline; Eden was added by v3.4.1.
- **Not listed:** Citron, Sudachi, Yuzu, Suyu, Strato, Torzu, Benji-SC.
- Extensions: `.nca .nro .nso .nsp .xci`.
- **No directory launch** in ES-DE for Switch. `%ROMPROVIDER%` is single-file.
- ES-DE has no mechanism to pass updates or DLC; they must be installed inside the emulator.

| Emulator | Package → Activity | Action | ROM / extras | Notes / confidence |
|---|---|---|---|---|
| Eden | `dev.eden.eden_emulator`, `dev.legacy.eden_emulator`, `com.miHoYo.Yuanshen` (spoof flavor) → `org.yuzu.yuzu_emu.activities.EmulationActivity` | ES-DE: `android.nfc.action.TECH_DISCOVERED` | data=FP | Details below. **V-ESDE + V-SRC** |
| Eden Nightly | `dev.eden.eden_emulator.nightly`, `dev.legacy.eden_emulator.nightly`, `com.miHoYo.Yuanshen.nightly` → same activity | `TECH_DISCOVERED` | data=FP | Same as Eden. **V-ESDE + V-SRC** |
| Kenji-NX | `org.kenjinx.android` → `.MainActivity` | `org.kenjinx.android.LAUNCH_GAME` | `bootPath`=SAF | Source host git.ryujinx.app was unreachable (HTTP 503 / connection reset). Also cross-checked by a retrobios note citing `src/KenjinxAndroid/app/src/main/AndroidManifest.xml`. **V-ESDE + X-DJ** |
| Skyline | `skyline.emu` → `emu.skyline.EmulationActivity` | `VIEW` | data=FP | Discontinued; last build on archive.org. **V-ESDE** |

Eden details from source:
- Exported filters: `TECH_DISCOVERED` (mime `application/octet-stream`), `VIEW` (`content`, `application/octet-stream`) and `dev.eden.eden_emulator.LAUNCH_WITH_CUSTOM_CONFIG`.
- `EmulationFragment` loads `intent.data` whenever the URI extension is in `{xci, nsp, nca, nro}`. So `VIEW` works equally well; the action is irrelevant except for the custom-config action.
- **Title-ID launch:** action `dev.eden.eden_emulator.LAUNCH_WITH_CUSTOM_CONFIG` with extras `title_id` (String) and optional `custom_settings` (String). The game is found in Eden's library by title ID (the EmuReady integration).
- `onNewIntent` with new data swaps the ROM live.

Eden sources:
- https://git.eden-emu.dev/eden-emu/eden/src/branch/master/src/android/app/src/main/AndroidManifest.xml
- `.../java/org/yuzu/yuzu_emu/fragments/EmulationFragment.kt`
- `.../utils/CustomSettingsHandler.kt`
- `.../model/Game.kt`
- `src/android/app/build.gradle.kts`

**Unlisted forks (secondary sources only):**
- Citron: `org.citron.citron_emu` → `org.citron.citron_emu.activities.EmulationActivity`. Same yuzu-style filters (TECH_DISCOVERED / VIEW content octet-stream).
  - Manifest from the GitHub mirror https://github.com/citron-neo/emulator (official hosting is off-GitHub).
  - Community ES-DE config also lists `org.citron.citron_emu.ea`, `com.antutu.ABenchMark` and `com.miHoYo.Yuanshen` builds.
  - **UNVERIFIED-ish** (mirror source plus community config).
- Sudachi: `org.sudachi.sudachi_emu` (applicationId in mirrors p-yukusai/sudachi-emu and Synoptikon/Sudachi) → `org.sudachi.sudachi_emu.activities.EmulationActivity`. **UNVERIFIED** (community).
- Yuzu: `org.yuzu.yuzu_emu` / `.ea` → `org.yuzu.yuzu_emu.activities.EmulationActivity`. Discontinued. **UNVERIFIED** (community).
- Suyu: `org.suyu.suyu_emu` or `dev.suyu.suyu_emu` → `…activities.EmulationActivity`. **UNVERIFIED**.
- Benji-SC (Kenji-NX fork): `org.benjisc.android` → `org.kenjinx.android.MainActivity`, same LAUNCH_GAME and `bootPath`. **UNVERIFIED** (Daijishō plus community).
- Strato (Skyline fork): `org.stratoemu.strato` → `org.stratoemu.strato.EmulationActivity`, exported, VIEW `content://` `.nro` filters. Manifest confirmed at https://github.com/strato-emu/strato/blob/master/app/src/main/AndroidManifest.xml. Launch behaviour is **UNVERIFIED**; expect Skyline-style `VIEW` with an FP URI.
- Community configs (all use `TECH_DISCOVERED` with `%ROMPROVIDER%` for the yuzu forks): https://github.com/GlazedBelmont/es-de-android-custom-systems. No license; reference only.

### 3.9 PS Vita

| Emulator | Package → Activity | Action | Extras | Notes / confidence |
|---|---|---|---|---|
| Vita3K | `org.vita3k.emulator` → `.Emulator` (exported, singleTop); fork `org.vita3k.emulator.ikhoeyZX` → `org.vita3k.emulator.Emulator` | none | `AppStartParameters` = `String[]{"-r", "<TITLEID>"}` (ES-DE: `%EXTRAARRAY_AppStartParameters%=-r,%INJECT%=%BASENAME%.psvita`) | Details below. **V-ESDE + V-SRC** |
| EmuCoreV | `com.sbro.emucorev` → `.core.vita.Emulator` | none | same `AppStartParameters` | **V-ESDE** |

Vita3K details:
- The source (`Emulator.getArguments()`) also accepts a newer String extra `title_id`, which it maps to `-r <id>`.
- The game must be installed in Vita3K first. The `.psvita` file contains only the title ID (e.g. `PCSF00007`).
- Bulk `.psvita` files: https://github.com/Jetup13/Retroid-Pocket-5-Wiki/wiki/Emulators-and-Formats#vita3k-frontend-support
- Source: https://github.com/Vita3K/Vita3K/blob/master/android/app/src/main/java/org/vita3k/emulator/Emulator.java

### 3.10 PS3 (folder launch supported)

ES-DE ps3 extensions: `.iso .ps3`. A **directory** named `Game.ps3/`, keeping the Blu-ray structure, is passed as a directory.

| Mode | Package → Activity | Action | Extras | Notes / confidence |
|---|---|---|---|---|
| aPS3e Game Serial (ES-DE default) | `aenu.aps3e.premium` or `aenu.aps3e` → `aenu.aps3e.EmulatorActivity` (exported, singleTask) | `aenu.intent.action.APS3E` | `game_dir` = `%EXTERNALDATA%/Android/data/<pkg>/files/aps3e/config/dev_hdd0/game/<SERIAL>` (serial injected from `Game.ps3`) | Works for HDD/PKG-installed games. **V-ESDE + V-SRC** |
| aPS3e Directory | same | same | `game_dir` = PATH of the `.ps3` directory | Raw filesystem path, so aPS3e needs file access. Disc games only. **V-ESDE + V-SRC** |
| aPS3e ISO | same | same | `iso_uri` = SAF (String) | Opened via `contentResolver` file descriptor. **V-ESDE + V-SRC + X-DJ** |
| ARMSX3 Game Serial | `com.armsx3` (also `com.armsx3.play`) → `com.armsx2.Main` | `VIEW` | `title_id` = serial | Details below. **V-ESDE + V-SRC** |
| ARMSX3 Directory or ISO | same | none | `path` = PATH (directory or ISO) | Source key list: data, then `EXTRA_STREAM`, then clipData, then `path`/`game`/`rom`/`uri`, then `title_id`/`titleId`/`serial`. **V-ESDE + V-SRC** |
| EmuCoreC Directory or ISO | `com.sbro.emucorec` → `.core.ps3.Emulator` | none | `gamePath` = PATH | **V-ESDE** only |

aPS3e source:
- `EmulatorActivity.java` constants `EXTRA_ISO_URI="iso_uri"` and `EXTRA_GAME_DIR="game_dir"`. A serializable `meta_info` is used internally.
- The serial is found in aPS3e via long-press, then *Show Game Info*.
- Bulk serial files: https://raw.githubusercontent.com/Jetup13/Retroid-Pocket-4-Pro-Wiki/main/Files/ps3serials.zip
- Source: https://github.com/aenu1/aps3e/blob/HEAD/app/src/main/java/aenu/aps3e/EmulatorActivity.java

ARMSX3 source:
- `title_id`, `titleId` or `serial` is accepted as bare `BLUS12345` or as `[title_id] BLUS12345`, and looked up in the library cache or `dev_hdd0`.
- Source: https://github.com/ARMSX2/ARMSX3/blob/master/android/armsx3-ui/app/src/main/java/com/armsx2/runtime/MainActivityRuntime.kt

**RPCS3 Android / RPCSX (not in ES-DE): LIMITED, app launch only.**
- RPCS3-Android (`net.rpcs3`) is archived and discontinued ("Alpha-7 is last release"). Its `RPCS3Activity` (action `rpcs3.intent.action.Emulator`) is `exported="false"`.
- Its successor RPCSX UI Android (`net.rpcsx`) also declares `RPCSXActivity` with `exported="false"`.
- Sources: https://github.com/RPCS3-Android/rpcs3-android/blob/master/app/src/main/AndroidManifest.xml and https://github.com/RPCSX/rpcsx-ui-android/blob/HEAD/app/src/main/AndroidManifest.xml. **V-SRC**

### 3.11 Dreamcast

| Emulator | Package → Activity | Action | ROM | Notes / confidence |
|---|---|---|---|---|
| Flycast | `com.flycast.emulator` → `com.flycast.emulator.MainActivity` (an activity-alias of `NativeGLActivity`); legacy `com.flycast.emulator` → `com.reicast.emulator.MainActivity` | **`VIEW` (required)** | data=SAF | Source (`BaseGLActivity.onCreate`) only reads `getData()` when the action is `ACTION_VIEW`, then calls `JNIdc.setGameUri(uri)`. **V-ESDE + V-SRC + X-DJ** |
| Redream | `io.recompiled.redream` → `.MainActivity` | `VIEW` | data=SAF | Closed source. **V-ESDE + X-DJ** |

- Recommended layout: `.gdi`/`.cue` sets in a directory-as-file, or `.chd`/`.cdi`. Dir: No; only the inner file is passed.
- Flycast source: https://github.com/flyinghead/flycast/blob/master/shell/android-studio/flycast/src/main/java/com/flycast/emulator/BaseGLActivity.java

### 3.12 Saturn, N64, GBA, GB/GBC, NES/SNES standalones

| System | Emulator | Package → Activity | Action | ROM / extras | Flags | Notes / confidence |
|---|---|---|---|---|---|---|
| Saturn | Yaba Sanshiro 2 | `org.devmiyax.yabasanshioro2.pro` (then non-pro) → `org.uoyabause.android.Yabause` | `VIEW` | `org.uoyabause.android.FileNameUri`=SAF | CT, CTop | Only the **Pro** build launches from ES-DE. .bin/.cue broken, .chd works. Some devices report "Cannot initialize SH2". Daijishō uses `FileNameEx` with a path for the older non-"2" builds. **V-ESDE + X-DJ** |
| Saturn | Saturn.emu | `com.explusalpha.SaturnEmu` → `com.imagine.BaseActivity` | none | data=SAF | none | **V-ESDE** |
| N64 | M64Plus FZ | `org.mupen64plusae.v3.fzurita.amazon` / `.pro` / (free) → `paulscode.android.mupen64plusae.SplashActivity` | `VIEW` | data=SAF | none | SplashActivity forwards `getIntent().getData()` to GalleryActivity. Needs a scoped grant on `ROMs/n64`. **V-ESDE + V-SRC (partial) + X-DJ** |
| N64 | Mupen64Plus AE | `org.mupen64plusae.v3.alpha` → same activity | `VIEW` | data=SAF | none | **V-ESDE + X-DJ** |
| GBA | Pizza Boy GBA | `it.dbtecno.pizzaboygbapro` → `it.dbtecno.pizzaboygbapro.MainActivity`; `it.dbtecno.pizzaboygba` → `it.dbtecno.pizzaboygba.MainActivity` | none | `rom_uri`=SAF | CT, CTop | Closed. **V-ESDE + X-DJ** |
| GBA | My Boy! | `com.fastemulator.gba` → `.EmulatorActivity` | `VIEW` | data=SAF | none | The Lite/free build is unsupported. **V-ESDE + X-DJ** |
| GBA | GBA.emu | `com.explusalpha.GbaEmu` → `com.imagine.BaseActivity` | none | data=FP | none | **V-ESDE + V-SRC** (EX+ BaseActivity: `file://` becomes a path, otherwise the content URI is used and the data is cleared after one use) |
| GB/GBC | Pizza Boy GBC | `it.dbtecno.pizzaboypro` / `it.dbtecno.pizzaboy` → `<pkg>.MainActivity` | none | `rom_uri`=SAF | CT, CTop | **V-ESDE + X-DJ** |
| GB/GBC | My OldBoy! | `com.fastemulator.gbc` → `.EmulatorActivity` | `VIEW` | data=SAF | none | **V-ESDE + X-DJ** |
| GB/GBC | GBC.emu | `com.explusalpha.GbcEmu` → `com.imagine.BaseActivity` | none | data=FP | none | **V-ESDE** |
| GB/GBC/GBA | Linkboy | `com.pixelrespawn.linkboy` → `.EmulatorActivity` | `VIEW` | data=SAF | none | **V-ESDE** |
| NES | NES.emu | `com.explusalpha.NesEmu` → `com.imagine.BaseActivity` | none | data=FP | none | **V-ESDE + X-DJ** |
| NES | iNES | `com.fms.ines.free` → `com.fms.emulib.TVActivity` | `VIEW` | data=SAF | CT, CTop | **V-ESDE** |
| NES | Nesoid | `com.androidemu.nes` → `.EmulatorActivity` | `VIEW` | data=**PATH** | none | **V-ESDE** |
| SNES | Snes9x EX+ | `com.explusalpha.Snes9xPlus` → `com.imagine.BaseActivity` | none | data=SAF | none | **V-ESDE + X-DJ** (Daijishō adds `-t application/zip`) |

EX+ Alpha source: https://github.com/Rakashazi/emu-ex-plus-alpha/blob/master/imagine/src/base/android/imagine-v9/src/main/java/com/imagine/BaseActivity.java (`intentDataPath()`).

### 3.13 Lemuroid (not in ES-DE)

| Field | Value |
|---|---|
| Package | `com.swordfish.lemuroid` (per the `${applicationId}` placeholder) |
| Component | `com.swordfish.lemuroid.app.shared.game.ExternalGameLauncherActivity` (exported) |
| Action / data | `VIEW` with `lemuroid://<applicationId>/play-game/id/<N>` |
| How the ROM is passed | **Not by path or URI.** `<N>` is Lemuroid's **internal database game id** (`retrogradeDatabase.gameDao().selectById`). The URI is what Lemuroid's own pinned shortcuts use |
| Verdict | **LIMITED: per-game only via Lemuroid's internal DB id.** A frontend has no documented way to learn that id, so in practice it is **app launch only** (TV entry: `lemuroid://<pkg>/open-leanback`) |
| Sources | https://github.com/Swordfish90/Lemuroid/blob/master/lemuroid-app/src/main/AndroidManifest.xml and `.../app/shared/game/ExternalGameLauncherActivity.kt` |
| Confidence | **V-SRC** |

### 3.14 Store apps added in 0.2.3 (not in ES-DE)

Checked on 2 October 2026 against each project's own source or frontend guide.

| App | Package | Launch | Source | Confidence |
|---|---|---|---|---|
| Pico8 Android (PICO-8) | `io.wip.pico8` | `VIEW` with the cart's URI to `com.godot.game.GodotAppLauncher` (`.p8`, `.p8.png`; `Splore.p8` opens Splore) | Macs75/pico8-android wiki, Frontends-Integration.md (Beacon's `am start`) | **V-SRC** |
| Swiff (Flash) | `io.navivani.swiff` | `VIEW` with a `.swiffid` file's URI, type `application/octet-stream`, to `.MainActivity` (the file its Frontend sync writes) | NaviVani-dev/Swiff wiki, Frontend-Support.md | **V-SRC** |
| Gopher64 (N64) | `io.github.gopher64.gopher64` | **App launch only**: `N64Activity` is `exported="false"`; only the launcher `SlintActivity` is exported | gopher64/gopher64 `android-project/app/src/main/AndroidManifest.xml` | **V-SRC** |
| X360 Mobile (Xbox 360) | `emu.x360mobile.com` | **App launch only**: the README mentions external front-end launching but publishes no intent | Ashnar2602/X360-Mobile---OFFICIAL README | **UNVERIFIED** |
| Winlator-Ludashi | `com.winlator.ludashi` | Same as Winlator Cmod: `com.winlator.cmod.XServerDisplayActivity` (exported) with a shortcut | StevenMXZ/Winlator-Ludashi `app/src/main/AndroidManifest.xml` | **V-SRC** |

Starboard (`org.force9.starboard`) runs PortMaster ports it installs itself and has no per-game
launch, so it is listed under Tools in Apps rather than as an emulator.

### 3.15 Installing games, updates, DLC and licences from outside the emulator (0.2.4)

Checked on 2 October 2026 against each project's source. Only these installers are used, and a step
counts as installed only when the emulator's storage shows it.

| Emulator | Mechanism | Source | Notes |
|---|---|---|---|
| RPCS3 (Linux, Windows, macOS) | `rpcs3 --headless --installpkg <file>` for `.pkg`, `.rap` and `.edat` | `rpcs3/rpcs3.cpp` (headless branch calls `main_window::InstallPackages(nullptr, ...)`); `rpcs3qt/main_window.cpp` `InstallFileInExData` | Always exits 0; a `.rap` is copied to `exdata` under its own file name, and boots need exactly `<contentId>.rap` |
| RPCS3 firmware | `--installfw <PS3UPDAT.PUP>` | `rpcs3/rpcs3.cpp` | Not used yet: firmware is checked in BIOS and firmware |
| Vita3K (Linux, Windows, macOS) | `Vita3K --pkg <file> --zrif <key>` (installs and quits); a `.vpk`/`.zip` as content path (installs, then starts it) | `vita3k/config/src/config.cpp`, `vita3k/main.cpp`, `vita3k/packages/src/pkg.cpp`, `license.cpp` | Patches decrypt with the game's licence; `find_pkg_zrif` makes a zRIF from `ux0/license/<id>/<contentId>.rif` |
| Azahar (Linux, Windows, macOS) | `azahar -i <file.cia>` | `src/citra_qt/citra_qt.cpp` | Exit 0 success, `InstallStatus + 2` failure (3 open, 4 not found, 5 aborted, 6 invalid, 7 encrypted); a `.cia` given to play is never started |
| aPS3e, RPCSX, ARMSX3 (Android) | None from outside: installs run from their own file pickers (`InstallGame.install_pkg` in aPS3e; RPCSX's activity isn't exported) | Their `AndroidManifest.xml` and sources | Fuse guides: the files in order, then opens the emulator |
| Vita3K (Android) | None from outside: `.pkg` installs from its own screens (`NativeLib.installPkg`) | `android/` in Vita3K | Launching installed games by `-r <title id>` works (AppStartParameters) |
| Eden, Ryujinx, Cemu, Dolphin, PPSSPP, xemu | No documented command line install (NAND or title installs are menu-only, or the formats play directly) | Their command line parsers | Updates and DLC are explained per emulator, as before |

---

## 4. PC/Windows launchers

### 4.1 Summary

| Launcher | ES-DE support | Per-game external launch | Confidence |
|---|---|---|---|
| Winlator (mainline, brunodev85) | No. ES-DE: "mainline Winlator does not offer frontend support" | **LIMITED: app launch only.** Package `com.winlator`; only `com.winlator.MainActivity` is exported (MAIN). `com.winlator.XServerDisplayActivity` is `exported="false"`, although internally it reads `container_id`, `shortcut_path` and `exec_path` | V-SRC (https://github.com/brunodev85/winlator-app/blob/HEAD/app/src/main/AndroidManifest.xml; app code is in the `winlator-app` submodule per https://github.com/brunodev85/winlator/blob/main/.gitmodules) |
| Winlator Cmod (Bionic, default) | Yes: `WINLATOR-CMOD` | `com.winlator.cmod` → `.XServerDisplayActivity`, extra `shortcut_path`=PATH to `.desktop` | V-ESDE + V-SRC + X-DJ |
| Winlator Cmod forks under spoofed packages | Yes, same rule | `com.winlator.vanilla`, `com.ludashi.benchmark` (e.g. Winlator Ludashi), `com.tencent.ig` → `com.winlator.cmod.XServerDisplayActivity` | V-ESDE |
| Winlator Cmod Glibc | Yes: `WINLATOR-GLIBC` | `com.winlator` → `.XServerDisplayActivity`, `shortcut_path` | V-ESDE + X-DJ ("Winlator Cmod (Old)"). ⚠ Same package as mainline Winlator, whose activity is not exported; a launch against mainline fails |
| Winlator Cmod PRoot | Yes: `WINLATOR-PROOT` | `com.cmodded.winlator` → `com.winlator.XServerDisplayActivity`, `shortcut_path` | V-ESDE + X-DJ |
| WinNative | Yes: `WINNATIVE` (master only) | `com.winnative.cmod` (flavors `com.ludashi.benchmark`, `com.tencent.ig`, `com.antutu.ABenchMark`) → `com.winlator.cmod.runtime.display.XServerDisplayActivity` | V-ESDE + V-SRC |
| Bannerlator (Winlator Star Bionic continuation) | Not in ES-DE; its own docs give ES-DE and Beacon instructions | `com.winlator.banner` (or `com.ludashi.benchmark`, `com.tencent.ig`) → **`com.winlator.star.XServerDisplayActivity`** (must be fully qualified), `-e shortcut_path <abs .desktop>`, CT, CTop | Vendor doc: https://github.com/The412Banner/Bannerlator/blob/HEAD/marcescence-frontends.md |
| Winlator Frost | Not in ES-DE; no repo or docs found | **UNVERIFIED.** If Cmod-based, expect `shortcut_path`, but test it | UNVERIFIED |
| GameNative | Yes: `GAMENATIVE` | Intent API (§4.3) | V-ESDE + V-SRC + X-DJ |
| GameHub Lite | Yes: `GAMEHUB-LITE` | Intent API (§4.4) | V-ESDE + V-SRC (manifest patch) + X-DJ |
| GameHub (original, XiaoJi) | No | No documented external launch found; the Lite patch has to *add* the export and intent filter, which implies the original lacks it. **LIMITED: app launch only** | UNVERIFIED (closed source) |

WinNative details:
- Exported. Accepts `shortcut_path` (a `.desktop` path), **or** `VIEW` with `file://` or `content://` data of MIME `application/x-desktop`, which is resolved to a path.
- Its own pinned shortcuts use `winnative://<pkg>/…shortcut…?uuid=&container=&hash=`.
- Store games are exported as `.desktop` files carrying a `game_source` extra.
- ES-DE uses it for the windows, steam and epic systems with `%EXTRA_shortcut_path%=%ROM%`.

### 4.2 Winlator Cmod intent and the `.desktop` shortcut format (for frontends)

**Intent** (Cmod source `XServerDisplayActivity.java`, lines ~441-500):
```
component: com.winlator.cmod/com.winlator.cmod.XServerDisplayActivity   (exported="true", launchMode=singleTask)
extras:    shortcut_path  (String, absolute java.io.File path to the .desktop)   [required for frontends]
           container_id   (int, optional; if 0/missing it is parsed from the .desktop "container_id=" line)
           shortcut_name  (String, optional; otherwise parsed from the file)
           disableXinput  (String "0"/"1", optional; the internal launcher passes it)
flags:     ES-DE: CLEAR_TASK + CLEAR_TOP; Cmod's own Pegasus export also adds NO_HISTORY
```
- The path is opened with `new File(shortcutPath)`, so it must be a **real filesystem path, not a content URI**.
- Winlator holds `MANAGE_EXTERNAL_STORAGE`, so reading `/storage/emulated/0/...` works.

**"Export for Frontend"** (Cmod `ShortcutsFragment.exportShortcutToFrontend`):
- Copies the container's `.desktop` to the export directory, which is the *Frontend Export Path* setting (`frontend_export_uri`) or, by default, `Download/Winlator/Frontend/`.
- It rewrites or appends `container_id=<id>`.
- It also writes `FRONTEND_INSTRUCTIONS.txt` (Daijishō and Beacon instructions) and a Pegasus `metadata.pegasus.txt`:
```
collection: Windows
shortname: windows
extensions: desktop
launch: am start
  -n com.winlator.cmod/com.winlator.cmod.XServerDisplayActivity
  -e shortcut_path {file.path}
  --activity-clear-task
  --activity-clear-top
  --activity-no-history
```
- Beacon instruction text: `am start -n com.winlator.cmod/com.winlator.cmod.XServerDisplayActivity -e shortcut_path {file_path}`.

**File format** (Cmod `container/Shortcut.java` parser and the .lnk importer):
```
[Desktop Entry]
Name=<name>
Exec=env WINEPREFIX="/data/user/0/com.winlator.cmod/files/imagefs/home/xuser/.wine" wine C:\\\\path\\\\Game.exe
Type=Application
StartupNotify=true
Path=<working dir under .../.wine/dosdevices/<drive>:/...>
Icon=<icon basename, looked up in the container's icons/{64,48,32,16} dirs as .png>
StartupWMClass=<Game.exe>

[Extra Data]
container_id=<int>
<other per-shortcut settings, e.g. execArgs, secondaryExec, execDelay, disableXinput, customIconPath, customCoverArtPath...>
```
Parser rules:
- A `[Desktop Entry]` header is **required**; otherwise the file is rejected as malformed.
- The Windows target is everything after the **last** `"wine "` in `Exec`, unescaped.
- Keys in `[Extra Data]` become a JSON map. The first `=` separates key from value.

Frontend use:
- Place the file in `ROMs/windows`, `ROMs/pcarcade` or `ROMs/type-x`.
- ES-DE command: `%EMULATOR_WINLATOR-CMOD% %ACTIVITY_CLEAR_TASK% %ACTIVITY_CLEAR_TOP% %EXTRA_shortcut_path%=%ROM%`.
- ES-DE doesn't parse the `.desktop` on Android; Winlator does.

Sources:
- https://github.com/coffincolors/winlator/blob/HEAD/app/src/main/java/com/winlator/cmod/XServerDisplayActivity.java
- https://github.com/coffincolors/winlator/blob/HEAD/app/src/main/java/com/winlator/cmod/ShortcutsFragment.java
- https://github.com/coffincolors/winlator/blob/HEAD/app/src/main/java/com/winlator/cmod/container/Shortcut.java
- https://github.com/coffincolors/winlator/blob/HEAD/app/src/main/AndroidManifest.xml
- ANDROID.md, section "Winlator"

### 4.3 GameNative (Steam, Epic, GOG, Amazon, custom games)

| Field | Value |
|---|---|
| Package → activity | `app.gamenative` → `.MainActivity` (exported, singleTop). A "gold" build type adds applicationId suffix `.gold` (`app.gamenative.gold`); ES-DE doesn't list it |
| Action | `app.gamenative.LAUNCH_GAME` (intent filter plus `IntentLaunchManager`) |
| Extras | `app_id` = **int** (`getIntExtra`; must be > 0)<br>`game_source` = String, one of `STEAM`, `CUSTOM_GAME`, `GOG`, `EPIC`, `AMAZON` (case-insensitive; **defaults to STEAM** if missing or invalid)<br>optional `container_config` = JSON (≤50 KB), a temporary container override |
| Alternative | `VIEW` with `gamenative://run?appid=<int>&gamesource=<SOURCE>` |
| ES-DE command | `%EMULATOR_GAMENATIVE% %ACTION%=app.gamenative.LAUNCH_GAME %EXTRA_game_source%=STEAM %EXTRAINTEGER_app_id%=%INJECT%=%ROM%`. The `game_source` value is also `EPIC`, `GOG`, `AMAZON` or `CUSTOM_GAME` depending on the entry |
| ID files | Written by GameNative's "Export for frontend" and by per-source auto-sync (`FrontendSyncManager`). The file is `<sanitized title><ext>` and its content is only the numeric id. `.steam` for STEAM, `.epic`, `.gog`, `.amazon`, and `.pcgame` for CUSTOM_GAME. These match ES-DE's windows/steam/epic extensions |
| Folder launch | N/A (id-based) |
| Sources | https://github.com/utkarshdalal/GameNative/blob/master/app/src/main/java/app/gamenative/utils/IntentLaunchManager.kt<br>`app/src/main/AndroidManifest.xml`<br>`app/src/main/java/app/gamenative/sync/FrontendSyncManager.kt`<br>`app/src/main/java/app/gamenative/data/LibraryItem.kt` (GameSource enum)<br>`app/build.gradle.kts` |
| Confidence | **V-ESDE + V-SRC + X-DJ** (Daijishō uses `--ei app_id {tags.steamappid} --es game_source STEAM`) |

### 4.4 GameHub Lite (patched GameHub)

| Field | Value |
|---|---|
| Packages | `gamehub.lite`, `com.antutu.ABenchMark`, `com.antutu.benchmark.full`, `com.ludashi.aibench`, `com.tencent.ig` (README variant table). ES-DE also lists `emuready.gamehub.lite` |
| Activity | `com.xj.landscape.launcher.ui.gamedetail.GameDetailActivity`. The Lite patch makes it `exported="true"` and adds the intent filter `gamehub.lite.LAUNCH_GAME` (`patches/diffs/AndroidManifest.xml.patch`) |
| Action | `gamehub.lite.LAUNCH_GAME` |
| Extras | `steamAppId` = String (Steam id)<br>`localGameId` = String (GameHub's local game id, copyable in-app)<br>`autoStartGame` = boolean true. ES-DE's Steam entry sets both `steamAppId` and `localGameId` to the file content |
| Sources | https://github.com/Producdevity/gamehub-lite (README; `patches/diffs/AndroidManifest.xml.patch`) |
| Confidence | Action and export: **V-SRC**. Extra names: **V-ESDE + X-DJ** (they live in closed GameHub smali). BannerHub forks use actions `banner.hub.LAUNCH_GAME` and `banner.hub.lite.LAUNCH_GAME` (Daijishō) |

---

## 5. ES-DE Steam and Android-app approach; Daijishō, iiSU, Beacon and Pegasus

**ES-DE on Android:**
- **steam** system: extensions `.desktop .pcgame .steam`; emulators GameNative (default), GameHub Lite, GameHub Lite Local, WinNative.
- **windows** system: extensions `.amazon .desktop .epic .gog .pcgame .steam`; default Winlator Cmod.
- **epic** system: `.app` native apps, plus GameNative (`game_source=EPIC`) and WinNative.
- A `.steam` file contains **only** the Steam app id (e.g. `274190`). USERGUIDE warns against extra spaces or newlines.
- Bulk Steam id files: https://github.com/RobZombie9043/steam-files-es-de (don't import all of them).
- Native Android apps live in the **androidapps** and **androidgames** systems (plus emulators, epic, n64, ports) as `.app` files, launched with `%ANDROIDAPP%=%FILEINJECT%` (§2).
- On desktop Linux, ES-DE's steam system uses `.desktop`/`.sh` files with `%RUNINBACKGROUND% %ENABLESHORTCUTS% %EMULATOR_OS-SHELL% %ROM%`.
  - `%ENABLESHORTCUTS%` parses the `Exec=` key and executes it blindly, after stripping `%F %f %U %u` (INSTALL.md).
  - Linux ps3 uses `.desktop`, `.ps3` (serial), `.ps3dir` or `.iso`.
  - Linux switch uses `Eden -f -g %ROM%` or `Ryujinx %ROM%`.

**Daijishō** (closed-source app; public "platform" JSON assets at https://github.com/TapiocaFox/Daijishou, no license):
- Players are raw `am start` argument strings with placeholders: `{file.path}` (absolute path), `{file.uri}` (content URI), and `{tags.<name>}`.
- `{tags.*}` values come from **Daijishō Player Template** files. The first line is `# Daijishou Player Template` (or `# DST`), followed by lines like `[steamappid] 274190`. Docs: https://github.com/TapiocaFox/Daijishou/blob/HEAD/docs/daijishou_player_template.md
- Windows platform (`platforms/Windows.json`): Winlator Cmod, Cmod PRoot and "Cmod (Old)" (`com.winlator`) players accept `.desktop` files. Daijishō passes `-e shortcut_path {file.path}` with `--activity-clear-task --activity-clear-top --activity-no-history` and **does not parse the .desktop itself**.
  - It also has MiceWine (`com.micewine.emu/com.micewine.emu.activities.MainActivity -e exePath {file.path}`, `.lnk`).
  - GameNative players for `.gog`, `.epicgame`, `.customgame`, `.amazon` and `.steamappid`.
  - GameHub Lite and BannerHub players for `.localgameid` and `.steamappid`.
- Steam platform: `.steamappid` files with GameHub Lite, BannerHub or GameNative players.
- Cmod's exported `metadata.pegasus.txt` can be imported into Daijishō ("Import from Pegasus").

**iiSU** (https://github.com/iisu-network/iiSU; app code not published in the repo):
- The wiki (https://iisu.network/wiki/adding-steam-games-to-iisu) documents the same conventions:
  - `.steam` files contain only the Steam app id, for GameHub or GameNative. GameNative's "Export for frontend" can generate them.
  - Winlator Cmod `.desktop` files come from "Export for Frontend".
- iiSU's emulator or intent definitions are **not publicly documented**. **UNVERIFIED** beyond the file conventions.

**Beacon and Pegasus:** Cmod's generated instructions and Bannerlator's doc both use the same `am start … -e shortcut_path {file_path}` form.

---

## 6. Implementation notes and caveats for Fuse (Kotlin)

- **Build the SAF URI like ES-DE.** Use a *tree* grant on the system folder. The Android API is `DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)`:
  ```kotlin
  // vol = "primary" or SD UUID; rel = "ROMs/psx"; file = "Game.chd"
  val tree = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "$vol:$rel")
  val doc  = DocumentsContract.buildDocumentUriUsingTree(tree, "$vol:$rel/$file")
  // -> content://com.android.externalstorage.documents/tree/primary%3AROMs%2Fpsx/document/primary%3AROMs%2Fpsx%2FGame.chd
  ```
  The *emulator* must hold a persisted grant for exactly that tree. That is why ES-DE tells users to grant `ROMs/<system>` inside each emulator, not the ROMs root (ANDROID.md, FAQ-ANDROID). ES-DE's own launch code is closed, so I matched the shape against the logged URI in Azahar issue #1484.
- **FileProvider launches** (Eden, Skyline, the EX+ Alpha emulators, SkyEmu, and others): serve the file through your own `FileProvider` and add `FLAG_GRANT_READ_URI_PERMISSION`. ES-DE notes you must *own* file access (`MANAGE_EXTERNAL_STORAGE`) for this to work.
- **Typed extras matter:**
  - GameNative `app_id` is an int.
  - Vita3K `AppStartParameters` is a `String[]`.
  - DuckStation `resumeState` and GameHub `autoStartGame` are booleans.
  - Everything else is a String, including SAF URIs passed as extras (DuckStation `bootPath`, Dolphin `AutoStartFile`, melonDS `uri`, Pizza Boy `rom_uri`).
- **Actions that the app's code actually checks:**
  - Flycast requires `VIEW`.
  - GameNative requires `app.gamenative.LAUNCH_GAME`, or `VIEW` with `gamenative://run`.
  - Eden treats `dev.eden.eden_emulator.LAUNCH_WITH_CUSTOM_CONFIG` specially.
  - For the others the action is cosmetic when you use an explicit component, but mirror ES-DE's values for safety.
- **Package visibility:** on Android 11 and later, Fuse needs `<queries>` entries or `QUERY_ALL_PACKAGES` to detect installed emulators. Verify package **and** activity, because spoofed package names are shared between apps.
- **Flags:** mirror ES-DE's per-emulator `CLEAR_TASK`/`CLEAR_TOP`. RetroArch self-restarts on a new ROM or core.
- **Directory launch support**, verified from source unless noted:
  - aPS3e `game_dir` (raw path).
  - ARMSX3 `path`.
  - EmuCoreC `gamePath` (ES-DE only).
  - Everything else takes single files. Directories-as-files for multi-disc only hide the folder and pass the inner `.m3u`/`.cue`.
  - Switch: none. Updates and DLC must be installed into the emulator's NAND via its UI; I did not verify from source how each emulator applies them on external launch.

## 7. Not verified or open questions

- Kenji-NX source (git.ryujinx.app was unreachable). ARMSX2 source (repo not indexed or reachable).
- ARMSX1 activity mismatch: ES-DE and Daijishō use `com.armsx2.Main`; the public repo has only `com.nanodata.armsx.EmulatorActivity`.
- Closed-source emulators rest only on ES-DE plus Daijishō: DuckStation, NetherSX2/AetherSX2, DraStic, Redream, Pizza Boy, My Boy!/My OldBoy!, ePSXe, FPse.
- Yaba Sanshiro 2 source path returned 404, so ES-DE plus Daijishō only.
- Unlisted in ES-DE, so treat as UNVERIFIED: Citron, Sudachi, Yuzu, Suyu, Borked3DS, Winlator Frost and GameHub original. Strato's manifest is verified but its launch behaviour is not.
- iiSU internals (closed).
