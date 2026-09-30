# Changelog

Each Fuse release has its notes in `docs/releases/<version>.md`, with three sections: New, Changed
and Fixed. The release workflow publishes that file as the body of the GitHub release, so the notes
here, in the repository and on the [Releases page](https://github.com/MAtiyaaa/fuse/releases) are
always the same. [RELEASE_NOTES.md](RELEASE_NOTES.md) holds the notes of the latest release.

Versions follow `fuse.version` in `gradle.properties`, and every release is tagged `v<version>`.

| Version | Name | Notes |
|---|---|---|
| 0.0.1 | The First Update | [docs/releases/0.0.1.md](docs/releases/0.0.1.md) |

## Adding a release

1. Write `docs/releases/<version>.md` starting with `# Fuse <version> - <name>` and the sections
   `## New`, `## Changed` and `## Fixed`.
2. Copy it to `RELEASE_NOTES.md` and add a row to the table above.
3. Bump `fuse.version`, `fuse.versionCode` and `fuse.releaseName` in `gradle.properties` and merge to
   `main`. See [BUILDING.md](BUILDING.md#releases-on-github).
