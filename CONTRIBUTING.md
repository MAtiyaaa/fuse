# Contributing to Fuse

Thank you for helping. Bug reports, device reports ("this emulator launches on my handheld"),
documentation fixes and code are all welcome. For anything larger than a small fix, please open an
issue first so we can agree on the approach before you spend time on it.

Be kind and assume good faith. Fuse is a hobby project maintained in spare time.

## Setting up

1. Install JDK 17 or newer and the Android SDK with the API 37 platform ([BUILDING.md](BUILDING.md)).
2. Clone the repository and open it in IntelliJ IDEA or Android Studio with Kotlin Multiplatform
   support.
3. Check that everything builds and passes:

   ```sh
   ./gradlew allTests :app:android:assembleDebug :app:desktop:compileKotlin
   ```

Read [ARCHITECTURE.md](ARCHITECTURE.md) before changing structure, and
[DESIGN_SYSTEM.md](DESIGN_SYSTEM.md) before changing anything visible.

## Module boundaries

Dependencies only point down the graph in [ARCHITECTURE.md](ARCHITECTURE.md#module-graph). In
practice:

- **`core:model`** holds data types only: no I/O, no platform code, no dependencies besides
  kotlinx.serialization.
- **`core:library`** reads files through `FuseFileSystem` and never writes, never uses the network.
- **`core:launch`** is pure: adapters describe a `LaunchPlan` and never start anything, read files or
  change emulator configuration. New emulators are data (`AndroidEmulatorDef`, `LinuxEmulatorDef`),
  not new branches in a `when`.
- **`core:integrations`** talks to the network through the shared `HttpClient` and returns
  `ApiResult`s; it never stores anything. Storage decisions belong to the data layer.
- **`core:data`** owns the database and settings; it never uses the network.
- **`ui:designsystem`** knows only `core:model`. No library, launch or network code.
- **`ui:shell`** talks to the rest of Fuse only through `FuseStore` and `PlatformUi`.
- **`app:android` and `app:desktop`** implement `FuseServices` and `PlatformUi` and contain
  everything that needs Android or desktop APIs. Keep them thin.
- Keep platform APIs out of `commonMain`. Prefer an interface implemented by the app over
  `expect`/`actual`.

## Code style

- Kotlin official code style (`kotlin.code.style=official`), four-space indents, and the formatting of
  the surrounding code.
- Write KDoc for public types and functions that says why, not only what. Cite the source for any
  fact about another app (an emulator's intent, a RomM convention) in the code, as the catalogs do.
- No blocking calls on the main thread: use `suspend` functions and the IO dispatcher.
- Never log or print credentials, and never put them in an intent, a URL you store, the database or
  settings. Use `SecretStore` and `Secret`.

### Interface text and assets

- **No em dashes in interface text.** Use commas, parentheses or full stops. (This applies to docs and
  commit messages too.)
- **No emojis** in the interface.
- **Icons come from the Lucide set only.** Add the Lucide file name to `ICONS` in
  `tools/icons/generate_icons.py`, regenerate `FuseIcons.kt` with it, and never hand-edit the
  generated file or mix in another icon set. Controller and status glyphs are drawn in code.
- **Nothing from console makers or closed frontends**: no logos, button artwork, sounds, fonts,
  layouts or wording from Sony, Microsoft, Nintendo, iiSU or others, and nothing copied from
  repositories without a licence (for example Daijisho's platform files). Facts about how an app
  accepts a launch are fine, with their source.
- Use colour roles, spacing, radius and type tokens, never literal values.

## Honest features

Fuse never claims to do something it does not do:

- If Fuse cannot know something, it says Unknown, never a guess. Firmware in a folder Fuse cannot read
  is Unknown, not Missing; a performance metric Fuse cannot measure is absent, not zero.
- If an emulator has no documented per-game launch, the adapter opens the app and says why
  (`LaunchPlan.OpenAppOnly`). Give every adapter a `source` and a `Confidence`; entries that are not
  confirmed by ES-DE, the emulator's source or its vendor's documentation must not claim a per-game
  launch.
- Fuse never writes into a user's game folders, never renames, moves or deletes their files, and never
  replaces art the user chose.
- Documentation describes what the code does. If a feature is partial, the docs say so.

## Tests

Tests are required for changes to parsing, scanning, launching and data:

| Change | Where to test |
|---|---|
| File names, folders, playlists, platform detection, BIOS checks | `core/library/src/commonTest` (use `InMemoryFileSystem`) |
| Emulator definitions, target resolution, launch plans | `core/launch/src/commonTest` (see `Fixtures.kt`) |
| Provider clients, matching, the Cartridge protocol | `core/integrations/src/commonTest` (use Ktor's `MockEngine` through `TestHttp.kt`) |
| Schema, migrations, repositories, settings | `core/data/src/desktopTest` (JDBC SQLite driver) |

Run `./gradlew allTests` before opening a pull request.

**Database changes** add a migration (`.sqm`) and a new schema snapshot in
`core/data/src/commonMain/sqldelight/databases/`; migrations are verified by the build. A migration
must never drop user data (custom titles, favourites, play time, collections, custom art).

## Commits and pull requests

- One logical change per commit. Subject line in the imperative mood ("Add melonDS nightly
  package"), at most 72 characters, no trailing full stop. Use the body to explain why.
- No emojis and no em dashes in commit messages.
- Fill in the pull request template: summary, changes, how you tested (device, Android version or
  distribution, emulator versions), and screenshots for visible changes.
- Never include API keys, passwords, tokens or personal paths in commits, logs or screenshots.

## Licence

Fuse is licensed under GPL-3.0-or-later. By contributing you agree that your contribution is
licensed under the same terms. New dependencies and assets must have a GPL-3.0-compatible licence;
add them to [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) in the same pull request.
