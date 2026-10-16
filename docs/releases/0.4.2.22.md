# Fuse 0.4.2.22 - The Native Update

Fixes for checking for updates and for the on-screen keyboard.

## New

- Nothing new in this release.

## Changed

- Check for Updates says when it couldn't reach GitHub, instead of saying Fuse is up to date.

## Fixed

- Check for Updates could say "Fuse is up to date" when a newer Fuse was out. GitHub answers only
  60 questions an hour from one network, and the Store's own checks can use them up; when it
  stopped answering, Fuse took that for "nothing new". Fuse now reads the latest release from
  GitHub's website when the API doesn't answer, still checks the download against the release's
  SHA-256, and says so when neither answers.
- On the on-screen keyboard, one press of X deleted two characters, and one press of A on a key
  typed it twice. A button that repeats while held acted when pressed and again when let go; it now
  acts once per press.
