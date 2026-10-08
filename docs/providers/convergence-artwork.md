# No-key artwork sources in Convergence

## GameTDB

Fuse adds GameTDB as an artwork-only fallback for GameCube and Wii disc games with an explicit
six-character disc ID. It requests the specific front cover over HTTPS, as Dolphin does, rather
than scraping game pages or searching titles. No user key, bundled third-party key or account is
used. Metadata, backdrops, logos, videos and unrelated platforms are not queried. Existing local
and accepted artwork and the user's preceding providers retain priority; GameTDB follows the
existing Libretro provider by default. The source's intrinsic image size remains its upper bound;
this fallback does not promise high-resolution artwork where GameTDB only has a smaller scan.

The [official FAQ](https://www.gametdb.com/Main/FAQ), checked on 2026-10-08, expressly permits use of
its database and artwork in software, asks developers to link to GameTDB and asks them to contact
the project. Fuse credits and links [GameTDB](https://www.gametdb.com/) in its provider description,
artwork provenance, Licences and third-party notices. Project notification has **not** been sent
by this implementation; the maintainer should notify GameTDB about Fuse. This software-use
permission is not an open copyright licence for publisher artwork. Scans remain the property of
their original owners. Fuse fetches covers at runtime and does not ship a cover pack.

No numerical request allowance is published in the reviewed guidance. Fuse therefore uses a
conservative application policy of one simultaneous probe and at least 500 ms between probes,
plus the shared provider scheduler's coalescing, cache, negative cache and cooldown handling.
This policy is not represented as a provider quota. A missing 404 is negative; 429 and other
failures remain failures. There is no language or region fan-out to multiply requests.

Evidence for the endpoint and the existing application-use pattern:
[Dolphin GameFile.cpp](https://github.com/dolphin-emu/dolphin/blob/master/Source/Core/UICommon/GameFile.cpp).
US, Japanese and Korean discs request their own region. PAL discs request the user's supported
language or English. A missing regional cover stays missing and another configured source may
fill the gap. Explicit filename disc IDs can supply artwork when the scanner has not extracted a
serial; no provider identity is inferred from a similar game title.

Wii U and Switch are deliberately excluded. Their title IDs are not interchangeable with
GameTDB product IDs. They need a proven ID mapping and a verified endpoint before this provider
can serve them. No arbitrary hex-to-product conversion is attempted.

## Existing Libretro source

The existing implementation remains the broad no-key fallback for named box art and other
supported thumbnail types. Its exact filename/name probing and source provenance remain intact;
GameTDB complements it with direct disc-ID requests rather than duplicating another name search.

## Sources investigated but not added

ScummVM's official icon repository serves game icons rather than useful general box covers and
does not justify a duplicate box-art provider. Steam's cover CDN needs a known Steam app ID and
separate access/licensing validation; no unverified general-purpose cover scraping is added.
