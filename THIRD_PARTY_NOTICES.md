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
| Ktor client core, content negotiation, kotlinx JSON serialization | `io.ktor:ktor-client-core`, `:ktor-client-content-negotiation`, `:ktor-serialization-kotlinx-json` | 3.6.0 | Apache-2.0 | `core:integrations` | [ktor](https://github.com/ktorio/ktor) |
| Ktor client OkHttp engine (with OkHttp and Okio) | `io.ktor:ktor-client-okhttp` | 3.6.0 | Apache-2.0 | Catalog only (planned for `app:android`) | [ktor](https://github.com/ktorio/ktor), [okhttp](https://github.com/square/okhttp) |
| Ktor client CIO engine | `io.ktor:ktor-client-cio` | 3.6.0 | Apache-2.0 | Catalog only (planned for `app:desktop`) | [ktor](https://github.com/ktorio/ktor) |
| Ktor client mock engine | `io.ktor:ktor-client-mock` | 3.6.0 | Apache-2.0 | Tests only | [ktor](https://github.com/ktorio/ktor) |
| AndroidX Activity Compose | `androidx.activity:activity-compose` | 1.13.0 | Apache-2.0 | `app:android` | [activity](https://developer.android.com/jetpack/androidx/releases/activity) |
| AndroidX Core KTX | `androidx.core:core-ktx` | 1.19.1 | Apache-2.0 | `app:android` | [core](https://developer.android.com/jetpack/androidx/releases/core) |
| Media3 ExoPlayer and UI | `androidx.media3:media3-exoplayer`, `:media3-ui` | 1.11.1 | Apache-2.0 | Catalog only (planned for video previews) | [media](https://github.com/androidx/media) |

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
| Lucide icon generator | `tools/icons/generate_icons.py` (part of Fuse) | | GPL-3.0-or-later | |

## Bundled assets

| Asset | Files | Licence | Copyright | Licence text |
|---|---|---|---|---|
| Sora typeface (Medium, SemiBold, Bold) | `ui/designsystem/src/commonMain/composeResources/font/sora_*.ttf` | SIL Open Font License 1.1 | Copyright 2019 The Sora Project Authors ([sora-font](https://github.com/sora-xor/sora-font)) | [licenses/OFL-Sora.txt](ui/designsystem/src/commonMain/composeResources/files/licenses/OFL-Sora.txt) |
| Manrope typeface (Regular, Medium, SemiBold, Bold) | `ui/designsystem/src/commonMain/composeResources/font/manrope_*.ttf` | SIL Open Font License 1.1 | Copyright 2018 The Manrope Project Authors ([manrope](https://github.com/googlefonts/manrope)) | [licenses/OFL-Manrope.txt](ui/designsystem/src/commonMain/composeResources/files/licenses/OFL-Manrope.txt) |
| Lucide icons 1.49.0 (167 icons, converted to path data) | `ui/designsystem/src/commonMain/kotlin/io/github/matiyaaa/fuse/ui/designsystem/icons/FuseIcons.kt` | ISC; the icons Lucide derived from Feather are also under MIT | Copyright (c) 2026 Lucide Icons and Contributors; Copyright (c) 2013-present Cole Bemis (Feather) | [licenses/LICENSE-lucide.txt](ui/designsystem/src/commonMain/composeResources/files/licenses/LICENSE-lucide.txt) |

Everything else visible in Fuse (the Fuse mark, controller and status glyphs, theme backgrounds,
generated placeholder art and interface sounds) is original work drawn or synthesised in code and is
part of Fuse under GPL-3.0-or-later.

## Data and conventions from other projects

Fuse uses facts about other software (which package and activity an emulator exports, which intent
extra it reads, how a library folder is named) to interoperate with it. Facts are not copied code,
but we credit every source:

| Source | Licence | What Fuse uses | How |
|---|---|---|---|
| [ES-DE](https://gitlab.com/es-de/emulationstation-de) `es_find_rules.xml` and `es_systems.xml` (Android and Linux) | MIT. Copyright (c) 2024-2026 Northwestern Software AB, (c) 2020-2024 Leon Styhre, (c) 2014 Alec Lofquist ([LICENSE](https://gitlab.com/es-de/emulationstation-de/-/blob/master/LICENSE)) | Emulator package and activity names, executables, Flatpak ids, intent actions, extras and flags, system folder names, directory-as-file and id-file conventions | Re-expressed as Kotlin data in `core/launch`; every entry cites its ES-DE rule in `source`. The XML files are not copied |
| [RomM](https://github.com/rommapp/romm) | AGPL-3.0 | Folder conventions (Structure A and B), platform slugs and aliases, file category names, never-game folder names and ignored file names | Used as facts in `core/library` and `core/model`. No RomM code is used |
| [rcheevos](https://github.com/RetroAchievements/rcheevos) | MIT | How RetroAchievements hashes ROMs for cartridge-based systems | Reimplemented in `RaHasher`; no rcheevos code is copied |
| [Cartridge](https://github.com/MAtiyaaa/cartridge) | MIT. Copyright (c) 2026 abdu2304 | The approach of matching emulator forks by words in the package name or label (its `FAMILIES` table) | Re-expressed in `AndroidFamilies`; the bridge protocol is a contract shared by both apps |
| Emulator and launcher sources and vendor guides (Dolphin, PPSSPP, melonDS, Azahar, Eden, Vita3K, aPS3e, ARMSX3, Flycast, GameNative, Winlator Cmod, WinNative, Bannerlator, GameHub Lite and others) | Various | Intent actions, extras and exported activities, cited per adapter | Facts only; see [docs/research/emulators.md](docs/research/emulators.md) |
| Daijisho platform files, GlazedBelmont community ES-DE configs | No licence | Cross-checking facts only | Nothing copied |

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
| GitHub REST API | [Releases API](https://docs.github.com/en/rest/releases/releases), [GitHub Terms of Service](https://docs.github.com/en/site-policy/github-terms/github-terms-of-service) |

## Compatibility with GPL-3.0-or-later

| Licence | Where | Conclusion |
|---|---|---|
| Apache-2.0 | Kotlin, kotlinx, Compose Multiplatform, JetBrains Lifecycle, SQLDelight, Coil, Ktor, OkHttp, Okio, AndroidX, Media3, SQLite JDBC, Skiko, Gradle | Compatible. The Free Software Foundation lists Apache-2.0 as compatible with GPL version 3, so Apache-2.0 code can be combined into a work distributed under GPL-3.0-or-later. Apache-2.0 asks redistributors to pass on its licence text and any NOTICE files |
| MIT | Feather-derived Lucide icons, ES-DE (facts), rcheevos (reimplemented rules), Cartridge (approach) | Compatible. Permissive; the notices are kept in `files/licenses/LICENSE-lucide.txt` and credited above |
| ISC | Lucide icons | Compatible. Permissive; the copyright and permission notice is kept in `files/licenses/LICENSE-lucide.txt` |
| BSD-3-Clause | Skia inside Skiko (desktop) | Compatible. Permissive |
| Public domain | SQLite | Compatible. No conditions |
| SIL OFL 1.1 | Sora and Manrope fonts | Compatible for distribution. The fonts stay under the OFL and are not relicensed; the OFL explicitly allows bundling the fonts with software under any licence as long as the fonts are not sold on their own and the licence travels with them. Fuse does not modify the fonts, and neither declares a Reserved Font Name |
| AGPL-3.0 | RomM | No code is used, only facts and names, which carry no licence obligations. (AGPL-3.0 and GPL-3.0 are also explicitly combinable under section 13 of each) |
| No licence | Daijisho files, community configs, libretro thumbnails | Nothing is copied or bundled |
| CC BY-NC-SA 2.0 | Art Book Next system art | Not combined with Fuse. The files are downloaded by each user at runtime and cached on their device, never bundled or redistributed, so the NonCommercial and ShareAlike terms do not reach Fuse's own licence. Fuse shows the required attribution with the art |

### Licence texts

The licence texts live in `ui/designsystem/src/commonMain/composeResources/files/licenses/`:
`GPL-3.0.txt` (Fuse's own licence, a copy of `LICENSE`), `Apache-2.0.txt` (the libraries under the
Apache License), `OFL-Sora.txt` and `OFL-Manrope.txt` (SIL OFL 1.1 with each font's copyright line)
and `LICENSE-lucide.txt` (Lucide's ISC licence and Feather's MIT licence). Because they are Compose
resources, they are packaged inside both the APK and the AppImage, and Settings > About > Open-source
licences shows each of them. Libraries that carry their own NOTICE files keep them where their jars
and AARs put them; collecting those into the licences screen is on the roadmap.
