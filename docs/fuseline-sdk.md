# Fuseline SDK

Fuseline is Fuse's animation engine, and it can be used in any Compose Multiplatform app. It has no
dependency on anything else of Fuse's: not the launcher, its store, its themes or its design system.
This page is how to use it in your own app. How it works inside is [docs/fuseline.md](fuseline.md).

## Version

**Fuseline 4.1.0**, released with Fuse 0.4.2. Fuseline has its own version (`fuseline.version` in
`gradle.properties`), separate from Fuse's, and follows semantic versioning:

- a patch release (4.1.x) fixes behaviour and never changes an API;
- a minor release (4.x) adds APIs and may deprecate some, never removing them;
- a major release (5) may remove what was deprecated, with the migration written here.

## Platforms

| | |
|---|---|
| Kotlin | 2.4 or later |
| Compose Multiplatform | the version in this repository's `gradle/libs.versions.toml` (Fuseline is built and tested against it) |
| JVM desktop | Java 17 or later; Linux, Windows and macOS |
| Android | the `minSdk` in `build-logic` (Fuseline itself needs nothing newer than Compose does) |

Fuseline's tests run on the JVM (`./gradlew :ui:fuseline:desktopTest`). Its benchmarks have been
measured on Linux only so far; see [Speed](fuseline.md#speed) for what was and wasn't measured.

## Getting it

Until it is on Maven Central, use one of these:

```sh
# Into your computer's local Maven repository
./gradlew :ui:fuseline:publishToMavenLocal
```

```kotlin
// settings.gradle.kts of your app
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
}

// build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.matiyaaa.fuseline:fuseline:4.1.0")
        }
    }
}
```

Fuse's releases also publish it to GitHub Packages (`https://maven.pkg.github.com/MAtiyaaa/fuse`),
which needs a GitHub token with `read:packages` to read. Or include this repository as a composite
build and depend on `:ui:fuseline` directly.

## A first animation

```kotlin
import io.github.matiyaaa.fuse.ui.fuseline.*

@Composable
fun Card(selected: Boolean) {
    // A value that follows its target: a new target mid-move carries on from where it is shown,
    // at the speed it has. It never jumps.
    val lift by fuselineFloat(if (selected) 1.06f else 1f, spring(stiffness = 600f), label = "lift")
    Box(Modifier.graphicsLayer { scaleX = lift; scaleY = lift }) { /* ... */ }
}
```

Many things coming in together (Fuseline 4.1): give them one cue, and read it where they draw.

```kotlin
@Composable
fun Grid(items: List<Item>) {
    val cue = rememberCue(items)
    LazyVerticalGrid(GridCells.Adaptive(160.dp)) {
        itemsIndexed(items) { i, item ->
            val delay = 25 * minOf(i, 8)
            LaunchedEffect(cue) { cue.join(delay + 360L) }
            Tile(item, Modifier.graphicsLayer {
                val p = cue.progress(delay, 360, Curves.Enter)
                alpha = p
                translationY = (1 - p) * 24f
            })
        }
    }
}
```

## What is stable

Stable (kept as they are until a major release): curves (`Curve`, `CubicCurve`, `Curves`), motions
(`Tween`, `Spring`, `Decay`, `Snap`, `Keyframes`, `Repeating`, `Sequence`, `Parallel` and their
builders), `FuselineValue` and its operations, the following values (`fuselineFloat`, `fuselineDp`,
`fuselineColor` and the rest), converters, `Appear`, `Swap`, `Crossfade`, `MotionTransition`,
`Timeline`, `Cue`, `rememberCue`, `FuselineMotion`, `Durations`.

Experimental (may change in a minor release): gestures (`dragMotion`, `StickDrive`, `ScrollDrive`),
layout motion and shared elements (`motionBounds`, `sharedMotion`), `FramePacing`, and the tools
(`MotionTrace`, `MotionInspector`).

Internal (not for apps): anything marked `internal`, and the `v1` to `v31` engines kept in the tests.

## From Fuseline 4 to 4.1

Nothing to change: every API of Fuseline 4 is in 4.1 and moves exactly as it did (the equivalence
tests that compare 4 with 3.1 still run). 4.1 adds:

- **Cues** (`Cue`, `rememberCue`). One clock that many parts of an entrance play from. Where Fuse
  revealed a page of tiles with one move per tile (a coroutine and a value each, every frame), each
  tile now reads the page's cue in its draw layer: one frame callback for the whole page, and a frame
  of the entrance redraws layers without recomposing or laying anything out. A part reads exactly
  where its own move would have been (`CueTest`).
- **A publishable SDK**: Maven coordinates, a version of its own, and this page.

## Tests you can run

```sh
./gradlew :ui:fuseline:desktopTest                                     # every test
./gradlew :ui:fuseline:desktopTest --tests '*MotionBenchmark' -Pfuse.bench=true   # the benchmarks
```

`OwnMotionTest` keeps Compose's own animation APIs out of Fuseline (one bridge aside), so what you
use is Fuseline's.

## Licence

Fuseline is part of Fuse and shares its licence: **GPL-3.0-or-later**. An app that includes it is a
work based on it and must be distributed under the GPL too. If that doesn't suit your app, ask the
maintainers before using it.
