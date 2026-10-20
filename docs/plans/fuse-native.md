# Fuse Native

Fuse Native is Fuse's own app for Windows and Steam games on Android. It runs Windows games and
apps, signs into Steam and appears offline by default, and keeps Steam's achievements. Fuse offers
it during setup and installs it from the Store, and its games are Fuse's Steam system.

It is a build of [GameNative](https://github.com/utkarshdalal/GameNative) (GPL-3.0), not a new
runtime. GameNative already does the hard parts: it signs into Steam with JavaSteam, downloads and
runs Windows games through its Winlator-based containers, unlocks achievements, and exports its
games for a frontend. Fuse already starts those exports (`app.gamenative.LAUNCH_GAME`,
INTEGRATIONS.md).

## What the build changes

Each change below is cited from GameNative's `main` branch (version 1.3.0 in its build file) as of
October 2026.

| Change | Where | Why |
|---|---|---|
| Its own package, `io.github.matiyaaa.fusenative` | `app/build.gradle.kts`, `applicationId = "app.gamenative"` | Installs beside GameNative, updates on its own |
| Name and icon: Fuse Native, `docs/assets/brand/fuse-native-mark.svg` | `app/src/main/res` (launcher icon, `app_name`) | The app Fuse offers looks like Fuse |
| Appear offline by default | `PrefManager.personaState`, which reads `getPref(PERSONA_STATE, EPersonaState.Online.code())` | Starts as `EPersonaState.Invisible`; the profile dialog still offers Online, Away and Invisible (`ProfileDialog.kt`) |
| No analytics | The release workflows pass `POSTHOG_API_KEY` and `POSTHOG_HOST` | Fuse sends nothing about how people play |
| Launch intent kept as `app.gamenative.LAUNCH_GAME`, with Fuse Native's package | `IntentLaunchManager.kt` | Fuse starts it as it starts GameNative |
| Releases signed with Fuse Native's own key | The release workflows read `KEYSTORE`, `KEYSTORE_KEY_ALIAS` and their passwords | Android only updates an app with the same signature |

## What Fuse does

- **Setup on Android** has a Steam games step (0.4.3.1). It offers GameNative today, says how to
  appear offline in it, and points its Export for Frontend at the games folder's `steam` folder.
  Once Fuse Native is released, the same step offers Fuse Native first.
- **The Store** lists Fuse Native from its own releases, checked by SHA-256 like every other app.
- **The Steam system** holds the exported games and starts them in Fuse Native
  (`AndroidPcDefs`, with Fuse Native's package added beside `app.gamenative`).
- **Achievements** are Steam's own: Fuse Native unlocks them in your account as GameNative does.

## What it needs from you

1. A repository for the build, for example `matiyaaa/fuse-native`, forked from GameNative. Its
   licence (GPL-3.0) asks that the build's source is public.
2. A signing key, kept as that repository's secrets and never in any repository.
3. Your go-ahead to publish its releases. Fuse's Store and setup only offer it once a signed
   release exists.

None of this is done yet: the step in setup offers GameNative until Fuse Native has a release.
