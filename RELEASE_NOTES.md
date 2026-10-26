# Fuse 0.4.5 - The Evolution Update

Fuse Instant: with ten thousand games, or twenty-five thousand, Home is up in about a fifth of a
second, the library opens again at once, and a search answers in a few hundredths of a second. New
games appear seconds after they're copied in. The Performance Observatory shows what Fuse measures
of itself, Library Health finds and puts right pictures that point nowhere, and How It Runs Here
keeps your own word on how each game runs.

## New

- **Library folders followed as they change.** A game copied into a library folder, renamed in it
  or deleted from it is in the library a few seconds later, without a scan. Each library folder and
  every system folder in it is followed with the system's own change notices, a Steam library in
  its steamapps folder. Saves, save states and downloads still being written don't count as new
  games, and while a game runs Fuse waits until it ends.
- **Performance Observatory.** Settings, Display (and System health) measure Fuse on this device
  now: two seconds of its own frames (the rate, the median, the slowest in twenty and in a hundred,
  missed refreshes), how long Home's counts, reading every game for search and a search take, what
  the picture caches hold, what each background job has cost, Fuse's working memory and what the
  system reports. Measure again, or copy or save it all as a report with no names of folders, games
  or people. Nothing of it runs while it is closed.
- **Library Health.** System health now looks over the library's pictures and files when you open
  it: pictures whose files are gone (a cache cleared, a folder moved) are put right in one press, a
  downloaded one going back to its web address and one without being forgotten so the next fill
  finds another, while pictures you chose stay as they are. It says how many of each before it
  changes anything, and asks first. Pictures kept for games no longer in the library can be tidied
  away, games without a cover are counted with Find Art Now, and the same file found in two places
  is listed, never merged.
- **How It Runs Here.** A game's options say where it stands on this device: Untested, Launches
  here (Fuse started it, nobody said how it went), or what you said: Plays well, Plays with problems
  (graphics, sound, controls, speed, saves, crashes) or Doesn't work. Fuse never calls a game
  playable because it started. A Compatibility Report lists them all to copy or save, and nothing
  of it leaves the device.

## Changed

- **Search, fast at any size.** Every game's name is taken apart once when the library is read, not
  on every key, and typos are weighed in rows the search keeps, with letters a word lacks ruling
  most names out before any comparing. The ranking is exactly as before. The index is built once
  Home has settled and again in the background a moment after the library changes, so the first
  search is as quick as the rest, and a game's stored details are read only when a search asks who
  made it.
- **Home opens in a fifth of a second at 25,000 games.** Reads of the games that are there no
  longer go through an index that can't narrow them: Home's series row read every game once per
  series, and Home's rows sorted the whole library instead of reading the latest played or added in
  order.
- **The library opens again at once.** Lists of games are shared: a screen asking for games
  another is showing gets the list that one keeps current, and the library opened again within half
  a minute of leaving it is there at once. Its pictures are read with only what they need.
- **Background work, coordinated.** Finding series, working out suggestions, reading discs to
  identify games, building the search index and checking the library run at most two at once on
  threads of their own, wait while a game runs or the device sleeps, and keep what they cost for
  the Observatory.

## Fixed

- With a large library, Home could take seconds to show its series and a system's games: SQLite
  read every game once per series. (25,000 games: 2.5 s, now 0.22 s.)
- Pausing a transfer and resuming it straight away could race the download still closing its
  partial file. A pause now waits for the transfer to stop, and keeps count of what arrived.

## Performance

Measured with LibraryScale on one machine (OpenJDK 21, Linux, four cores), every game with a cover,
a logo and a background, against 0.4.4.2 with the same benchmark. Times in milliseconds:

| Games | Home after start | Library opened again (p95) | Search (p95) | Search once settled (p95) |
| ---: | ---: | ---: | ---: | ---: |
| 1,000 | 79 to 65 | 50 to 0.09 | 16.5 to 7.6 | 7.1 |
| 10,000 | 1,012 to 132 | 510 to 0.59 | 72 to 23 | 12.6 |
| 25,000 | 2,540 to 223 | 1,260 to 0.12 | 146 to 83 | 26.3 |

Search is measured from about a second after the start (0.4.4.2's searches began up to 24 s
later); once settled is after Fuse has been left alone for four seconds. The first time the library
opens after a start still reads every game and its art (1.0 s at 25,000 games). In the first
seconds after a start, searches share the one database connection with Fuse's start-up work: the
first waits for the search index (0.6 s at 25,000 games), and another run measured a p95 of 399 ms
at 25,000. Every figure, how it was made and what is not done are in
[the 0.4.5 engineering record](https://github.com/MAtiyaaa/fuse/blob/main/docs/audits/0.4.5-evolution.md).
