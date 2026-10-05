# Changelog

Each Fuse release has its notes in `docs/releases/<version>.md`, with three sections: New, Changed
and Fixed. The release workflow publishes that file as the body of the GitHub release, so the notes
here, in the repository and on the [Releases page](https://github.com/MAtiyaaa/fuse/releases) are
always the same. [RELEASE_NOTES.md](RELEASE_NOTES.md) holds the notes of the latest release.

Versions follow `fuse.version` in `gradle.properties`, and every release is tagged `v<version>`.

| Version | Name | Notes |
|---|---|---|
| 0.3.3 | The Together Update | [docs/releases/0.3.3.md](docs/releases/0.3.3.md) |
| 0.3.2 | The Corner Update | [docs/releases/0.3.2.md](docs/releases/0.3.2.md) |
| 0.3.1 | The Glide Update | [docs/releases/0.3.1.md](docs/releases/0.3.1.md) |
| 0.3.0 | The Sync & Clean Update | [docs/releases/0.3.0.md](docs/releases/0.3.0.md) |
| 0.2.9 | The Organized Update | [docs/releases/0.2.9.md](docs/releases/0.2.9.md) |
| 0.2.8 | The Media & Connectivity Update | [docs/releases/0.2.8.md](docs/releases/0.2.8.md) |
| 0.2.7 | The Swap & Clean Update | [docs/releases/0.2.7.md](docs/releases/0.2.7.md) |
| 0.2.6 | The Detail Update | [docs/releases/0.2.6.md](docs/releases/0.2.6.md) |
| 0.2.5 | The Ignition Update | [docs/releases/0.2.5.md](docs/releases/0.2.5.md) |
| 0.2.4 | The Install Update | [docs/releases/0.2.4.md](docs/releases/0.2.4.md) |
| 0.2.3 | The Other Fix Update | [docs/releases/0.2.3.md](docs/releases/0.2.3.md) |
| 0.2.2 | The Insignificant Update | [docs/releases/0.2.2.md](docs/releases/0.2.2.md) |
| 0.2.1 | The Store & Patch Update | [docs/releases/0.2.1.md](docs/releases/0.2.1.md) |
| 0.2.0 | The Everything Update | [docs/releases/0.2.0.md](docs/releases/0.2.0.md) |
| 0.1.6 | The Patch & Widget Update | [docs/releases/0.1.6.md](docs/releases/0.1.6.md) |
| 0.1.5 | The Craft Update | [docs/releases/0.1.5.md](docs/releases/0.1.5.md) |
| 0.1.4 | The Gallery Update | [docs/releases/0.1.4.md](docs/releases/0.1.4.md) |
| 0.1.3 | The Capture Update | [docs/releases/0.1.3.md](docs/releases/0.1.3.md) |
| 0.1.2 | The Visual Update | [docs/releases/0.1.2.md](docs/releases/0.1.2.md) |
| 0.1.1 | The Second Screen Update | [docs/releases/0.1.1.md](docs/releases/0.1.1.md) |
| 0.1.0 | The Showcase Update | [docs/releases/0.1.0.md](docs/releases/0.1.0.md) |
| 0.0.6 | The Android Games Update | [docs/releases/0.0.6.md](docs/releases/0.0.6.md) |
| 0.0.5 | The Box Art Update | [docs/releases/0.0.5.md](docs/releases/0.0.5.md) |
| 0.0.4 | The Enhancement Update | [docs/releases/0.0.4.md](docs/releases/0.0.4.md) |
| 0.0.3 | The Sound and Screens Update | [docs/releases/0.0.3.md](docs/releases/0.0.3.md) |
| 0.0.2 | The Polish Update | [docs/releases/0.0.2.md](docs/releases/0.0.2.md) |
| 0.0.1 | The First Update | [docs/releases/0.0.1.md](docs/releases/0.0.1.md) |

## Adding a release

1. Write `docs/releases/<version>.md` starting with `# Fuse <version> - <name>` and the sections
   `## New`, `## Changed` and `## Fixed`.
2. Copy it to `RELEASE_NOTES.md` and add a row to the table above.
3. Bump `fuse.version`, `fuse.versionCode` and `fuse.releaseName` in `gradle.properties` and merge to
   `main`. See [BUILDING.md](BUILDING.md#releases-on-github).
