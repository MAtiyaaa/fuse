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
- **Linux desktop** to run the desktop app. Gradle configures every module, including the Android
  ones, so keep the Android SDK available even for desktop builds.

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
| AppImage | `scripts/build-appimage.sh` | `build/appimage/Fuse-<version>-x86_64.AppImage` |

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
./gradlew :core:library:desktopTest --tests '*FilenameParserTest'   # one test class
```

Android host tests are not enabled for the shared modules yet (the AGP Kotlin Multiplatform library
plugin creates them only when a module opts in), so there are no Android unit test tasks for them.
`ui:designsystem` and `ui:shell` have no tests yet. Test reports are written to
`<module>/build/reports/tests/desktopTest/`.

## AppImage

`scripts/build-appimage.sh` builds the desktop app and packages it as
`build/appimage/Fuse-<version>-x86_64.AppImage`, with `<version>` read from `gradle.properties`
<!-- verify -->. Running an AppImage (including the packaging tool) needs the FUSE 2 library
(`libfuse2`, or `libfuse2t64` on Ubuntu 24.04 and later); where that is not possible, set
`APPIMAGE_EXTRACT_AND_RUN=1` <!-- verify -->.

## Versions

`gradle.properties` holds the release identity:

| Property | Current value | Used for |
|---|---|---|
| `fuse.version` | `0.0.1` | Android `versionName`, the release tag `v0.0.1`, file names |
| `fuse.versionCode` | `1` | Android `versionCode`; must grow with every release |
| `fuse.releaseName` | `The First Update` | Release title |

## Release signing

Release APKs are signed with the key described by four environment variables <!-- verify -->:

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

Without them the release build is not signed with the release key <!-- verify -->. To create a key:

```sh
keytool -genkeypair -v -keystore fuse-release.jks -alias fuse -keyalg RSA -keysize 4096 -validity 10000
```

Keep the keystore and its passwords out of the repository (`*.jks` and `*.keystore` are git-ignored).
Losing the key means existing installs cannot update to your builds.

## Releases on GitHub

`.github/workflows/release.yml` publishes a release when:

- `gradle.properties` changes on `main` and no release `v<fuse.version>` exists yet;
- a tag `v*` is pushed (the tag must equal `v<fuse.version>`); or
- it is started by hand (Actions, Release, Run workflow), again only if the release does not exist.

It builds the Android APK and the AppImage in parallel, writes `SHA256SUMS.txt`, and creates the
release `v<version>` titled `Fuse <version> - <releaseName>` with the notes from
`docs/releases/<version>.md`. So a release is: add `docs/releases/<version>.md`, bump `fuse.version`,
`fuse.versionCode` and `fuse.releaseName`, and merge to `main`.

Repository secrets used by the workflow (all optional; without them the APK is not signed with the
release key and the workflow warns about it):

| Secret | Content |
|---|---|
| `FUSE_KEYSTORE_BASE64` | The keystore file, base64 encoded (`base64 -w0 fuse-release.jks`) |
| `FUSE_KEYSTORE_PASSWORD` | Keystore password |
| `FUSE_KEY_ALIAS` | Key alias |
| `FUSE_KEY_PASSWORD` | Key password |

`.github/workflows/ci.yml` runs the core tests, builds the debug APK and compiles the desktop app for
every pull request and every push to a branch other than `main`.

## Troubleshooting

- **"SDK location not found"**: set `ANDROID_HOME` or `sdk.dir` in `local.properties`.
- **Android platform 37 not found**: install `platforms;android-37.0` with `sdkmanager`.
- **Gradle runs out of memory**: `gradle.properties` gives the Gradle daemon 4 GB and the Kotlin
  daemon 3 GB; lower them on small machines, or close other builds.
- **Configuration cache problems after changing build logic**: run once with
  `--no-configuration-cache`.
