# Fuse Native (future)

Fuse Native is a future way to play Steam games on Android from Fuse: a fork of
[GameNative](https://github.com/utkarshdalal/GameNative) (GPL-3.0) that Fuse drives completely. It
is on the roadmap, not in a release. Until then, Fuse starts the games GameNative exports
(`app.gamenative.LAUNCH_GAME`, INTEGRATIONS.md).

## How it should feel

1. **Chosen like any emulator.** On a Steam game in Fuse, Fuse Native is one of the emulators to
   play it with. Choosing it when it isn't installed downloads it from its own releases (checked by
   SHA-256 and its signing key, like every app Fuse installs); the Store offers it too. Android
   asks the person to confirm the install, as it does for every app.
2. **Straight into the game.** Fuse starts the game. A second app opening is fine, but it shows
   only the game, never a second home screen, library or sign-in, and closes back to Fuse when the
   game ends.
3. **Signed in through Fuse.** Steam's sign-in happens in Fuse: a QR code to scan with the Steam
   app, or a name and password with Steam Guard's code or an approval in the Steam app.
4. **Everything edited in Fuse.** A game's options and its container (every setting GameNative
   has: Wine or Proton, the graphics driver, DXVK and VKD3D, Box64 or FEX, screen size,
   environment variables, drives, controls and the rest) are edited in Fuse, with the same choices
   GameNative offers.
5. **GameNative understood.** A container is the same JSON (`.container`) in both apps, so Fuse
   reads and edits either. Moving from GameNative brings what Android lets it: games GameNative put
   on shared storage are taken over and checked instead of downloaded again, and a game's settings
   come over from GameNative's Export Config file. Steam's sign-in and GameNative's private storage
   (its default install folder) can't be read by another app, so those games download again and
   the person signs in once. Each game can stay on GameNative or move to Fuse Native.
6. **Says what it is.** Everywhere Fuse Native appears (its name, its README, its releases, its
   Store entry), it says it is a fork of GameNative and that GameNative's team doesn't support it.
   It appears offline on Steam by default and sends no analytics.

## What is known

- **Its package must be `app.fusenative`.** GameNative's Linux image has
  `/data/data/app.gamenative/files/imagefs` compiled into glibc, ld.so, ldconfig and over 200 other
  files (216 in `imagefs_gamenative.txz`), and two prebuilt libraries (`libevshim.so`,
  `libkgslshim.so`) name the package too. A package of exactly the same length lets every file be
  rewritten byte for byte as it unpacks; a longer one (such as Fuse's own) can't be.
- **Inside Fuse's own APK** is possible by licence (both are GPL-3.0) but costly: GameNative's
  debug APK is 617 MB, mostly asset packs, and its build (AGP 8.8, Kotlin 2.1, Hilt) differs from
  Fuse's (AGP 9.4, Kotlin 2.4). A separate app that shows only the game is the plan.
- **GameNative's Modern build** targets Android 16 (API 36) from Android 10 up; its Legacy build
  targets API 28 so Android lets it run programs from its own storage.
- **The launch** is GameNative's own intent (`app.gamenative.LAUNCH_GAME`, `game_source` STEAM,
  `app_id`), sent to Fuse Native's package.
- **Fuse talks to it** through a content provider that answers only Fuse's package signed with
  Fuse's release key, and Fuse talks only to the Fuse Native signed with Fuse Native's key, so a
  Steam password or code never reaches another app.
- **Its signing key** lives only in Fuse Native's repository secrets (`KEYSTORE`,
  `KEYSTORE_KEY_ALIAS`, `KEYSTORE_KEY_PASSWORD`, `KEYSTORE_STORE_PASSWORD`), never in a repository.

## Not decided yet

- Epic and GOG, which GameNative signs in to as well: through Fuse too, or Steam first.
