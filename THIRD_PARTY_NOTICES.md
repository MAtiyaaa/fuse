# Third-party notices

Fuse is licensed under **GPL-3.0-or-later** ([LICENSE](LICENSE)). This page audits everything Fuse is
built with or ships, and records why each licence is compatible with distributing Fuse under
GPL-3.0-or-later. Components keep their own licences; Fuse's licence does not change them.

Versions come from `gradle/libs.versions.toml` (checked 2026-09-30). The complete, resolved list of
transitive dependencies for a build is printed by `./gradlew :app:android:dependencies` and
`./gradlew :app:desktop:dependencies`.

## Libraries

"Used by" says which part of Fuse depends on the library today; entries marked "catalog only" are
declared in the version catalog for the platform apps and not yet used by any module.

| Library | Maven coordinates | Version | Licence | Used by | Link |
|---|---|---|---|---|---|
| Kotlin standard library and `kotlin-test` | `org.jetbrains.kotlin:*` | 2.4.20 | Apache-2.0 | All modules (`kotlin-test` in tests only) | [kotlin](https://github.com/JetBrains/kotlin) |
| kotlinx.coroutines core | `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.11.0 | Apache-2.0 | All shared modules | [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) |
| kotlinx.coroutines Android | `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.11.0 | Apache-2.0 | Catalog only | [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) |
| kotlinx.coroutines Swing | `org.jetbrains.kotlinx:kotlinx-coroutines-swing` | 1.11.0 | Apache-2.0 | `app:desktop` | [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) |
| kotlinx.coroutines test | `org.jetbrains.kotlinx:kotlinx-coroutines-test` | 1.11.0 | Apache-2.0 | Tests only | [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) |
| kotlinx.serialization JSON | `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.11.0 | Apache-2.0 | All shared modules | [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) |
| kotlinx-datetime | `org.jetbrains.kotlinx:kotlinx-datetime` | 0.8.0 | Apache-2.0 | `ui:shell` | [kotlinx-datetime](https://github.com/Kotlin/kotlinx-datetime) |
| Compose Multiplatform runtime, foundation, ui, ui-util, animation, components-resources | `org.jetbrains.compose.*` | 1.12.1 | Apache-2.0 | `ui:designsystem`, `ui:shell` | [compose-multiplatform](https://github.com/JetBrains/compose-multiplatform) |
| Compose for Desktop (`compose.desktop.currentOs`, includes Skiko) | `org.jetbrains.compose.desktop:*` | 1.12.1 | Apache-2.0 (Skiko bundles Skia, BSD-3-Clause) | `app:desktop` | [compose-multiplatform](https://github.com/JetBrains/compose-multiplatform) |
| Compose Multiplatform Material 3 | `org.jetbrains.compose.material3:material3` | 1.9.0 | Apache-2.0 | Catalog only | [compose-multiplatform](https://github.com/JetBrains/compose-multiplatform) |
| JetBrains Lifecycle runtime-compose | `org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose` | 2.11.0 | Apache-2.0 | `ui:shell` | [compose-multiplatform](https://github.com/JetBrains/compose-multiplatform) |
| JetBrains Lifecycle viewmodel-compose | `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose` | 2.11.0 | Apache-2.0 | Catalog only | [compose-multiplatform](https://github.com/JetBrains/compose-multiplatform) |
| SQLDelight runtime and coroutines extensions | `app.cash.sqldelight:runtime`, `:coroutines-extensions` | 2.4.0 | Apache-2.0 | `core:data` | [sqldelight](https://github.com/sqldelight/sqldelight) |
| SQLDelight Android driver | `app.cash.sqldelight:android-driver` | 2.4.0 | Apache-2.0 | `core:data` (Android) | [sqldelight](https://github.com/sqldelight/sqldelight) |
| SQLDelight SQLite (JDBC) driver, with Xerial SQLite JDBC | `app.cash.sqldelight:sqlite-driver` | 2.4.0 | Apache-2.0 (SQLite itself is in the public domain) | `core:data` (desktop) | [sqldelight](https://github.com/sqldelight/sqldelight), [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) |
| Coil compose | `io.coil-kt.coil3:coil-compose` | 3.6.3 | Apache-2.0 | `ui:designsystem`, `ui:shell` | [coil](https://github.com/coil-kt/coil) |
| Coil network (Ktor 3) | `io.coil-kt.coil3:coil-network-ktor3` | 3.6.3 | Apache-2.0 | `ui:shell` | [coil](https://github.com/coil-kt/coil) |
| Coil SVG (with AndroidSVG on Android) | `io.coil-kt.coil3:coil-svg`, `com.caverock:androidsvg-aar` | 3.6.3 | Apache-2.0 | `ui:shell` | [coil](https://github.com/coil-kt/coil), [androidsvg](https://github.com/BigBadaboom/androidsvg) |
| Ktor client core, content negotiation, kotlinx JSON serialization | `io.ktor:ktor-client-core`, `:ktor-client-content-negotiation`, `:ktor-serialization-kotlinx-json` | 3.6.0 | Apache-2.0 | `core:integrations` | [ktor](https://github.com/ktorio/ktor) |
| Ktor client OkHttp engine (with OkHttp and Okio) | `io.ktor:ktor-client-okhttp` | 3.6.0 | Apache-2.0 | `app:android` | [ktor](https://github.com/ktorio/ktor), [okhttp](https://github.com/square/okhttp) |
| Ktor client CIO engine | `io.ktor:ktor-client-cio` | 3.6.0 | Apache-2.0 | `app:desktop` | [ktor](https://github.com/ktorio/ktor) |
| Ktor server core and CIO engine (Phone Link's web server) | `io.ktor:ktor-server-core`, `:ktor-server-cio` | 3.6.0 | Apache-2.0 | `ui:link` | [ktor](https://github.com/ktorio/ktor) |
| Pulled in by the Ktor server: Typesafe Config, kotlinx.coroutines SLF4J | `com.typesafe:config`, `org.jetbrains.kotlinx:kotlinx-coroutines-slf4j` | 1.4.9, 1.11.0 | Apache-2.0 | `ui:link` | [config](https://github.com/lightbend/config), [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) |
| SLF4J API (logging facade pulled in by the Ktor server; Fuse adds no logger, so nothing is logged through it) | `org.slf4j:slf4j-api` | 2.0.19 | MIT | `ui:link` | [slf4j](https://github.com/qos-ch/slf4j) |
| QR Code generator library (Phone Link pairing codes) | `io.nayuki:qrcodegen` | 1.8.0 | MIT | `ui:link` | [qrcodegen](https://github.com/nayuki/QR-Code-generator) |
| Ktor client mock engine | `io.ktor:ktor-client-mock` | 3.6.0 | Apache-2.0 | Tests only | [ktor](https://github.com/ktorio/ktor) |
| AndroidX Activity Compose | `androidx.activity:activity-compose` | 1.13.0 | Apache-2.0 | `app:android` | [activity](https://developer.android.com/jetpack/androidx/releases/activity) |
| AndroidX Core KTX | `androidx.core:core-ktx` | 1.19.1 | Apache-2.0 | `app:android` | [core](https://developer.android.com/jetpack/androidx/releases/core) |
| Media3 ExoPlayer, HLS and UI | `androidx.media3:media3-exoplayer`, `:media3-exoplayer-hls`, `:media3-ui` | 1.11.1 | Apache-2.0 | `ui:player` (Fuse Player on Android), `app:android` (video previews) | [media](https://github.com/androidx/media) |
| FFmpeg (bytedeco build, GPL variant, with the natives for the packaged operating system only) | `org.bytedeco:ffmpeg` with the `<os>-gpl` classifier | 8.1.2-1.5.14 | GPL-3.0-or-later (this build enables GPL parts; FFmpeg itself is LGPL-2.1-or-later with optional GPL parts) | `ui:player` (Fuse Player on Windows, macOS and Linux) and `app:desktop` (screen recordings) | [ffmpeg](https://ffmpeg.org), [javacpp-presets](https://github.com/bytedeco/javacpp-presets) |
| JavaCPP | `org.bytedeco:javacpp` | 1.5.14 | Apache-2.0 | `ui:player` and `app:desktop` | [javacpp](https://github.com/bytedeco/javacpp) |
| JLayer (MP3 decoder for menu music) | `javazoom:jlayer` | 1.0.1 | LGPL-2.1-or-later (the sources say "version 2 of the License, or any later version") | `app:desktop` | [JLayer](http://www.javazoom.net/javalayer/javalayer.html) |
| libGDX Jamepad (controllers on Windows and macOS, through SDL) | `com.badlogicgames.jamepad:jamepad` | 2.30.0.0 | Apache-2.0 | `app:desktop`, Windows and macOS builds only | [jamepad](https://github.com/libgdx/jamepad) |
| SDL2 (inside Jamepad's native libraries) | | 2.30 | zlib | `app:desktop`, Windows and macOS builds only | [SDL](https://github.com/libsdl-org/SDL) |
| gdx-jnigen-loader (loads Jamepad's native library) | `com.badlogicgames.gdx:gdx-jnigen-loader` | 2.2.0 | Apache-2.0 | `app:desktop`, Windows and macOS builds only | [gdx-jnigen](https://github.com/libgdx/gdx-jnigen) |

### Build tools

These build Fuse and are not shipped inside the APK or AppImage (except where a library above comes
from the same project).

| Tool | Coordinates | Version | Licence | Link |
|---|---|---|---|---|
| Gradle and the Gradle wrapper (`gradle/wrapper/gradle-wrapper.jar` is in the repository) | | 9.8.0 | Apache-2.0 | [gradle](https://github.com/gradle/gradle) |
| Kotlin Gradle plugin, Compose compiler plugin, serialization plugin | `org.jetbrains.kotlin:kotlin-gradle-plugin`, `:compose-compiler-gradle-plugin`, `:kotlin-serialization` | 2.4.20 | Apache-2.0 | [kotlin](https://github.com/JetBrains/kotlin) |
| Compose Multiplatform Gradle plugin | `org.jetbrains.compose:compose-gradle-plugin` | 1.12.1 | Apache-2.0 | [compose-multiplatform](https://github.com/JetBrains/compose-multiplatform) |
| Android Gradle Plugin (application and Kotlin Multiplatform library plugins) | `com.android.tools.build:gradle` | 9.4.1 | Apache-2.0 | [AGP release notes](https://developer.android.com/build/releases/gradle-plugin) |
| SQLDelight Gradle plugin | `app.cash.sqldelight:gradle-plugin` | 2.4.0 | Apache-2.0 | [sqldelight](https://github.com/sqldelight/sqldelight) |
| WiX Toolset (builds the Windows installer; the Compose plugin downloads it) | | 3.14 | MS-RL | [wix3](https://github.com/wixtoolset/wix3) |
| Lucide icon generator | `tools/icons/generate_icons.py` (part of Fuse) | | GPL-3.0-or-later | |

## Bundled assets

| Asset | Files | Licence | Copyright | Licence text |
|---|---|---|---|---|
| Sora typeface (Medium, SemiBold, Bold) | `ui/designsystem/src/commonMain/composeResources/font/sora_*.ttf` | SIL Open Font License 1.1 | Copyright 2019 The Sora Project Authors ([sora-font](https://github.com/sora-xor/sora-font)) | [licenses/OFL-Sora.txt](ui/designsystem/src/commonMain/composeResources/files/licenses/OFL-Sora.txt) |
| Manrope typeface (Regular, Medium, SemiBold, Bold) | `ui/designsystem/src/commonMain/composeResources/font/manrope_*.ttf` | SIL Open Font License 1.1 | Copyright 2018 The Manrope Project Authors ([manrope](https://github.com/googlefonts/manrope)) | [licenses/OFL-Manrope.txt](ui/designsystem/src/commonMain/composeResources/files/licenses/OFL-Manrope.txt) |
| Menu music: the albums "jam channel" (10 songs: soiree, dewwy, puddleworld, alright apothecary, chachuu, beamrider, mirth, comeaux, wub time, sighonara) and "creature interchange" (14 songs: hellacious hilltops, kickback kroose, compassionate cafe, mediocre mall, distant dunes, plucky park, sarcastic shop, sassy shells, verisimilitudinous valley, nocturnal knoll, enamoring eventide, fickle fountain, bedtime ballad, reflective river) by boipurple, re-encoded to 128 kbps MP3 | `ui/designsystem/src/commonMain/composeResources/files/music/*.mp3` | Not under Fuse's licence; all rights remain with the artist. Included at the request of Fuse's maintainer, who supplied the files | boipurple | Credited in Settings, Sound and in Settings, About, Open-source licences, Music |
| Lucide icons 1.49.0 (185 icons, converted to path data) | `ui/designsystem/src/commonMain/kotlin/io/github/matiyaaa/fuse/ui/designsystem/icons/FuseIcons.kt` | ISC; the icons Lucide derived from Feather are also under MIT | Copyright (c) 2026 Lucide Icons and Contributors; Copyright (c) 2013-present Cole Bemis (Feather) | [licenses/LICENSE-lucide.txt](ui/designsystem/src/commonMain/composeResources/files/licenses/LICENSE-lucide.txt) |

The website ([`site/`](site), published at matiyaaa.github.io/fuse) uses the same fonts, icons and
brand art, and three platform marks from [Simple Icons](https://simpleicons.org) (Android, Apple and
Tux, in `site/assets/platforms/`), released under CC0 1.0. The marks belong to their owners and only
label which download is for which system; the Windows mark there is four plain squares drawn for
Fuse.

Everything else visible or audible in Fuse (the Fuse mark, controller and status glyphs, theme
backgrounds, generated placeholder art and interface sounds) is original work drawn or synthesised in
code and is part of Fuse under GPL-3.0-or-later. The menu music is the one exception: it is
boipurple's work, shipped alongside Fuse as a separate work rather than part of it, the way the fonts
are.

## Data and conventions from other projects

Fuse uses facts about other software (which package and activity an emulator exports, which intent
extra it reads, how a library folder is named) to interoperate with it. Facts are not copied code,
but we credit every source:

| Source | Licence | What Fuse uses | How |
|---|---|---|---|
| [ES-DE](https://gitlab.com/es-de/emulationstation-de) `es_find_rules.xml` and `es_systems.xml` (Android, Linux, Windows and macOS) | MIT. Copyright (c) 2024-2026 Northwestern Software AB, (c) 2020-2024 Leon Styhre, (c) 2014 Alec Lofquist ([LICENSE](https://gitlab.com/es-de/emulationstation-de/-/blob/master/LICENSE)) | Emulator package and activity names, executables, Flatpak ids, intent actions, extras and flags, system folder names, directory-as-file and id-file conventions | Re-expressed as Kotlin data in `core/launch`; every entry cites its ES-DE rule in `source`. The XML files are not copied |
| [RomM](https://github.com/rommapp/romm) | AGPL-3.0 | Folder conventions (Structure A and B), platform slugs and aliases, file category names, never-game folder names and ignored file names | Used as facts in `core/library` and `core/model`. No RomM code is used |
| [rcheevos](https://github.com/RetroAchievements/rcheevos) | MIT | How RetroAchievements hashes ROMs: cartridge systems, Nintendo DS, and the PlayStation family's discs (ISO 9660 and boot executables) | Reimplemented in `RaHasher` and `RaDisc`; no rcheevos code is copied |
| [Cartridge](https://github.com/abdu2304/cartridge) by abdu2304 | MIT. Copyright (c) 2026 abdu2304 | The approach of matching emulator forks by words in the package name or label (its `FAMILIES` table); Fuse pairs with the app and installs it from the [MAtiyaaa/cartridge](https://github.com/MAtiyaaa/cartridge) fork until the bridge is merged upstream | Re-expressed in `AndroidFamilies`; the bridge protocol is a contract shared by both apps. Cartridge's mark (its `Logo.vue` path) and colours (`#EF4B23` on `#16171B`) are used as `FuseMarks.Cartridge` and `CartridgeBrand` wherever Fuse shows Cartridge. The licence is in `files/licenses/LICENSE-cartridge.txt` and in Settings, About, Licences |
| Emulator and launcher sources and vendor guides (Dolphin, PPSSPP, melonDS, Azahar, Eden, Vita3K, aPS3e, ARMSX3, Flycast, GameNative, Winlator Cmod, WinNative, Bannerlator, GameHub Lite and others) | Various | Intent actions, extras and exported activities, cited per adapter | Facts only; see [docs/research/emulators.md](docs/research/emulators.md) |
| Daijisho platform files, GlazedBelmont community ES-DE configs | No licence | Cross-checking facts only | Nothing copied |
| [Obtainium](https://github.com/ImranR98/Obtainium) by Imran Remtulla | GPL-3.0 | How a source's settings choose a release and a file: link finding and natural sorting on download pages, release filters and sort methods, version extraction templates, the processor-type filter | Reimplemented in `core/integrations` `obtainium/` (`PackResolver`, `HtmlLinks`, `ApkPicker`, `VersionText`) so the pack's entries resolve as their authors intend; no Obtainium code is copied |
| [Obtainium Emulation Pack](https://github.com/RJNY/Obtainium-Emulation-Pack) by RJNY | Public domain (Unlicense) | The Store's catalogue: each app, its source and the settings that find its releases | Downloaded at runtime and cached on the device. Copies of both editions (release v7.18.0) are test fixtures in `core/integrations/src/desktopTest/resources/obtainium`, with the links of a few apps' download pages saved there for the same tests |

## Online services

Content from these services is downloaded by each user at runtime, under the service's terms, and
cached on their device. None of it is part of Fuse's distribution.

| Service | Terms and documentation |
|---|---|
| RetroAchievements | [API documentation](https://api-docs.retroachievements.org/) and the site's rules |
| SteamGridDB | [Terms](https://www.steamgriddb.com/terms), [API](https://www.steamgriddb.com/api/v2) |
| IGDB (Twitch) | [API documentation](https://api-docs.igdb.com/), [Twitch Developer Services Agreement](https://legal.twitch.com/legal/developer-agreement/) |
| TheGamesDB | [API](https://api.thegamesdb.net/) |
| ScreenScraper | [API documentation](https://www.screenscraper.fr/webapi2.php) |
| Libretro thumbnails | [Repository](https://github.com/libretro-thumbnails/libretro-thumbnails) (no licence file; images belong to their owners). Fetched at runtime, never bundled |
| Art Book Next system art | [Repository](https://github.com/anthonycaccese/art-book-next-es-de), [CC BY-NC-SA 2.0](https://creativecommons.org/licenses/by-nc-sa/2.0/). Theme by Anthony Caccese; system logos modified from Dan Patrick's console logos; Noir artwork set by tenlevels with help from f8less; Outline artwork set by Joppa Fallston; some artwork by theUnBurn. Fetched at runtime from a pinned commit, never bundled, credited wherever it is shown |
| RetroArch Systematic icons | [Repository](https://github.com/libretro/retroarch-assets), [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). The pictures of each system on the Systems page's small tiles, by the libretro team. Fetched at runtime from a pinned commit, never bundled; only their file names are part of Fuse. Credited in Settings, Systems and in Licences |
| GitHub REST API | [Releases API](https://docs.github.com/en/rest/releases/releases), [GitHub Terms of Service](https://docs.github.com/en/site-policy/github-terms/github-terms-of-service) |
| Apps in the Store | Each app comes from the source the Obtainium Emulation Pack names (its developers' GitHub releases or download page), under that app's own licence, downloaded by the user. Fuse distributes none of them |

### Screenshots

The README and website screenshots (`docs/assets/screenshots`) are captures of Fuse running on an
AYN Thor with its author's own library. The game art in them is what Fuse fetched for that library
from the sources it fills art from, and the system art is from Art Book Next (CC BY-NC-SA 2.0,
credited as below). The screenshots are documentation: they are not part of Fuse's code or of its
downloads. The games, their names and their art belong to their owners, the Art Book Next art in
them stays under CC BY-NC-SA 2.0, and they are shown only to illustrate what Fuse does with a
library.

## Compatibility with GPL-3.0-or-later

| Licence | Where | Conclusion |
|---|---|---|
| Apache-2.0 | Kotlin, kotlinx, Compose Multiplatform, JetBrains Lifecycle, SQLDelight, Coil, Ktor, Typesafe Config, OkHttp, Okio, AndroidX, Media3, JavaCPP, SQLite JDBC, Skiko, libGDX Jamepad and gdx-jnigen-loader (Windows and macOS), Gradle | Compatible. The Free Software Foundation lists Apache-2.0 as compatible with GPL version 3, so Apache-2.0 code can be combined into a work distributed under GPL-3.0-or-later. Apache-2.0 asks redistributors to pass on its licence text and any NOTICE files |
| MIT | QR Code generator library, SLF4J API, Feather-derived Lucide icons, ES-DE (facts), rcheevos (reimplemented rules), Cartridge (approach) | Compatible. Permissive; the notices are kept in `files/licenses/LICENSE-qrcodegen.txt`, `files/licenses/LICENSE-slf4j.txt`, `files/licenses/LICENSE-lucide.txt` and `files/licenses/LICENSE-cartridge.txt` and credited above |
| ISC | Lucide icons | Compatible. Permissive; the copyright and permission notice is kept in `files/licenses/LICENSE-lucide.txt` |
| BSD-3-Clause | Skia inside Skiko (desktop) | Compatible. Permissive |
| zlib | SDL 2 inside Jamepad (Windows and macOS) | Compatible. Permissive; its notice is kept in `files/licenses/LICENSE-sdl.txt` |
| MS-RL | WiX Toolset (builds the Windows installer only) | Not shipped as part of Fuse's code; a build tool, like Gradle |
| LGPL-2.1-or-later | JLayer (desktop only) | Compatible. The LGPL lets a covered library be combined with a program under any licence, and section 3 of LGPL-2.1 also allows applying the GPL to it. Fuse uses the unmodified jar, which the AppImage keeps as a separate file that can be replaced; its licence text ships with Fuse |
| GPL-3.0-or-later | FFmpeg's GPL build (desktop only) | Compatible: the same licence as Fuse. The FFmpeg libraries ship unmodified, as the separate native libraries bytedeco publishes, with their source available from FFmpeg and the javacpp-presets build scripts |
| Public domain | SQLite | Compatible. No conditions |
| SIL OFL 1.1 | Sora and Manrope fonts | Compatible for distribution. The fonts stay under the OFL and are not relicensed; the OFL explicitly allows bundling the fonts with software under any licence as long as the fonts are not sold on their own and the licence travels with them. Fuse does not modify the fonts, and neither declares a Reserved Font Name |
| AGPL-3.0 | RomM | No code is used, only facts and names, which carry no licence obligations. (AGPL-3.0 and GPL-3.0 are also explicitly combinable under section 13 of each) |
| No licence | Daijisho files, community configs, libretro thumbnails | Nothing is copied or bundled with Fuse. The documentation screenshots show some libretro thumbnails, credited there (see Screenshots) |
| CC BY 4.0 | RetroArch Systematic icons | Compatible as used: the pictures are downloaded by each user at runtime and cached on their device, never bundled with Fuse. Fuse ships only the icons' file names, and shows the required attribution where the pictures are offered |
| CC BY-NC-SA 2.0 | Art Book Next system art | Not combined with Fuse. The files are downloaded by each user at runtime and cached on their device, never bundled with Fuse, so the NonCommercial and ShareAlike terms do not reach Fuse's own licence. Fuse shows the required attribution with the art. The documentation screenshots that show some of it credit it and leave it under its licence (see Screenshots) |

### Licence texts

The licence texts live in `ui/designsystem/src/commonMain/composeResources/files/licenses/`:
`GPL-3.0.txt` (Fuse's own licence, a copy of `LICENSE`), `Apache-2.0.txt` (the libraries under the
Apache License), `OFL-Sora.txt` and `OFL-Manrope.txt` (SIL OFL 1.1 with each font's copyright line),
`LICENSE-lucide.txt` (Lucide's ISC licence and Feather's MIT licence), `LICENSE-qrcodegen.txt` (the QR Code
generator library's MIT licence), `LICENSE-slf4j.txt` (SLF4J's MIT licence) and `LGPL-2.1.txt` (JLayer). Because they are Compose
resources, they are packaged inside both the APK and the AppImage, and Settings > About > Open-source
licences shows each of them. Libraries that carry their own NOTICE files keep them where their jars
and AARs put them; collecting those into the licences screen is on the roadmap.
