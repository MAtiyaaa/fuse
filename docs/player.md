# Fuse Player

Fuse Player is Fuse's own video and music player. It is not tied to any one service: a provider
(today, Jellyfin) turns an item into something to play, and the player does the rest with its own
controls, subtitles and reporting.

## Modules

| Module | What it holds |
|---|---|
| `core:playback` | Fuse's playback models, with no UI and no provider types: `PlayItem`, `PlaySource`, `AudioTrack`, `SubtitleTrack` and how each subtitle arrives (`SubtitleDelivery`: a file Fuse draws, embedded in the stream, or drawn into the picture by the server), `PlayRequest`, `PlaybackEvent`, the `PlaybackResolver` a provider implements, `Capabilities` (what the engine can decode), and Fuse's subtitle parser (SRT, WebVTT and ASS/SSA with styles). |
| `ui:player` | The player: the `PlayerEngine` interface, `PlayerSession` (one per process, holding the engine, the queue, the resolver and the reports), `PlayerScreen` (controls, sheets, up next, music) and `SubtitleLayer`, which draws every subtitle on every platform. |

## Engines

| Platform | Engine | Notes |
|---|---|---|
| Android | `Media3Engine` | ExoPlayer with HLS, drawing to a `SurfaceView`. Capabilities come from `MediaCodecList`, with HDR only where the display and a decoder both support it. Hardware decoding off prefers software decoders. |
| Desktop (Windows, macOS, Linux) | `FfmpegEngine` | FFmpeg through JavaCPP (bytedeco). Separate threads read, decode video and decode audio. Video is decoded on the GPU where FFmpeg can (VAAPI or VDPAU, D3D11VA or DXVA2, VideoToolbox) with a software fallback, and turned into BGRA for Compose. Audio goes through a filter graph (resample, stereo, speed) to `javax.sound`, which is the clock; without a sound card a wall clock is used. Subtitles are decoded by FFmpeg (text, ASS with its styles, PGS and DVD pictures). The system's HTTP proxy is passed on. It never claims HDR, so a server tone-maps for it. |

Only the FFmpeg natives for the operating system being packaged are bundled. FFmpeg is the GPL
build; see `THIRD_PARTY_NOTICES.md`.

## Controls

- **Controller:** A plays or pauses (or presses the focused control). B hides the controls, then
  leaves. Left and Right seek while the controls are hidden (holding goes further: the seek
  interval, then 30 seconds, then a minute) and move between buttons while they show. Up and Down
  move between the timeline and the buttons. LB and RB go to the previous and next episode, song or
  chapter. LT and RT skip by the seek interval. X opens the settings.
- **Touch:** a tap shows or hides the controls, a double tap on either side skips, and the timeline
  drags. Buttons are at least 48 dp.
- **Desktop:** moving the mouse shows the controls. F or Alt+Enter toggles full screen.

Controls fade after the timeout set in Settings, unless paused, buffering or a sheet is open. Music
keeps its controls on screen.

## Two screens

On a device with two screens, `PlayerPlacement` says where the picture is: with Fuse's menus, or
on the other screen. It starts where Settings, Jellyfin, Films play on asks (the main screen or
the second screen) and is swapped while playing by Play here, from either screen. The screen
without the picture shows `PlayerRemote` (the poster, the time, the timeline, play and pause,
skips, tracks, Play here and Stop):

- **Menus on top:** the picture on the main screen and the remote on the second, or the picture on
  the second screen (`PlayerPicture`, a tap offers it back) while the main screen's remote lets the
  menus browse (B), with a Playing entry in the top line to bring the remote back.
- **Menus below (flipped):** the picture above with the touch screen as the remote, or the picture
  on the touch screen with the menus, while the screen above shows what is playing
  (`PlayerNowShowing`).

The remote takes the controller where it is the menus' screen: A plays or pauses, Left and Right
skip, LB and RB go to the previous and next, Y swaps the screens, X stops, B goes back to browsing.

## Sheets

- **Audio:** the stream's sound tracks, with codec and channels.
- **Subtitles:** Off, then each track. A track the server must draw into the picture says so;
  choosing it asks the server again from the same moment.
- **Settings:** speed, subtitle size, position, timing and background, and "About this stream"
  (how it plays: Direct Play, Direct Stream or Transcode with the reason, the decoder, the size).
- **Queue (music):** every song in the queue; choosing one plays it.

## Music

Music shows the album large with the song, the artist and album, and what plays next. Wide
screens put the art beside the words; narrow ones above them. Repeat cycles through off, the whole
queue and the one song.

## Recovery

A stream that fails is resolved again from where it stopped: the provider tries the other way to
the server first (home or outside), then asks for a converted stream. "Try again" does the same
by hand.

## Reports

The session reports start, progress (every ten seconds and on pause), and stop to the provider,
which is how resume points and watched marks stay in step with the server.
