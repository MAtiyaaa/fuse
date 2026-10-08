# Fuse 0.4.1 - The Convergence Update

Convergence builds on Fuseline 4, bringing display, profile, library and save foundations closer
together across Fuse devices.

## New

- **Import Saves.** Inspect a save file, folder or ZIP, review detected games and emulator formats,
  correct uncertain matches and copy selected saves into the existing save system. Current saves
  receive safety history before replacement; successful imports enter Fuse Sync history and queue
  while offline. Unsupported formats remain unmatched.
- **Max refresh rate.** Display maximum is the default. A separate display cap selects the highest
  supported native-resolution rate within the chosen maximum, independently of performance quality.
  Diagnostics distinguish requested, active and measured rates on each Fuse-controlled display.
- **GameTDB cover fallback.** Identified GameCube and Wii disc IDs can use a no-key cover source,
  with attribution and bounded requests. Other GameTDB systems are not guessed from incompatible IDs.
- **Rebuild Steam Integration.** Linux can repair Fuse-owned shortcuts and restore missing bundled
  artwork while keeping unrelated shortcuts and custom artwork.

## Changed

- **Fuse Library** is the name of the household game library throughout the current interface.
- Desktop accelerated rendering retains static drawing commands; software rendering keeps a
  separate bounded raster policy. Artwork uses physical-pixel buckets and focus headroom.
- Profile settings carry durable per-setting revisions, preserving newer choices across late sync
  and startup writers. Household matching prioritizes strong identities and rejects conflicting
  serials instead of merging similar names.
- Scraping coalesces repeated work, promotes visible requests and reuses confident provider IDs.
- Proven legacy Switch update/DLC rows become archived content of their base title, with selective
  preservation of user intent and history. Structured PS4/PS5 roots contain their internal resources.
- Standby is shared across the display session. Waking consumes the wake input and held repeats.
- Developer onboarding runs in an isolated disposable environment with fake profiles and services.
- Ordinary profile switching uses a short interruptible presentation; first welcome remains separate.

## Acceptance

This branch remains under integration and physical acceptance testing. No SteamOS, TV, Android
hardware or GPU presentation performance result is claimed by these notes. See the engineering
acceptance record in docs/audits/0.4.1-convergence.md before treating the update as shipped.
