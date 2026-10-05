# Fuse 0.2.9 - The Organized Update

## New

- **Films play on the screen you choose.** On a device with two screens, Settings, Jellyfin,
  Films play on: the main screen, or the second screen. The other screen becomes the remote,
  and the menus stay free to browse while it plays. With the menus below (flipped), the second
  screen is the touch screen: the film plays right there, as on a phone, and the screen above
  shows what is playing, large.
- **Swap the picture while it plays.** Play here, on either screen's remote or in the player's
  controls (Y on the remote), moves the picture to the other screen without stopping. B on the
  menus' remote goes back to browsing while the film carries on; the top line brings its
  remote back.
- **Hide the second screen.** Settings, Display, and the quick menu: the second screen goes dark
  and shows nothing until it is shown again (or with two double taps on it).
- **Addons in your order.** Hold a tab in Addons (by touch, the mouse, or A on the controller)
  and move it; the order is kept.
- **Addons in Settings, organised.** Settings, Addons holds Jellyfin, the Store and Cartridge,
  each in a group that opens in place. Cartridge has its own switch there, with picking up new
  downloads and RomM's details.

## Changed

- **Fuseline by Fuse shares one frame among every move.** A screen full of motion (a page of
  tiles rising in, a grid sliding) now costs about a tenth of Compose's time and memory per frame,
  instead of three quarters; page transitions about half.
- **The second screen's remote** fills the screen to every edge, with the status line over it,
  shows the show's poster for an episode, and wraps its buttons on a narrow screen.
- **Home on a small screen that is wider than tall** (a two-screen handheld's lower screen, with
  the menus below) keeps the full widget board, sized so three rows fit.
- The last crash report is at the bottom of About.
- Typed Jellyfin addresses are tested as soon as they are entered, and the exact address that
  answered is kept.

## Fixed

- **Jellyfin at home connects.** Android refused plain http, which every home server uses
  (http://192.168.1.20:8096), so no home address worked however it was typed. Addresses are also
  read more forgivingly now: spaces, a phone's comma for a dot, a half-typed http, the browser's
  /web/index.html tail, and Jellyfin's usual ports when none is given. A server found on the
  network in Docker is reached at the address it answered from, not its container's.
- The menu music no longer plays over a film or a song.
- A film or episode's grid in Jellyfin follows the selection smoothly, instead of needing a second
  push to bring a half-hidden row into view.
- With Remember where you were off, tabs (and Addons' views) open at their start again.
- Flipping the screens keeps the page you were on, instead of starting again at Home.
- The second screen's remote no longer shows a strip of the art behind it at the top.
- The Themes page no longer cuts off the All filter's outline and the left edge of the cards.
