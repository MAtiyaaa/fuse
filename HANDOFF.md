# Fuse 0.4.2.1 handoff

You have **one hour** from your first message. Work the list below **in order**. Each item has a time
box: when it runs out, commit what works (with its test) and move on. At 55 minutes stop starting
new work, push, and write the report described at the end. Never trade correctness for speed on
saves: an unfinished save fix is left out, never shipped half done.

## Ground rules (from the owner, all binding)

- **Agents:** no subagents or Agent tool, ever. Do everything yourself.
- **Text:** no em dashes anywhere, in code, UI, docs or commits (use commas, parentheses or
  periods). No emojis in the UI. Icons from Lucide only.
- **Must not break or overwrite:** Android must not regress. Never overwrite saves. Fuse
  Intelligence stays read-only for outside data.
- **Honesty:** no claim without a test or a measurement. Anything not verifiable here (a Steam
  Deck, the AYN Thor, a Mac, a TV) is said to be unverified.
- **Naming and scope:** the tab is called "Sync". Nothing outside this list: note anything else
  you find in the PR instead of fixing it.

## Where things are

- **Branch and PR:** branch `fuse-0.4.2.1`, PR #60 (open, marked in progress). Push to that
  branch only.
- **Build:** `ANDROID_HOME=/root/android-sdk ./gradlew --no-configuration-cache --max-workers=3 -q <tasks>`
- **Tests:** results go to `*/build/test-results/desktopTest/*.xml`, except app/desktop, which uses
  `build/test-results/test`.
- **Audit:** `:ui:shell:desktopAudit -Pfuse.audit.dir=DIR "-Pfuse.audit.only=group/scenario" -Pfuse.audit.sizes=M`.
  It passes when `manifest.json` has `"uncovered": []`. Look at the PNGs yourself.
- **Perf:** `:ui:shell:desktopPerf -Pfuse.perf.dir=DIR -Pfuse.perf.only=home,series,...`
- **Release check:** `scripts/check-release.sh`. The notes must be identical in
  `docs/releases/<v>.md`, `RELEASE_NOTES.md` and
  `ui/designsystem/src/commonMain/composeResources/files/release-notes.md`. CHANGELOG needs a
  row, and README needs `> **Fuse <v> "<name>"` and `<sub><v> is the`.
- **Commit footer:**
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: <your session URL>
  ```

Already on the branch (do not redo): Steam restart in Game Mode, the Store redesign and Updates
screen, processor and graphics checks, Xenia and Vita3K achievements, EmuCoreX packs and cheats,
details for other devices' games, widget resize lag, and drag to scroll on Linux and Windows touch
screens (`ui/designsystem/.../input/DragScroll.kt`).

## The list, in order

### 1. Switch save not found with Eden's Save Data Directory (10 min)

**What the owner saw:** after choosing Eden's Save Data Directory, Fuse said it found no save in
`.../Emulation/saves/switch/user/save/0000000000000000/<profile>/01007820196A6000`.

**Cause (from Eden's `src/core/file_sys/savedata_factory.cpp`):** Eden prefers newer layouts when
they exist, and `SwitchNand.locate` in `core/sync/.../SaveAdaptersMore.kt` never reads them:
- an account save: `user/save/account/<profile uuid, 32 lowercase hex>/<title id & ~0xFF, 16 upper hex>/0`;
- a device save: `user/save/device/<title id & ~0xFF>/0`.

The legacy layout is still `user/save/0000000000000000/<user id hi><lo>/<title id>`. Device saves
there use the all-zero user.

**Fix:**
- Look in all four places. The save root is the folder holding the files (for the new layouts,
  the `/0` folder).
- Prefer whichever has the game.
- When none has it, say where Fuse looked and that the game may not have saved yet.

**Test:** fixture tests in `core/sync` for each layout, plus the case where nothing is found.

### 2. DS save not found (10 min)

**What the owner saw:** "Cooking Mama: save not sent. Fuse found no save for it in
/storage/emulated/0/Emulation/roms/nds" (Android, AYN Thor).

**Fix:**
- Find which DS adapters exist (`SaveAdapters*.kt`) and where each Android DS emulator really
  saves, from its source: melonDS Android (a chosen save folder, else beside the ROM, `.sav`) and
  DraStic (`DraStic/backup/<name>.dsv`).
- Fix the lookup so the real save is found.
- The message must name the setting to change in that emulator.

**Test:** with a fixture.

### 3. Sync tab shows the raw filename and no art (5 min)

**What the owner saw:** "Your games on the host" shows `[ROMsNS] Red Dead Redemption v0
(01007820196A6000) (BASE)` with an "RD" placeholder, although the library parsed the game.

**Fix:** use the library's title and art for the game (`ui/shell/.../sync/SyncTab.kt` and its
store).

**Test:** one test.

### 4. Home: the left stick breaks it and the widgets vanish (8 min)

**What the owner saw:** the recording shows the Home page blank, with only its page dots.

**Fix:**
- Reproduce with stick input (`StickDrive` / the router's stick path) on `ChannelHome` /
  `HomePages`.
- Find the cause, fix it, and add the test.

### 5. Top line: tabs join together and jump while switching (5 min)

**What the owner saw:** switching tabs quickly, the active tab's pill ("System", "Libr") overlaps
the next icons, and the content falls out of step with the pill.

**Fix:** in the HUD tab row (`ui/shell/.../app/Hud.kt`), the pill and its label must move as one,
and the content must follow the pill.

**Check:** a frame-sequence audit.

### 6. Library All: the count and the spacing (6 min)

- **Count:** All shows the same number as Installed (62). All must count every game once, across
  this device, RomM and other devices.
- **Spacing:** the band kept for the chosen game's title under Recently Added leaves a big gap
  above Systems. Use one shelf rhythm across All, Home and the household pages
  (`LibraryEverywhere.kt`).

### 7. Small fixes (2 to 3 min each)

- **Available on:** the "Available on" heading and its first device card are clipped (game
  details). Fix, and check with an audit.
- **Second screen:** its Systems widget names the wrong system.
- **Quick settings:** far too big on the handheld, especially the top tabs. Scale it like the rest
  of the UI (`ui/shell/.../quick/`).
- **Games that need you:** games already updated stay listed. Clear them once updated.

### 8. Linux: rebuild what it looks and feels like (the rest of the hour, at least 10 min)

**What the owner said:** "a mess, ugly, the quality is shit, the animations run like ass". A full
rebuild does not fit in an hour, so do the triage that matters most and list the rest:
1. **Rendering:** check how the desktop app renders on Linux (Skiko render API: OpenGL versus a
   software fallback, vsync, the frame pacing in `app/desktop/.../system/`). A software fallback
   explains both the quality and the motion. Make sure it uses the GPU, and fall back only when
   it must, saying so in Developer options.
2. **Measure:** run `desktopPerf` on Home, Library and Systems. Find the costliest effects (blur,
   glass, shadows, large gradients) and cut or cheapen them on Linux, keeping Android unchanged.
3. **Look:** audit Home, Library, a game page and Settings at the Steam Deck's 1280 by 800. Fix
   the worst spacing, text size and clipping you see.
4. **Report:** write in the PR what a real rebuild of Linux needs (a list with estimates), and
   what you measured before and after.

### 9. If time remains

- **Recording glitches:** the Installed grid shows blank tiles while scrolling. A segment switch
  flings tiles to the right edge. The On Other Devices shelf reorders under the selection.
- **One page per system:** a system in All opens one page with On this device, On other devices
  and On RomM, and systems only on other devices join the Systems shelf. Today `openSystem` in
  `LibraryEverywhere.kt` opens only one source.

## Decky (waiting on the owner)

The owner reported that Decky stopped working, their themes are gone, and Decky says this isn't
SteamOS. What was found:
- **What Fuse 0.4.2 writes:** only `~/homebrew/plugins/Fuse` (through pkexec), Steam's
  `shortcuts.vdf` (backed up first) and art in Steam's `grid` folder. Nothing in CSS Loader's
  themes folder, Decky's own files or Steam's settings.
- **The message:** neither Decky Loader's source nor CSS Loader's has a "not SteamOS" message.
- **The only Fuse code inside Steam** is the Fuse plugin's frontend (`tools/decky/fuse/src`,
  which patches the library's context menu).
- **The test given to the owner:** move `~/homebrew/plugins/Fuse` aside and restart
  `plugin_loader`.

If the owner reports back that the plugin was the cause, make its frontend fail safe: wrap all
patching, never throw from `definePlugin`, and add a kill switch. Otherwise leave it.

## Moved to 0.4.3 (do not start)

- **Original-prompt leftovers:** household join by code, easier Fuse One sign-in, the Switch
  details page, Home's ROMs and date widgets, quick menu tiles, glass that eases off under load,
  CI count gates, Fuseline follow-ups.
- **Partial items:** Dolphin and RPCS3 settings inside Fuse, display hot-plug, sleep and wake,
  Hub admin page, remote diagnostics.
- **Nexus Mods:** needs Fuse registered with Nexus (the owner's decision).

## Release (only if the list is done; otherwise leave the PR marked in progress)

1. Set the version to 0.4.2.1, code 49, in `gradle.properties`.
2. Write the notes three times (identical), add the CHANGELOG row and the README lines.
3. Run `scripts/check-release.sh`.
4. Run the full suite.
5. Push, watch CI on PR #60 until it is green, then tell the owner it is ready to merge.

## Your report at the end (one hour)

- **Per item:** done (with its commit), partly done (what works, what doesn't), or not started.
- **Measurements:** what was measured, with numbers.
- **Unverified on hardware:** anything you couldn't check on a real device.
- **No padding.** Say plainly what didn't fit.
