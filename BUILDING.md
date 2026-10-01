# Building Fuse

## Requirements

- **JDK 17 or newer** to run Gradle (the wrapper uses Gradle 9.8.0; all code targets JVM 17).
- **Android SDK** for anything Android, with the API 37 platform installed (`compileSdk` is 37,
  `targetSdk` 36, `minSdk` 28). With the command-line tools:

  ```sh
  sdkmanager "platform-tools" "platforms;android-37.0"
  ```

  Point Gradle at the SDK with `ANDROID_HOME` or a `local.properties` file containing
  `sdk.dir=/path/to/sdk` (it is git-ignored). The Android Gradle Plugin downloads the build tools it
  needs once the SDK licences are accepted (`sdkmanager --licenses`).
- **Linux, Windows or macOS** to run the desktop app. Gradle configures every module, including the
  Android ones, so keep the Android SDK available even for desktop builds. Each desktop package is
  built on its own system (jpackage can't build for another one).

Versions of everything else live in `gradle/libs.versions.toml`.

## Common tasks

| Task | Command | Output |
|---|---|---|
| Android debug APK | `./gradlew :app:android:assembleDebug` | `app/android/build/outputs/apk/debug/` |
| Install on a connected device | `./gradlew :app:android:installDebug` | |
| Android release APK | `./gradlew :app:android:assembleRelease` | `app/android/build/outputs/apk/release/` (see [signing](#release-signing)) |
| Run the desktop app | `./gradlew :app:desktop:run` | |
| Compile the desktop app | `./gradlew :app:desktop:compileKotlin` | |
| Compile the shell for desktop | `./gradlew :ui:shell:compileKotlinDesktop` | |
| AppImage (on Linux) | `scripts/build-appimage.sh` | `build/appimage/Fuse-<version>-x86_64.AppImage` |
| Windows installer and zip (on Windows) | `bash scripts/build-windows.sh` | `build/windows/Fuse-<version>-windows-x64.{msi,zip}` |
| macOS disk image (on a Mac) | `scripts/build-macos.sh` | `build/macos/Fuse-<version>-macos-<arm64 or x64>.dmg` |
| Self-test a desktop build | `./gradlew :app:desktop:run --args=--self-test` | a report; exit code 0 when it all works |

## Tests

Every shared module has two Kotlin Multiplatform targets: `android` and a JVM target named `desktop`
(`build-logic/src/main/kotlin/fuse.kmp.library.gradle.kts`). Tests in `commonTest` run on the desktop
JVM through the `desktopTest` task; tests that need the JVM (the JDBC SQLite driver in `core:data`, an
MD5 cross-check in `core:integrations`) live in `desktopTest` sources and run in the same task.

```sh
./gradlew allTests                      # every test in every module
./gradlew :core:library:desktopTest     # one module
./gradlew :core:launch:desktopTest
./gradlew :core:integrations:desktopTest
./gradlew :core:data:desktopTest
./gradlew :ui:link:desktopTest          # Phone Link: sign-in rules and the real server over HTTP
./gradlew :core:library:desktopTest --tests '*FilenameParserTest'   # one test class
```

Android host tests are not enabled for the shared modules yet (the AGP Kotlin Multiplatform library
plugin creates them only when a module opts in), so there are no Android unit test tasks for them.
`ui:designsystem` (the keyboard), `ui:shell` (the store end to end, with a real database and fake
platform services) and `ui:link` (Phone Link) have desktop tests too. Test reports are written to
`<module>/build/reports/tests/desktopTest/`.

## AppImage

`scripts/build-appimage.sh` builds the desktop app and packages it as
`build/appimage/Fuse-<version>-x86_64.AppImage`, with `<version>` read from `gradle.properties`.
It needs `curl` and network access the first time, to fetch `appimagetool`. Running an AppImage (including the packaging tool) needs the FUSE 2 library
(`libfuse2`, or `libfuse2t64` on Ubuntu 24.04 and later); where that is not possible, set
`APPIMAGE_EXTRACT_AND_RUN=1` (the script already does this for `appimagetool`).

## Windows

`scripts/build-windows.sh` runs in Git Bash on Windows 10 or 11 (as in CI) and builds:

- `Fuse-<version>-windows-x64.msi`, a per-user installer (`%LOCALAPPDATA%\Programs\Fuse`, no
  administrator prompt). Its upgrade code never changes, so a newer MSI replaces an older install.
  The Compose plugin downloads the WiX toolset it needs the first time.
- `Fuse-<version>-windows-x64.zip`, the same app with an empty `FuseData` folder next to `Fuse.exe`.
  When that folder exists, Fuse keeps its database, cache and settings there instead of in AppData.

Both are checked with `--self-test` first (once with the bundled Java, which prints the report, and
once through `Fuse.exe`). Controllers use SDL through libGDX Jamepad, which only the Windows and macOS
builds carry.

## macOS

`scripts/build-macos.sh` builds `Fuse.app` for the Mac it runs on (`arm64` on Apple silicon, `x64`
on Intel), signs it ad hoc (Apple silicon runs nothing unsigned), runs `--self-test`, and puts it on
`Fuse-<version>-macos-<arch>.dmg` next to a link to Applications. The app isn't notarized, so the
first launch needs right-click, Open. The bundle's version is `<major + 1>.<minor>.<patch>` (1.1.0
for 0.1.0), because macOS and Windows Installer don't accept a leading 0; Fuse itself shows
`fuse.version`. Apple silicon builds need macOS 11 or newer, Intel builds 10.15 or newer.

## Icons

`app/desktop/packaging/RenderIcon.java` draws Fuse's icon from the same geometry as `fuse.svg`:

```sh
java app/desktop/packaging/RenderIcon.java app/desktop/packaging/fuse.png 256
java app/desktop/packaging/RenderIcon.java app/desktop/packaging/fuse.ico
java app/desktop/packaging/RenderIcon.java app/desktop/packaging/fuse.icns
```

## Versions

`gradle.properties` holds the release identity:

| Property | Current value | Used for |
|---|---|---|
| `fuse.version` | `0.1.1` | Android `versionName`, the release tag `v0.1.1`, file names |
| `fuse.versionCode` | `7` | Android `versionCode`; must grow with every release |
| `fuse.releaseName` | `The Showcase Update` | Release title |

## Release signing

Release APKs are signed with the key described by four environment variables (in CI the release
workflow sets them from the `ANDROID_*` repository secrets described below):

| Variable | Meaning |
|---|---|
| `FUSE_KEYSTORE_PATH` | Path to the keystore file (`.jks` or `.p12`) |
| `FUSE_KEYSTORE_PASSWORD` | Keystore password |
| `FUSE_KEY_ALIAS` | Alias of the signing key |
| `FUSE_KEY_PASSWORD` | Password of the key |

```sh
export FUSE_KEYSTORE_PATH="$HOME/keys/fuse-release.jks"
export FUSE_KEYSTORE_PASSWORD=...
export FUSE_KEY_ALIAS=fuse
export FUSE_KEY_PASSWORD=...
./gradlew :app:android:assembleRelease
```

Without them the release build is signed with the debug key and Gradle prints a warning, so CI
still produces an installable APK. To create a key:

```sh
keytool -genkeypair -v -keystore fuse-release.jks -alias fuse -keyalg RSA -keysize 4096 -validity 10000
```

Keep the keystore and its passwords out of the repository (`*.jks`, `*.keystore`, `*.p12` and `*.pfx`
are git-ignored).
Losing the key means existing installs cannot update to your builds.

## Releases on GitHub

`.github/workflows/release.yml` publishes a release when:

- `gradle.properties` or anything in `docs/releases/` changes on `main` and no release `v<fuse.version>` exists yet;
- a tag `v*` is pushed (the tag must equal `v<fuse.version>`); or
- it is started by hand (Actions, Release, Run workflow), again only if the release does not exist.

It creates the release `v<version>` titled `Fuse <version> - <releaseName>`, with the notes from
`docs/releases/<version>.md`, as soon as the Android APK is built. The AppImage, the Windows MSI and
zip, and the macOS disk images (Apple silicon on `macos-15`, Intel on `macos-15-intel`) build at the
same time and are attached to the release as each finishes; one failing doesn't hold back the
others. A last job rewrites `SHA256SUMS.txt` for every file there. Started by hand with
`desktop_only`, the workflow builds the desktop packages again and adds them to the existing release
of the current version, without touching the APK. So a release is: add
`docs/releases/<version>.md`, bump `fuse.version`, `fuse.versionCode` and `fuse.releaseName`, and
merge to `main`.

Fuse announces an update only once the release has the build for the device it runs on, so a Mac
doesn't hear about a release before its disk image is attached.

Repository secrets used by the workflow (Settings > Secrets and variables > Actions > New repository
secret). Without them the APK is signed with a debug key and the workflow warns; phones that
installed a debug-signed build cannot update to a release signed with the real key.

| Secret | Content |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | The keystore file, base64 encoded (`base64 -w0 fuse-release.p12`) |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias |
| `ANDROID_KEY_PASSWORD` | Optional. Key password; defaults to the keystore password (PKCS12 keys share it) |

After building, the workflow checks with `apksigner` that the APK carries exactly that key's
certificate and fails the release otherwise, so every release can update the previous one. Keep a
backup of the keystore and its password somewhere safe: if they are lost, installed copies can only
move to a new key by uninstalling.

To create a key locally:

```sh
keytool -genkeypair -storetype PKCS12 -keystore fuse-release.p12 -alias fuse \
  -keyalg RSA -keysize 4096 -validity 18250 -dname "CN=Fuse, OU=Fuse, O=Fuse"
base64 -w0 fuse-release.p12   # the value of ANDROID_KEYSTORE_BASE64
```

`.github/workflows/ci.yml` runs every test suite and builds the debug APK for every pull request and
every push to a branch other than `main`. On Windows and on both kinds of Mac it also runs the shared
and desktop tests, builds the packages and self-tests them; the packages are kept as run artifacts
for 14 days, to try a change before it's released.

## Troubleshooting

- **"SDK location not found"**: set `ANDROID_HOME` or `sdk.dir` in `local.properties`.
- **Android platform 37 not found**: install `platforms;android-37.0` with `sdkmanager`.
- **Gradle runs out of memory**: `gradle.properties` gives the Gradle daemon 4 GB and the Kotlin
  daemon 3 GB; lower them on small machines, or close other builds.
- **Configuration cache problems after changing build logic**: run once with
  `--no-configuration-cache`.
