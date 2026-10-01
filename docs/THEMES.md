# Themes

A Fuse theme is a small JSON file. It names a built-in theme to start from and changes what it
likes: colours, the background, corners, focus, motion, sounds, glass and the CRT effect. Anyone
can write one, share it as a link, and anyone can add it in **Settings, Appearance, Theme**.

## Add a theme

Open **Settings, Appearance, Theme** and choose **Add a theme**:

- **From a link or text.** Paste a link to a theme file, or the theme itself.
  - A GitHub file page or a gist works as it is; Fuse fetches the raw file.
  - Only `https` links are used.
- **From a file.** Pick a `.json` theme file on the device.
- **Find themes.** Opens the themes on [Fuse's website](https://matiyaaa.github.io/fuse/#themes).

Before anything changes, Fuse shows the theme's name and author and anything it had to repair.
Added themes live on the device: **Options, Remove** forgets one, and **Options, Copy as a theme
file** puts any theme, built-in ones included, on the clipboard as a file to start from.

## The smallest theme

```json
{
  "fuseTheme": 1,
  "name": "Night Ember",
  "extends": "starlight",
  "colors": { "accent": "#FF8A5B" }
}
```

Only `name` is required. Everything else comes from the theme in `extends` (Fuse when it is left
out).

## Everything a theme can say

| Key | What it is | Values |
|---|---|---|
| `fuseTheme` | The version of this format | `1` |
| `name` | Shown in the gallery | Up to 40 characters |
| `id` | Keeps one theme apart from another when names change | Letters, digits and dashes; made from the name when left out |
| `author` | Shown as "by ..." | Up to 40 characters |
| `tagline` | One line under the name | Up to 80 characters |
| `extends` | The built-in theme to start from | `fuse`, `glass`, `starlight`, `crossbar`, `orbital`, `wave`, `blades`, `channels`, `crt`, `daylight` |
| `dark` | A dark or a bright theme | `true` or `false`; worked out from the background when left out |
| `colors` | See [Colours](#colours) | |
| `background` | A style name, or an object: see [Background](#background) | |
| `corners` | How round tiles and panels are | `soft`, `round`, `sharp`, `pill` |
| `focus` | How the chosen tile is shown | `glow` (a halo in its colour), `ring` (an outline), `bar` (a bar under it) |
| `motion` | How much moves | `reduced`, `minimal`, `standard`, `enhanced` |
| `sound` | Navigation sounds | `off`, `soft`, `click`, `chime` |
| `glass` | Frosted panels | `true`, `false`, or `{ "enabled", "blur" (0 to 48), "opacity" (0.3 to 1) }` |
| `crt` | The CRT effect | `true`, `false`, or `{ "enabled", "scanlines" (0 to 0.6), "bloom", "curvature", "chromatic", "vignette" (0 to 1) }` |

### Colours

Colours are written as on the web: `#RGB`, `#RRGGBB`, or `#RRGGBBAA` with the transparency last.

| Key | Where it shows |
|---|---|
| `background` | The room everything sits in |
| `surface` | Panels, menus and sheets |
| `surfaceRaised` | Cards on panels and chosen rows |
| `accent` | The spark: the focus bar, progress, the main action |
| `accentSoft` | Soft tints of the accent (the accent at 20% when left out) |
| `onAccent` | Text on the accent (black or white, whichever reads better, when left out) |
| `text` | Titles and body text |
| `textMuted` | Details and captions |
| `focus` | The focus outline |
| `success`, `warning`, `danger` | Status colours; Fuse's own green, amber and red when left out |

### Background

```json
"background": { "style": "stars", "intensity": 1.2, "speed": 0.7, "secondary": "#FFC46B" }
```

| Key | What it is | Values |
|---|---|---|
| `style` | The background | `hero` (the game's art), `solid`, `wave`, `aurora`, `orbital`, `grid`, `stars`, `stripes` |
| `intensity` | How bright it glows | 0 to 1.5 |
| `speed` | How fast it moves; 0 holds it still | 0 to 2 |
| `secondary` | A second colour some backgrounds blend with the accent | A colour |

## Readable by design

Fuse keeps every theme readable and never refuses one over small things. It repairs what it can
and lists each repair when the theme is added:

- **Text.** Text needs a contrast of at least 4.5 to 1 on the background and on panels.
  - Muted text needs 3 to 1 on panels.
  - Text on the accent needs 4.5 to 1.
  - The focus outline needs 3 to 1 on the background.
  - Text that falls short is lightened (on dark themes) or darkened until it reads well.
- **Surfaces.** The background and panels are always solid, whatever their transparency.
- **Numbers.** Numbers out of range are kept in range.
- **Unknowns.** Unknown keys are skipped, and an unknown value keeps the starting theme's.
- **Size.** Files are at most 64 KB.

## Start from a built-in theme

Every built-in theme is in [`themes/presets`](themes/presets) exactly as Fuse writes it. Copy one,
change what you like, and keep the rest. Three examples to learn from are in [`themes`](themes):
[Ember Night](themes/ember-night.json), [Paper Mint](themes/paper-mint.json) and
[Deep Sea](themes/deep-sea.json).

## Share a theme

Put the file anywhere that serves it over `https` and share the link:

- a GitHub repository (the page link works);
- a gist;
- your own site.

To have a theme listed on Fuse's website, open a pull request that adds it to `docs/themes`.
