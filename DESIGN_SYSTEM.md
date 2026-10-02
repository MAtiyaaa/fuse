# Design system

Fuse should feel like a console home screen that belongs to you: a dark room, lit by the game you are
on. This document describes the design language and the values that implement it. Every number here
is read from `ui/designsystem/src/commonMain/kotlin/io/github/matiyaaa/fuse/ui/designsystem/`
(abbreviated `designsystem/` below); if the two ever disagree, the code wins and this page is out of
date.

## Contents

- [Identity](#identity)
- [Tokens](#tokens)
- [Colour roles](#colour-roles)
- [Typography](#typography)
- [Shapes](#shapes)
- [Focus: the spark](#focus-the-spark)
- [Motion](#motion)
- [Layout](#layout)
- [Artwork](#artwork)
- [Components](#components)
- [Iconography and glyphs](#iconography-and-glyphs)
- [Sound](#sound)
- [Themes](#themes)
- [Render quality](#render-quality)
- [Accessibility](#accessibility)

## Identity

- **A dark room lit by the game.** The focused game's hero art fills the screen behind everything,
  softened by scrims so text stays readable, and a low, wide glow in the art's or platform's colour
  lights the room from the bottom left. In the default theme, a focused tile's shadow is tinted with
  the same colour.
- **Objects, not boxes.** Tiles use continuous (squircle) corners and a thin light edge along the top,
  so they read as things you can pick up.
- **The spark.** Focus is a lift, a tinted glow, a single sweep of light and a short accent bar
  underneath, with an outline ring for high contrast. It never depends on colour alone.
- **Calm edges.** One edge-hugging HUD line at the top (Fuse's mark, icon tabs, status) and one quiet
  hint line at the bottom right. Nothing floats in pills.
- **Two faces.** Sora for display text and numbers, Manrope for everything you read.
- **Honest placeholders.** Games without art get generated art from their initials and platform
  colour, so every tile still looks intentional.
- **Original everything.** The Fuse mark, the button and status glyphs, the theme backgrounds and the
  interface sounds are drawn or synthesised in code. iiSU was studied as a reference for quality only
  ([RESEARCH.md](RESEARCH.md#iisu-reference-for-quality-only)); no code or assets were taken from it,
  nor from Sony, Microsoft or Nintendo.

**The Fuse mark** (`FuseMark` in `ui/shell/.../app/Hud.kt`) is a rounded frame with a lit fuse line
curving into it and a spark at its end, drawn in the text colour with the spark in the accent colour.

## Tokens

All in `designsystem/theme/Tokens.kt`. Screens never use literal spacing, radii or sizes.

### Space

| Token | dp | Token | dp |
|---|---|---|---|
| `hair` | 1 | `xl` | 24 |
| `xxs` | 2 | `xxl` | 32 |
| `xs` | 4 | `x3` | 48 |
| `s` | 8 | `x4` | 64 |
| `m` | 12 | `x5` | 96 |
| `l` | 16 | | |

`gutter` is 40 dp: content never comes closer to the screen edge. `gutterCompact` is 20 dp for narrow
or short screens (phones in portrait, small second screens).

### Radius

`xs` 4, `s` 8, `m` 12, `l` 18, `xl` 26, `pill` 999 (dp). Tiles do not use these: their corner is a
fraction of the tile's short side (see [Shapes](#shapes)).

### Sizes

| Token | dp | Use |
|---|---|---|
| `iconS`, `iconM`, `iconL`, `iconXL` | 16, 20, 24, 32 | Icons |
| `touch` | 48 | Minimum touch target |
| `hudHeight` | 64 | Top HUD line |
| `hintHeight` | 44 | Bottom hint line |
| `row`, `rowCompact` | 56, 44 | Menu and settings rows |
| `sparkWidth` x `sparkHeight` | 28 x 3 | The focus bar |
| `stroke`, `focusStroke` | 1, 2 | Hairlines, focus ring |

### Aspects (width / height)

| Token | Ratio | Use |
|---|---|---|
| `ICON` | 1 | Square tiles (the Box art layout, Home shelves), drawn with square box art |
| `BOX` | 0.72 | Portrait covers |
| `CAPSULE` | 2 / 3 | Portrait capsules |
| `GRID_WIDE` | 460 / 215 | Wide grid capsules |
| `HERO` | 1920 / 620 | Hero art |
| `SCREENSHOT` | 16 / 9 | Screenshots and video |
| `SYSTEM_CARD` | 1.45 | System cards |

### Tile metrics

`TileMetrics.forHeight(height, width)` sizes tiles from the window at runtime:

| Metric | Rule |
|---|---|
| Icon tile | 19% of the height, clamped to 84..168 dp (three rows fit under the hero band on a 720 dp handheld) |
| Capsule | 30% of the height, clamped to 120..260 dp |
| Cover | (width minus both gutters) / 7, clamped to 96..180 dp |
| System card | 26% of the width, clamped to 200..380 dp |
| Gap | 18% of the icon tile, clamped to 12..24 dp |

## Colour roles

Screens pick a role, never a raw colour (`designsystem/theme/Colors.kt`). A theme's `ThemePalette`
supplies the base colours and `FuseColors.from` derives the rest.

| Role | Meaning | Source |
|---|---|---|
| `ink` | The room: the darkest layer | palette background |
| `surface` | Panels, menus, sheets | palette surface |
| `surfaceRaised` | Panels on panels, selected rows | palette surfaceRaised |
| `hairline` | 1 dp edges and dividers | text colour at 9% (dark) or 12% (light) |
| `text`, `textMuted`, `textFaint` | Primary, secondary, tertiary text | palette text; secondary; secondary at 62% |
| `accent`, `accentSoft`, `onAccent` | The spark: primary action, progress, the focus bar. Used sparingly | palette |
| `focus` | Focus ring and tab outline | palette focusRing |
| `success`, `warning`, `danger` | States | `#3DD68C`, `#FFB547`, `#FF5D6C` |
| `scrim` | Dims content under overlays | background at 72% |

`tinted(tint, amount = 0.14)` mixes a surface toward a game's or platform's colour for cards that
belong to it. Platform accents in the catalog are original, desaturated hues, not brand colours.

## Typography

`designsystem/theme/Typography.kt`. Sora (Medium, SemiBold, Bold) sets display text and numbers; it is
wide and geometric and reads well at handheld distance. Manrope (Regular, Medium, SemiBold, Bold) sets
everything you read. Both are SIL Open Font License 1.1 and ship in
`designsystem/.../composeResources/font/`. Line heights are trimmed on both sides and centred, so text
aligns optically with icons.

| Style | Face | Weight | Size / line (sp) | Tracking (em) | Use |
|---|---|---|---|---|---|
| `hero` | Sora | Bold | 46 / 52 | -0.025 | Focused game's title when it has no logo |
| `display` | Sora | SemiBold | 32 / 38 | -0.02 | Screen titles |
| `title` | Sora | SemiBold | 22 / 28 | -0.01 | Section and card titles |
| `titleSmall` | Sora | SemiBold | 17 / 22 | -0.005 | Small titles |
| `body` | Manrope | Medium | 15 / 22 | 0 | Body text |
| `bodyStrong` | Manrope | SemiBold | 15 / 22 | 0 | Emphasis, active tab label |
| `label` | Manrope | SemiBold | 13 / 18 | 0.005 | Buttons, hints |
| `caption` | Manrope | Medium | 12 / 16 | 0.01 | Meta lines |
| `overline` | Manrope | Bold | 11 / 14 | 0.14 | Small uppercase section labels |
| `numeric` | Sora | SemiBold | 15 / 20 | 0 | Clock, counters (tabular figures) |
| `numericLarge` | Sora | SemiBold | 40 / 44 | -0.02 | Large statistics (tabular figures) |

**Text size** (Settings, Appearance) scales the whole scale: Large is 1.15 and Extra large 1.3 for the
Manrope styles and the small numbers, and half of that step for the Sora titles, which are already
large. Rows and cards grow with their text rather than clipping it.

## Shapes

`SquircleShape` (`designsystem/shape/SquircleShape.kt`) draws continuous corners: the curvature ramps
up gradually instead of jumping from a straight edge into a circle. Each corner is two cubic Bezier
curves either side of a shortened circular arc; `smoothing` 0 is a plain rounded rectangle and 0.6 is
the Fuse default. Tile corners are a fraction of the short side, so tiles keep their proportions at
any size. `PillShape` is a 50% rounded rectangle for buttons and tabs.

Each theme picks a corner family (`FuseGeometry.of`):

| Family | Tile corner (fraction of short side) | Panel | Control |
|---|---|---|---|
| `SOFT` | 0.20 | 18 dp | 12 dp |
| `ROUND` | 0.26 | 26 dp | 18 dp |
| `SHARP` | 0.06 | 6 dp | 4 dp |
| `PILL` | 0.30 | 26 dp | pill |

## Focus: the spark

`Tile` (`designsystem/components/Tile.kt`) is the building block of every browsable surface. Its
selected state is shown several ways at once, so it never relies on colour:

| Layer | What it does |
|---|---|
| Lift | The tile scales to the motion profile's focus scale (1.00 Reduced, 1.04 Minimal, 1.07 Standard, 1.08 Enhanced) on a firm spring (damping 0.82, stiffness 900) that settles without visible bounce; it snaps in Reduced. Pressing shrinks it by 3% |
| Tinted glow | In the `GLOW` focus style the shadow rises from 6 to 28 dp of elevation and is tinted with the tile's colour (the art's or platform's), ambient shadow at half strength. Other styles use a neutral shadow from 4 to 14 dp |
| Light edge | A 1 dp squircle hairline along the top, white fading from 16% (30% when focused) to transparent, so the tile reads as a raised object |
| Sweep | When focus arrives, one band of light (55% of the tile's width, white at 22%) sweeps across the tile once, in 520 ms, clipped to its shape. Standard and Enhanced motion only, and never in Low Power Mode |
| Accent bar | A 28 x 3 dp rounded bar in the accent colour, 7 dp below the tile, for the `GLOW` and `BAR` styles (1.4 x wider and 1.3 x taller in `BAR`). It grows out from its middle as the tile lifts |
| Ring | A 2 dp outline in the focus colour, 3 dp outside the tile, for the `RING` style, and added to every style when High contrast focus is on |

Buttons follow the same rule: the selected primary button gets a ring, and every selected button gets
one with High contrast focus. HUD tabs show focus as an outline, and the active tab as its label plus
a short accent underline.

### Crisp accents

The accent is a colour, never a light. Every accent shape is solid with clean edges (no halo, bloom
or glow), so the screen reads as a calm, finished object and the art does the lighting:

| Shape | Where |
|---|---|
| A short rounded bar | Under the focused tile and the active tab |
| A solid fill with round ends on a quiet track (`ProgressBar`); a short segment gliding along the track when the length isn't known (resting in the middle under Reduced motion) | Fills, downloads, updates, achievements |
| A round-capped arc on a faint ring (`ProgressRing`, `Spinner`) | Downloads, achievement completion, loading |

Section titles (`SectionLabel`) are small uppercase labels with an optional quiet count after them.

Panels share the tiles' lit top edge, so menus, dialogs and cards read as the same family of objects.

### Moving things by touch

`designsystem/focus/DragReorder.kt` moves items in any lazy list or grid the way a phone does:

- **Lift.** A hold of 0.7 times the long press (300 to 450 ms) lifts the item, 1.08 times bigger,
  over a 20 dp shadow.
- **Make room.** The others slide out of its way on a spring (damping 0.86, stiffness 420).
- **Let go.** The item glides into place (damping 0.78, stiffness 520).
- **Haptics.** Lifting, passing a place and dropping each have their own.
- **No flicker.** An item just passed waits until the finger leaves it before it can swap back. In a
  single row or column the held item slides along it only.
- **Edges.** Near the list's ends the list scrolls by itself. The held item takes the nearest place,
  and the scroll is pinned by index, so the list never jumps.
- **Handles.** With handles, only part of an item lifts it: a Home shelf by its title. Its own items
  keep their holds. With instant handles a touch on a grip lifts at once (lists made for ordering).
- **Ordering sheets.** `ReorderList` (`components/ReorderList.kt`) is a list made for putting things in
  order: every row has a grip, a locked row (Home in the section order) shows a lock and keeps its
  place, and with the controller A picks a row up, the D-pad moves it and A puts it down. Settings
  uses it for the section order, the scraper order and Flow's rows.

### The widget board

Channels is a board of widgets, like a phone's home screen. The grid is four cells across (two on a
phone held upright); a cell is 60% as tall as it is wide (86% upright), between 96 and 240 dp. A
widget is one to four cells across and one to three down, and `packBoard` (`focus/SpatialSelection.kt`)
places each in turn at the first place it fits, reading from the top left, so a small widget fills a
gap a large one left. Every face is designed for five shapes (`home/BoardFaces.kt`): one cell, a strip,
a column, a square of four and anything larger, and the room the face really has decides how many
covers, rows or chips fit. A new shape crossfades in.

Arranging: widgets wobble by under a degree (less for wider ones), each on its own beat, with a remove
badge at the top left corner and a resize arc at the bottom right. Moves and resizes reflow the board
on a spring (damping 0.78, stiffness 420); the widget under a finger never lags behind it. Resizing by
touch snaps in whole cells, with a haptic tick for each. Focused, a widget grows at most 6 dp a side,
lifted 10 dp, whatever its size.

## Motion

`designsystem/theme/Motion.kt`. Controller users move fast, so durations stay short and movement never
blocks input.

### Easings

| Name | Cubic Bezier | Use |
|---|---|---|
| `Standard` | (0.2, 0, 0, 1) | Most movement: quick start, long gentle landing |
| `Enter` | (0.05, 0.7, 0.1, 1) | Things arriving on screen |
| `Exit` | (0.3, 0, 0.8, 0.15) | Things leaving: accelerate away, never linger |
| `Fade` | (0.4, 0, 0.2, 1) | Crossfades between artworks |

### Durations (Standard profile, ms)

`INSTANT` 90, `FAST` 150, `BASE` 220, `SLOW` 320, `DELIBERATE` 420, `HERO` 380 (hero art change),
`SWEEP` 520 (focus light sweep).

### Profiles

The theme sets a `MotionProfile`; the user can override it (Settings, Appearance, Motion).

| | Reduced | Minimal | Standard | Enhanced |
|---|---|---|---|---|
| Focus scale | 1.00 (none) | 1.04 | 1.07 | 1.08 |
| Durations | `min(base, 120) x 0.6` (72 ms at most) | 0.75 x | 1 x | 1 x |
| Focus spring | Snap | Spring | Spring | Spring |
| Light sweep | No | No | Yes | Yes |
| Hero parallax | No | No | Yes | Yes |
| Animated backgrounds | No | No | Yes | Yes |
| Page slide | 0 | 3% | 6% | 8% |

Enhanced adds depth but keeps Standard's durations, so it never slows navigation down. **Reduced
motion** keeps only short fades: no scaling, sliding, parallax, sweeps or moving backgrounds. Lists
that follow the selection use a no-bounce spring (stiffness 520), or a short tween in Reduced.

### The hero backdrop

`HeroBackdrop` (`designsystem/media/HeroBackdrop.kt`) never flashes. While the selection is moving it
waits for it to rest (110 ms; the app's room waits 160 ms) so fast scrolling never queues dozens of
image decodes or crossfades. Box art behind the interface is blurred once per picture, on a copy an
eighth of the screen's size, and drawn scaled up; rooms crossfade by modulating their own alpha,
never through a buffer the size of the screen. The previous
art stays fully visible until the new art has decoded; then the new layer fades in over 380 ms while
settling from 103.5% to 100% scale over 620 ms. Under Reduced motion it only fades, in 72 ms.
Layers that never finished loading are dropped, at most three are kept, and everything under a fully
shown layer is released. On top sit a black dim, left and bottom gradients into the room colour, a
soft top gradient, and the radial glow in the art's colour. With parallax the art moves at 12% of the
content's scroll. Art-less items get a lit radial gradient in their colour instead of a flash.

## Layout

```
+--------------------------------------------------------------------------+
| [mark]  (icon) (icon) [icon Label] (icon)          wifi  battery 85%  9:41 |  HUD, 64 dp
|                                                                          |
|   Stage: logo or title, meta line                                        |
|                                                                          |
|   [tile] [TILE] [tile] [tile] [tile]                                     |
|            ---                                                           |
|                                                                          |
|                                         (A) Open   (Y) Search  (X) More  |  hint line, 44 dp
+--------------------------------------------------------------------------+
  <-- 40 dp gutter                                          40 dp gutter -->
```

- **HUD** (`Hud` in `ui/shell/.../app/Hud.kt`): 64 dp tall, inside the 40 dp gutters. Left: the Fuse
  mark (22 dp) and the section tabs. Inactive tabs are icons only; the active tab shows its label and
  an 18 x 2.5 dp accent underline. When controller focus moves up into the tabs the active one gets an
  outline and the shoulder-button glyphs appear either side; LB and RB switch sections from anywhere.
  Right: the status cluster.
- **Status cluster** (`StatusCluster`): Bluetooth (when shown and a device is connected), Wi-Fi strength as three
  arcs and a dot, battery as an outline with a level fill (a bolt when charging, the warning colour
  when low) with its percentage, and the time, all in `numeric` with tabular figures. Anything the
  platform does not report is left out. The clock updates once a minute.
- **Stage** (`Stage` in `ui/shell/.../components/Stage.kt`): the selected item's name, told big: its
  logo art when it has one, otherwise its title in the display face, with a quiet meta line.
- **Hint line** (`HintBar`): bottom right, 44 dp tall, glyph (22 dp) plus label for each available
  action, 16 dp apart. Changes crossfade so the line never jumps.
- **Gutters**: 40 dp, or 20 dp on compact screens. TV overscan-safe margins beyond the gutter are not
  implemented yet ([ROADMAP.md](ROADMAP.md)).

## Artwork

- **Artwork slots** (`MediaKind`): icon (1:1), box art (0.72), grid (460:215), hero (1920:620), logo,
  screenshot, video, border. Users can set a focal point and zoom so a hero crops where they want.
- **Game art** (Settings, Appearance): game tiles show square box art (the default) or posters, 2:3
  tiles of about the same area (0.8 times as wide, 1.2 times as tall) that show the portrait cover,
  else the square art drawn whole over a soft copy of itself.
- **Fitted art** (logos, icons, badges) is decoded at the size it is laid out in, so SVG logos stay
  sharp at any size.
- **Generated art** (`designsystem/media/GeneratedArt.kt`) fills any slot without art. It is
  deterministic: the same title always gives the same composition. A gradient from the platform accent
  into near-black, a soft light whose position comes from the title, and faint diagonal lines for
  texture. Icon and system slots show two initials in the display face, skipping small words ("The
  Legend of Zelda" becomes LZ, "Tetris" becomes TE); box, wide and hero slots set the full title.
- **Theme backgrounds** (`designsystem/background/AmbientBackground.kt`): Wave, Aurora, Orbital,
  Grid, Stars, Stripes and Solid, all drawn in code with no image assets. A theme sets how bright and
  how fast its background is, and a second colour to blend with the accent. They animate slowly at no more than 30 frames per
  second and are drawn once and left still when motion or power settings say so.
- **CRT overlay** (`CrtOverlay`): scanlines, a phosphor stripe mask, centre glow and edge darkening as
  one cached, static layer. Every strength is capped so text stays readable, and it is skipped in Low
  Power Mode.

## Components

| Component | File | Notes |
|---|---|---|
| `Tile` | `components/Tile.kt` | The focus spark, see above |
| `FuseButton`, `IconButton` | `components/Buttons.kt` | Pill buttons: primary, secondary, ghost and danger |
| `Toggle`, `SliderBar`, `ProgressBar`, `ProgressRing`, `Spinner`, `Chip`, `StatusDot` | `components/Controls.kt` | |
| `MenuRow`, `MenuList` | `components/Menu.kt` | Settings and option menus |
| `ReorderList` | `components/ReorderList.kt` | Lists made for putting things in order |
| `Panel` | `components/Panel.kt` | Surfaces, optional glass |
| `Overlay` | `components/Overlay.kt` | Modal sheets over a scrim |
| `ProblemOverlay` | `ui/shell/app/ProblemSheet.kt` | Something that went wrong or needs a decision: an icon well tinted by severity, what it means, a reassurance line, actions, and technical details on request |
| `TextPreviewOverlay` | `ui/shell/app/TextPreview.kt` | Text to read before it is saved or copied (the diagnostics report) |
| `ToastHost` | `components/Toast.kt` | Short notices |
| `HintBar` | `components/HintBar.kt` | The hint line |
| `StatusCluster`, `BatteryGlyph`, `WifiGlyph` | `components/Status.kt` | Status glyphs |
| `OnScreenKeyboard` | `components/Keyboard.kt` | Controller text entry |
| `FText`, `SectionLabel` | `components/Text.kt` | Text with theme styles |
| `Artwork`, `GeneratedArt`, `HeroBackdrop` | `media/` | Art loading, placeholders, the room |
| `AmbientBackground`, `CrtOverlay` | `background/` | Theme backgrounds and CRT |
| `FuseIcon`, `FuseIcons`, `ButtonGlyph` | `icons/` | Icons and controller glyphs |
| Selection models, `FollowSelection` | `focus/` | See [ARCHITECTURE.md](ARCHITECTURE.md#selection-models) |
| `InputRouter`, `InputLayer` | `input/` | See [ARCHITECTURE.md](ARCHITECTURE.md#input-routing) |

Fuse draws its own focus and press feedback, so Compose's ripple indication is turned off everywhere
(`NoIndication`).

### Screenshots and recordings

`shell/capture/CaptureOverlay.kt` draws what a capture shows of its own. None of it is ever in the
picture: the controller hides it and waits 150 ms before a screenshot or a recording starts.

| Piece | Look |
|---|---|
| Countdown | A 132 dp ink circle in the middle of the screen, a 64 sp numeral that scales in as it changes (fades only under Reduced motion), and a thin accent ring that runs down once each second. Under it, a pill says Screenshot or Recording starts, with the Camera or CircleDot icon. Shown and hidden at once, never faded, so it can't linger into the picture |
| Flash | White at 32 % fading out over 340 ms after each screenshot, like a shutter. Left out under Reduced motion |
| Saved card | Bottom left above the hint line: a 128 x 72 dp picture of the capture (a play badge on recordings), a success check, "Screenshot saved" or "Recording saved" and where it went. It stays 3.5 s |
| Recording chip | The top line's activity chip with the CircleDot icon, an attention dot and a ring filling towards the 30 minute limit; selecting it stops the recording |

## Iconography and glyphs

- **One icon set.** Interface icons are [Lucide](https://lucide.dev/license) line icons (ISC; a few
  are derived from Feather, MIT), vendored as path data into `designsystem/icons/FuseIcons.kt` by
  `tools/icons/generate_icons.py`, which lists every icon Fuse uses (305 today). Do not hand-edit the
  generated file and do not mix in other icon sets.
- **One stroke weight.** Every icon is a 24 x 24 viewport stroked at 1.8 (slightly lighter than
  Lucide's 2, to match the typography at handheld sizes) with round caps and joins, tinted with the
  current text colour. Sizes come from the `icon*` tokens.
- **Original controller glyphs** (`ButtonGlyph`): face buttons are small discs with a letter (Xbox or
  Nintendo lettering, following the layout swap) or plain geometric shapes for the PlayStation style;
  shoulders, triggers and keyboard keys are keycaps with their label; the D-pad is an outlined cross;
  menu and view buttons are simple line drawings. No console maker artwork is used.
- **Status glyphs** (battery, Wi-Fi, Bluetooth) and the Fuse mark are drawn in code.

## Sound

Interface sounds are synthesised at runtime by `ToneSynth` (`designsystem/sound/UiSounds.kt`): short
16-bit mono PCM tones at 44.1 kHz with a 4 ms attack and an exponential decay. No recorded or console
sounds ship with Fuse. Each platform plays the samples through its own audio API: `AudioTrack` on
Android, and one shared `javax.sound` line on Linux that is released after a few seconds of silence,
so a game never finds the audio device busy.

| Cue | Sound |
|---|---|
| `MOVE`, `MOVE_UP`, `MOVE_DOWN` | One 38 ms note; moving up raises the pitch, moving down lowers it |
| `SELECT` | Two rising notes |
| `BACK` | Two falling notes |
| `OPEN`, `CLOSE` | Three notes rising, or falling |
| `TOGGLE` | One short high note |
| `BUMP` | One quiet low note when the selection cannot move |
| `ERROR` | Two low falling notes |
| `LAUNCH` | Three rising notes |
| `ACHIEVEMENT` | A four-note arpeggio |

Sound profiles change the timbre: **Soft** (sine with a little octave), **Click** (brighter harmonics,
faster decay), **Chime** (a bell-like inharmonic partial) or **Off**. Feedback follows what actually
happened (`NavResult`): a move sound only when the selection moved, a soft bump when it could not,
never a move sound at an edge. There is a master volume and a separate navigation volume.

## Themes

A theme changes more than colour: background renderer, corner family, focus style, motion profile,
navigation style, sound profile, glass and CRT (`ThemeSpec` in `core:model`, presets in
`designsystem/theme/ThemePresets.kt`). All presets are original designs with Fuse's own names; none
uses console trademarks or artwork.

| Theme | Tagline | Mode | Accent | Background | Corners | Focus | Motion | Sound | Extras |
|---|---|---|---|---|---|---|---|---|---|
| Fuse (default) | Dark room, lit by the game you're on | Dark | `#FF6A3D` | Hero | Soft | Glow | Standard | Soft | |
| Glass | Frosted panels over your art | Dark | `#8CCBFF` | Hero | Round | Ring | Standard | Soft | Glass panels (blur 28, opacity 0.64), violet second light |
| Starlight | A slow drift of stars | Dark | `#B79CFF` | Stars | Round | Glow | Standard | Chime | Sky-blue nebula |
| Crossbar | Sections across, items down | Dark | `#9FC3FF` | Wave | Sharp | Bar | Standard | Chime | Crossbar navigation, brighter ribbon |
| Orbital | Quiet light circling in the dark | Dark | `#7C8CFF` | Orbital | Soft | Glow | Enhanced | Soft | A far field of stars |
| Wave | A slow ribbon of light at dusk | Dark | `#FF8F7A` | Wave | Soft | Glow | Standard | Chime | Plum room, gold ribbons |
| Blades | Bold panels, sharp edges, green light | Dark | `#86DC5C` | Aurora | Sharp | Ring | Standard | Click | Teal second light |
| Channels | Bright tiles you arrange yourself | Light | `#1779BC` | Stripes | Pill | Ring | Standard | Soft | |
| CRT | Scanlines and phosphor glow | Dark | `#FFB02E` | Grid | Sharp | Bar | Standard | Click | CRT overlay |
| Daylight | Fuse in a bright room | Light | `#C9431F` | Hero | Soft | Ring | Standard | Soft | Warm paper |

Every preset meets the contrast the theme format asks of community themes (text 4.5:1 on the room
and on panels, muted text 3:1, text on the accent 4.5:1, the focus outline 3:1), checked by a test.

### Community themes

Anyone can write a theme: a small JSON file that names a preset in `extends` and changes what it
likes. The format, its limits and the repairs Fuse makes are in [docs/THEMES.md](docs/THEMES.md);
the codec is `ThemeCodec` in `core:model`. Settings, Appearance, Theme shows every theme as a live
preview drawn in that theme, and adds one from a link, pasted text or a file. Added themes are kept
as they were written (`AppearanceSettings.customThemes`), so parts a later Fuse understands survive.

What inspired some of them, in general terms: **Crossbar** and **Wave** recall the horizontal
cross-menus and flowing light ribbons of mid-2000s media consoles; **Blades** recalls the bold,
sliding dashboard panels of the same era; **Channels** recalls late-2000s boards of rounded channel
tiles; **CRT** recalls tube televisions. They borrow a feeling, not a layout or an asset.

Users can override a theme's motion, glass and CRT settings. Glass defaults: 24 dp blur, 72% surface
opacity, hero brightness 0.85. CRT defaults: scanlines 0.35, bloom 0.15, curvature 0.08, chromatic
aberration 0.1, vignette 0.3.

## Render quality

Effects scale with the device, never the other way round (`RenderQuality` and `CapabilityProfile` in
`core:model`). The device tier comes from legitimate device information measured once (RAM, cores,
media performance class, low-RAM flag), never from a list of device names. The Automatic profile maps
Low, Mid and High tiers to these settings; the user can pick one directly, and Low Power Mode always
wins:

| | Low Power | Balanced | High Quality |
|---|---|---|---|
| Video previews | No | Yes | Yes |
| Blur | No | If supported | If supported |
| Animated backgrounds and sweep | No | Yes | Yes |
| Particles | No | No | Yes |
| CRT | No | Allowed | Allowed |
| Art prefetch (each side) | 2 | 4 | 6 |
| Hero decode size (long edge) | 1280 px | 1920 px | 2560 px |
| Status refresh | 60 s | 30 s | 15 s |

Low Power Mode keeps navigation exactly as quick; it only drops effects.

## Accessibility

- **Never colour alone.** Focus is always a change of size plus a bar or ring (in Reduced motion, where
  nothing scales, the bar or ring alone); the active tab shows its label; battery state has a glyph
  and a percentage.
- **High contrast focus** (Settings, Appearance) adds an outline ring to every focused tile and
  button, whatever the theme.
- **Reduced motion** (Settings, Appearance, Motion) removes scaling, sliding, parallax, sweeps and
  moving backgrounds and shortens fades to 72 ms or less.
- **Text size.** Type sizes are in sp, so on Android they follow the system font size, and Fuse's
  own Text size (Default, Large, Extra large) scales them further; see [Typography](#typography).
- **Screen edges.** For TVs that cut off the picture's edges, Screen edges keeps everything 2, 4 or 6
  percent clear of every edge while the background still fills the screen.
- **Problems say what they mean.** Severity is told by the words and an icon as well as its tint:
  healthy, a note, needs attention, or broken. A reassurance line says when nothing was changed.
- **Input.** Everything works with a controller, a keyboard or touch; the touch target token is 48
  dp. The button layout (Xbox, Nintendo or PlayStation) sets the hint glyphs and where confirm sits;
  "Swap confirm and back" swaps the keys themselves, and "Detect my buttons" sets both. Repeat delay and speed, stick deadzone and push threshold, hold time
  and vibration are adjustable (Settings, Inputs). The input router supports per-button remapping
  (`InputProfile.remap`), but there is no remapping screen yet.
- **Sound and haptics are optional** and never carry information that is not also on screen.
