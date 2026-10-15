# Fuse's menu music

The songs Fuse plays in its menus, by boipurple, from the albums "jam channel" and "creature
interchange". Opus at 64 kbps (variable) in WebM, made by `tools/music/encode.sh` on the main branch.

Two of them (puddleworld and alright apothecary) ship inside Fuse. Fuse fetches the others from here
the first time they are wanted, checks each against the size and sha256 it knows for it, and keeps
it, so it plays offline after that. `v2/SHA256SUMS` lists them all.
