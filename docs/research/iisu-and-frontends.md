# Research notes: iiSU and the 2026 Android frontend baseline (reference only)

Checked 2026-09-30. iiSU is closed source (package `com.iisulauncher`, latest Alpha 0.0.7.4, 2026-07-31; FAQ: the
Android app "is not currently planned to be open source"). Fuse uses none of its code, assets, wording or layouts.

## What iiSU is praised for

- "the prettiest out-of-box presentation on Android, with real motion design" (Held Games, 2026-07).
- "one of the most visually pleasing emulator frontends on Android" (Retro Dodo, 2026-06).
- Dual-screen mode: "an absurd level of care" (Steam Deck HQ).
- Criticism: overwhelming at first, alpha instability (settings resets, broken builds while set as home).

## Observed visual traits (estimated from official gallery stills)

- Light default: off-white background with a faint dot grid, translucent white glass panels, charcoal text.
- Selection: rounded-square tiles with a white 3-4 px frame; focus scales ~1.10-1.15x with a violet-to-sky gradient ring.
- Per-platform accent colours on tile borders and badges.
- Home tile grid 7-8 columns x 3 rows, 1x1 tile ~17% of screen height; a title pill at top centre.
- Status: glass pills with clock/date/battery and shoulder-button keycaps; two corner hint cards instead of a bar.
- Console list: focused platform tile doubles in size; entering a console slides the tile left as a breadcrumb.
- Motion described as "domino-style" cascades, "title swivel", "cinematic browsing" crossfades.
- Hero art washed out toward white; transparent logo centred on top.
- Sound: theme BGM, per-game soundbites, navigation SFX; feedback only when focus actually moves.

## How Fuse stays itself (decisions)

1. Dark-first, cinematic canvas: the focused game's hero is the room's lighting, not a washed-out wallpaper. Deep
   ink background, art-derived tint, no dot grid, no gradient ring.
2. Focus grammar: squircle tiles that lift (scale + tinted shadow) with a crisp inner light edge and a thin bar
   underneath, readable without colour. Spring physics, no bounce.
3. Motion signature: parallax depth (hero drifts slower than content), shared-bounds expansion into the game page,
   crossfades that never pass through black or white. No domino cascades.
4. Status: a single edge-hugging HUD line with tabular numerals, no pills and no keycaps.
5. Hints: one quiet bottom line of original button glyphs, right-aligned.
6. Sound: short procedural tones, pitch following movement direction; silent when focus cannot move.

## 2026 baseline features (every serious frontend has these)

Home-app mode + app drawer; controller-first with touch; ABXY swap and prompt styles; folder-name platform detection;
emulator database; multiple scrapers; icon/box/hero/logo/screenshot/video slots; RetroAchievements; playtime; Jump back
in; grid/list/carousel/XMB layouts; widgets; themes; SFX/BGM; dual-screen choice; Steam/PC via GameNative, GameHub and
Winlator shortcuts; backups; onboarding; updater.

Other frontends: ES-DE (desktop MIT, Android paid/partly closed), Daijisho (closed; its platform JSON repo has no
licence, do not copy), Pegasus (GPL-3.0), Beacon (closed), Cocoon (closed), Console Launcher (closed), Mimir (GPL-3.0).
