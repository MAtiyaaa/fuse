# Fuse 0.3.3 - The Together Update

## New

- **Join without a code.** On a new device, Connect to a Host now offers Ask to Let This Device
  In. A card pops up on the host and on every device already connected, showing who is asking
  and a six-digit number; the new device shows the same number. Anyone there checks the two
  match and chooses Let It In, and the device is in. Typing a code still works.
- **Any connected device can add another.** Add a Device is on every connected device now (in
  the Sync tab and in Settings, Addons, Fuse Sync), not only the host: it shows a code, and that
  device can also say yes when a new one asks.
- **The host has its own profile.** Setting up a host makes Admin, a profile only that computer
  sees and plays as, so nobody needs a profile there. Other devices never see Admin; the first
  person on each device makes their own. A host set up before now gets Admin too, and keeps the
  profile it was using.

## Changed

- **The Systems carousel looks like a console menu.** Each system's card shows its art on the
  right, fading into the system's colour, and its logo at a sensible size at the bottom left
  with how many games it has. Logos no longer grow huge on wide screens, and the card peeking at
  the edge fades its words instead of showing them cut off.
- **Fuse Sync on Android talks to the host with Android's own secure connection**, as Jellyfin
  does, so an https address (a Cloudflare tunnel, a reverse proxy) answers the same way it does
  in a browser.
- An address typed without `http://` or `https://` is reached securely when it is a name on the
  internet (`sync.example.com`) and plainly when it is at home (an IP address, a `.local` name).
- When the home address doesn't answer while connecting, Fuse tries the outside address given
  for away from home before giving up.

## Fixed

- A Fuse Sync host reached through a tunnel on the same computer (cloudflared, a reverse proxy)
  treated those calls as coming from the computer itself, so the Hub page, which names people and
  devices, could be opened from the internet. Calls a tunnel or proxy carries now count as coming
  from outside.
