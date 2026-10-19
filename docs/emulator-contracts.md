# Emulator contracts

Version 1. Generated from Fuse's code by `EmulatorContractsTest`; do not edit by hand.
"Yes" means Fuse has code for it, checked against the emulator's own source and tested with fixtures.

## Android

| Emulator | Id | Systems | Saves | Settings | Cheats | Firmware |
| --- | --- | --- | --- | --- | --- | --- |
| 2600.emu | `2600-emu` | atari2600 | No: Atari 2600 games have no saves of their own; the emulator's save states stay on this device. | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| Android app | `android-app` | android | No: An Android game keeps its own saves inside its app. | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| aPS3e | `aps3e` | ps3 | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | No: installed inside it, where Fuse can't look |
| ARMSX1 | `armsx1` | psx | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: BIOS folders, by name and checksum |
| ARMSX2 | `armsx2` | ps2 | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: BIOS folders, by name and checksum |
| ARMSX3 | `armsx3` | ps3 | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | No: installed inside it, where Fuse can't look |
| aX360e | `ax360e` | xbox360 | No: This Xbox 360 emulator keeps its saves in its own private Android folder. | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| Azahar | `azahar` | 3ds, new-nintendo-3ds | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| AzaharPlus | `azaharplus` | 3ds, new-nintendo-3ds | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| BachataS4 | `bachatas4` | ps4 | No: This emulator is early, and where it keeps saves still changes between versions. | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| Bannerlator | `bannerlator` | win | No: Windows games keep their own saves inside this app's Windows drive, a different place for every game. | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| C64.emu | `c64-emu` | c64 | No: Games on this computer save to their own disk or tape images, which Fuse doesn't change. Keep those files in step with Syncthing. | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| Cemu | `cemu` | wiiu | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| Citra | `citra` | 3ds, new-nintendo-3ds | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| Citra Canary | `citra-canary` | 3ds, new-nintendo-3ds | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| Citra MMJ | `citra-mmj` | 3ds, new-nintendo-3ds | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| Citron | `citron` | switch | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | No: installed inside it, where Fuse can't look |
| ColEm | `colem` | colecovision | No: This emulator keeps its saves in its own private Android folder, which no other app can open. RetroArch plays the same systems with saves Fuse keeps in step. | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: BIOS folders, by name and checksum |
| Dolphin | `dolphin` | ngc, wii | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| Dolphin MMJR | `dolphin-mmjr` | ngc, wii | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| Dolphin MMJR2 | `dolphin-mmjr2` | ngc, wii | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| DraStic | `drastic` | nds | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: optional; BIOS folders, by name and checksum |
| DuckStation | `duckstation` | psx | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: BIOS folders, by name and checksum |
| Eden | `eden` | switch | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | No: installed inside it, where Fuse can't look |
| Eden Nightly | `eden-nightly` | switch | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | No: installed inside it, where Fuse can't look |
| EmuCoreC | `emucorec` | ps3 | No: This emulator keeps its saves in its own private Android folder, which no other app can open. RetroArch plays the same systems with saves Fuse keeps in step. | No: changed in the emulator on Android | No: changed in the emulator on Android | No: installed inside it, where Fuse can't look |
| EmuCoreV | `emucorev` | psvita | No: This emulator keeps its saves in its own private Android folder, which no other app can open. RetroArch plays the same systems with saves Fuse keeps in step. | No: changed in the emulator on Android | No: changed in the emulator on Android | No: installed inside it, where Fuse can't look |
| EmuCoreX | `emucorex` | ps2 | No: This emulator keeps its saves in its own private Android folder, which no other app can open. RetroArch plays the same systems with saves Fuse keeps in step. | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: BIOS folders, by name and checksum |
| ePSXe | `epsxe` | psx | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: BIOS folders, by name and checksum |
| Flycast | `flycast` | arcade, dc | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: optional; BIOS folders, by name and checksum |
| fMSX | `fmsx` | msx | No: This emulator keeps its saves in its own private Android folder, which no other app can open. RetroArch plays the same systems with saves Fuse keeps in step. | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| FPse | `fpse` | psx | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: BIOS folders, by name and checksum |
| FPseNG | `fpse-ng` | psx | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: BIOS folders, by name and checksum |
| GameHub | `gamehub` | steam, win | No: Windows games keep their own saves inside this app's Windows drive, a different place for every game. | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| GameHub Lite | `gamehub-lite` | steam, win | No: Windows games keep their own saves inside this app's Windows drive, a different place for every game. | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| GameHub Lite (local id) | `gamehub-lite-local` | steam, win | No: Windows games keep their own saves inside this app's Windows drive, a different place for every game. | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| GameNative | `gamenative` | steam, win | No: Windows games keep their own saves inside this app's Windows drive, a different place for every game. | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| GBA.emu | `gba-emu` | gba | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: optional; BIOS folders, by name and checksum |
| GBC.emu | `gbc-emu` | gb, gbc | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| Gopher64 | `gopher64` | n64 | No: This emulator keeps its saves in its own private Android folder, which no other app can open. RetroArch plays the same systems with saves Fuse keeps in step. | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| hakuX | `hakux` | xbox | No: This emulator is early, and where it keeps saves still changes between versions. | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: BIOS folders, by name and checksum |
| iNES | `ines` | famicom, fds, nes | No: This emulator keeps its saves in its own private Android folder, which no other app can open. RetroArch plays the same systems with saves Fuse keeps in step. | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: BIOS folders, by name and checksum |
| IrataJaguar | `iratajaguar` | jaguar | No: This emulator keeps its saves in its own private Android folder, which no other app can open. RetroArch plays the same systems with saves Fuse keeps in step. | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| Kenji-NX | `kenji-nx` | switch | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | No: installed inside it, where Fuse can't look |
| Lemuroid | `lemuroid` | arcade, atari2600, atari7800, gamegear, gb, gba, gbc, genesis, lynx, n64, nds, neo-geo-pocket, neo-geo-pocket-color, nes, psp, psx, segacd, sms, snes, tg16, wonderswan, wonderswan-color | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: BIOS folders, by name and checksum |
| Lime3DS | `lime3ds` | 3ds, new-nintendo-3ds | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| Linkboy | `linkboy` | gb, gba, gbc | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: optional; BIOS folders, by name and checksum |
| Lynx.emu | `lynx-emu` | lynx | No: This EX+Alpha app keeps saves in a form Fuse doesn't read yet; RetroArch plays the same systems with saves Fuse keeps in step. | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: optional; BIOS folders, by name and checksum |
| M64Plus FZ | `m64plus-fz` | n64 | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| MAME4droid | `mame4droid` | arcade, neogeoaes | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: BIOS folders, by name and checksum |
| MAME4droid Current | `mame4droid-current` | arcade, atari7800, intellivision, jaguar, neogeoaes, vectrex | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: BIOS folders, by name and checksum |
| Mandarine | `mandarine` | 3ds, new-nintendo-3ds | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| MasterGear | `mastergear` | gamegear, sg1000, sms | No: This emulator keeps its saves in its own private Android folder, which no other app can open. RetroArch plays the same systems with saves Fuse keeps in step. | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| MD.emu | `md-emu` | genesis, segacd, sms | No: This emulator keeps its saves in its own private Android folder, which no other app can open. RetroArch plays the same systems with saves Fuse keeps in step. | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: BIOS folders, by name and checksum |
| melonDS | `melonds` | nds, nintendo-dsi | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: BIOS folders, by name and checksum |
| melonDS Nightly | `melonds-nightly` | nds, nintendo-dsi | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: BIOS folders, by name and checksum |
| MSX.emu | `msx-emu` | colecovision, msx | No: Games on this computer save to their own disk or tape images, which Fuse doesn't change. Keep those files in step with Syncthing. | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: BIOS folders, by name and checksum |
| Mupen64Plus AE | `mupen64plus-ae` | n64 | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| My Boy! | `my-boy` | gba | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: optional; BIOS folders, by name and checksum |
| My OldBoy! | `my-oldboy` | gb, gbc | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| NEO.emu | `neo-emu` | arcade, neogeoaes | No: This EX+Alpha app keeps saves in a form Fuse doesn't read yet; RetroArch plays the same systems with saves Fuse keeps in step. | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: BIOS folders, by name and checksum |
| NES.emu | `nes-emu` | famicom, fds, nes | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: BIOS folders, by name and checksum |
| Nesoid | `nesoid` | famicom, fds, nes | No: This emulator keeps its saves in its own private Android folder, which no other app can open. RetroArch plays the same systems with saves Fuse keeps in step. | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: BIOS folders, by name and checksum |
| NetherSX2 / AetherSX2 | `nethersx2` | ps2 | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: BIOS folders, by name and checksum |
| NetherSX2-Turnip | `nethersx2-turnip` | ps2 | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: BIOS folders, by name and checksum |
| NetherSX2-Turnip Classic | `nethersx2-turnip-classic` | ps2 | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: BIOS folders, by name and checksum |
| NGP.emu | `ngp-emu` | neo-geo-pocket, neo-geo-pocket-color | No: This EX+Alpha app keeps saves in a form Fuse doesn't read yet; RetroArch plays the same systems with saves Fuse keeps in step. | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| NooDS | `noods` | gba, nds | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: optional; BIOS folders, by name and checksum |
| Panda3DS | `panda3ds` | 3ds, new-nintendo-3ds | No: Panda3DS keeps its saves in a layout Fuse doesn't read yet; Azahar's saves sync. | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| PCE.emu | `pce-emu` | tg16, turbografx-cd | No: This emulator keeps its saves in its own private Android folder, which no other app can open. RetroArch plays the same systems with saves Fuse keeps in step. | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: BIOS folders, by name and checksum |
| Pico8 Android | `pico8-android` | pico8 | No: This emulator keeps its saves in its own private Android folder, which no other app can open. RetroArch plays the same systems with saves Fuse keeps in step. | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| Pizza Boy GBA | `pizza-boy-gba` | gba | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: optional; BIOS folders, by name and checksum |
| Pizza Boy GBC | `pizza-boy-gbc` | gb, gbc | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| Pizza Boy SC | `pizza-boy-sc` | gamegear, genesis, segacd, sms | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: BIOS folders, by name and checksum |
| Play! | `play` | ps2 | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: BIOS folders, by name and checksum |
| PPSSPP | `ppsspp` | psp | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| Real3DOPlayer | `real3doplayer` | 3do | No: This emulator keeps its saves in its own private Android folder, which no other app can open. RetroArch plays the same systems with saves Fuse keeps in step. | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: BIOS folders, by name and checksum |
| Redream | `redream` | dc | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: optional; BIOS folders, by name and checksum |
| RetroArch | `retroarch` | 3do, 3ds, 64dd, amiga, amiga-cd32, amstrad-gx4000, appleii, arcade, arcadia-2001, arduboy, atari-jaguar-cd, atari2600, atari5200, atari7800, atari8bit, bbcmicro, c-plus-4, c64, chailove, colecovision, commodore-cdtv, creativision, dc, doom, dos, fairchild-channel-f, famicom, fds, g-and-w, gamegear, gb, gba, gbc, genesis, handheld-electronic-lcd, intellivision, j2me, jaguar, laserdisc, lowresnx, lutro, lynx, mac, mega-duck-slash-cougar-boy, model2, model3, msx, msx-turbo, msx2plus, multivision, n64, nds, neo-geo-cd, neo-geo-pocket, neo-geo-pocket-color, neogeoaes, nes, new-nintendo-3ds, ngc, nintendo-dsi, odyssey-2, palm-os, pc-8800-series, pc-9800-series, philips-cd-i, pokemon-mini, ps2, psp, psx, quake, rpg-maker, satellaview, saturn, scummvm, sega32, segacd, sfam, sg1000, sharp-x68000, sms, snes, spectravideo, stv, sufami-turbo, supervision, tg16, thomson-mo5, tic-80, turbografx-cd, uzebox, vectrex, vic-20, videopac-g7400, vircon32, virtualboy, wasm-4, wii, wiiu, win3x, win9x, wonderswan, wonderswan-color, x1, z-machine, zx81 | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: BIOS folders, by name and checksum |
| RPCS3 (Android) | `rpcs3-android` | ps3 | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | No: installed inside it, where Fuse can't look |
| RPCSX | `rpcsx` | ps3 | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | No: installed inside it, where Fuse can't look |
| Saturn.emu | `saturn-emu` | saturn | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: optional; BIOS folders, by name and checksum |
| ScummVM | `scummvm` | scummvm | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| SeedlessDS | `seedlessds` | nds, nintendo-dsi | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: BIOS folders, by name and checksum |
| shadPS4 | `shadps4` | ps4 | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| SkyEmu | `skyemu` | gb, gba, gbc, nds | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: optional; BIOS folders, by name and checksum |
| Skyline | `skyline` | switch | No: This emulator keeps its saves where Fuse can't reach them. | No: changed in the emulator on Android | No: changed in the emulator on Android | No: installed inside it, where Fuse can't look |
| Snes9x EX+ | `snes9x-explus` | sfam, snes | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| Strato | `strato` | switch | No: This emulator keeps its saves where Fuse can't reach them. | No: changed in the emulator on Android | No: changed in the emulator on Android | No: installed inside it, where Fuse can't look |
| Sudachi | `sudachi` | switch | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | No: installed inside it, where Fuse can't look |
| Swan.emu | `swan-emu` | wonderswan, wonderswan-color | No: This EX+Alpha app keeps saves in a form Fuse doesn't read yet; RetroArch plays the same systems with saves Fuse keeps in step. | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| Swiff | `swiff` | flash | No: This emulator keeps its saves in its own private Android folder, which no other app can open. RetroArch plays the same systems with saves Fuse keeps in step. | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| Virtual Virtual Boy | `virtual-virtual-boy` | virtualboy | No: This emulator keeps its saves in its own private Android folder, which no other app can open. RetroArch plays the same systems with saves Fuse keeps in step. | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| Vita3K | `vita3k` | psvita | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | No: installed inside it, where Fuse can't look |
| WatermelonDS | `watermelonds` | nds, nintendo-dsi | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: BIOS folders, by name and checksum |
| Winlator | `winlator` | win | No: Windows games keep their own saves inside this app's Windows drive, a different place for every game. | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| Winlator Cmod | `winlator-cmod` | win | No: Windows games keep their own saves inside this app's Windows drive, a different place for every game. | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| Winlator Frost | `winlator-frost` | win | No: Windows games keep their own saves inside this app's Windows drive, a different place for every game. | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| Winlator Cmod Glibc | `winlator-glibc` | win | No: Windows games keep their own saves inside this app's Windows drive, a different place for every game. | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| Winlator Cmod PRoot | `winlator-proot` | win | No: Windows games keep their own saves inside this app's Windows drive, a different place for every game. | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| WinNative | `winnative` | steam, win | No: Windows games keep their own saves inside this app's Windows drive, a different place for every game. | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| X1 BOX | `x1-box` | xbox | No: This emulator is early, and where it keeps saves still changes between versions. | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: BIOS folders, by name and checksum |
| X360 Mobile | `x360-mobile` | xbox360 | No: This Xbox 360 emulator keeps its saves in its own private Android folder. | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| XenDroid | `xendroid` | xbox360 | No: This Xbox 360 emulator keeps its saves in its own private Android folder. | No: changed in the emulator on Android | No: changed in the emulator on Android | Not needed |
| Xenra | `xenra` | xbox, xbox360 | No: This Xbox 360 emulator keeps its saves in its own private Android folder. | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: BIOS folders, by name and checksum |
| Yaba Sanshiro 2 | `yabasanshiro-2` | saturn | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | Yes: optional; BIOS folders, by name and checksum |
| yuzu | `yuzu` | switch | Yes | No: changed in the emulator on Android | No: changed in the emulator on Android | No: installed inside it, where Fuse can't look |

## Linux

| Emulator | Id | Systems | Saves | Settings | Cheats | Firmware |
| --- | --- | --- | --- | --- | --- | --- |
| Azahar | `linux.azahar` | 3ds, new-nintendo-3ds | Yes | No: changed in the emulator | No: changed in the emulator | Not needed |
| AzaharPlus | `linux.azaharplus` | 3ds, new-nintendo-3ds | Yes | No: changed in the emulator | No: changed in the emulator | Not needed |
| Cemu | `linux.cemu` | wiiu | Yes | No: changed in the emulator | No: changed in the emulator | Not needed |
| Citra | `linux.citra` | 3ds, new-nintendo-3ds | Yes | No: changed in the emulator | No: changed in the emulator | Not needed |
| Citron | `linux.citron` | switch | Yes | No: changed in the emulator | No: changed in the emulator | Yes: installed inside it: checked to be there |
| Dolphin | `linux.dolphin` | ngc, wii | Yes | Yes: for every game and for one game | No: changed in the emulator | Not needed |
| DOSBox Staging | `linux.dosbox-staging` | dos | No: DOS games save inside their own folders; keep those folders in step with Syncthing. | No: changed in the emulator | No: changed in the emulator | Not needed |
| DOSBox-X | `linux.dosbox-x` | dos | No: DOS games save inside their own folders; keep those folders in step with Syncthing. | No: changed in the emulator | No: changed in the emulator | Not needed |
| DuckStation | `linux.duckstation` | psx | Yes | Yes: for every game and for one game | Yes | Yes: BIOS folders, by name and checksum |
| Eden | `linux.eden` | switch | Yes | Yes: for every game and for one game | No: changed in the emulator | Yes: installed inside it: checked to be there |
| Flycast | `linux.flycast` | arcade, dc | Yes | No: changed in the emulator | No: changed in the emulator | Yes: optional; BIOS folders, by name and checksum |
| Lime3DS | `linux.lime3ds` | 3ds, new-nintendo-3ds | Yes | No: changed in the emulator | No: changed in the emulator | Not needed |
| MAME | `linux.mame` | arcade, neogeoaes | Yes | No: changed in the emulator | No: changed in the emulator | Yes: BIOS folders, by name and checksum |
| Mandarine | `linux.mandarine` | 3ds, new-nintendo-3ds | Yes | No: changed in the emulator | No: changed in the emulator | Not needed |
| Mednafen | `linux.mednafen` | famicom, fds, gamegear, gb, gba, gbc, genesis, lynx, neo-geo-pocket, neo-geo-pocket-color, nes, psx, saturn, sfam, sms, snes, tg16, turbografx-cd, virtualboy, wonderswan, wonderswan-color | Yes | No: changed in the emulator | No: changed in the emulator | Yes: BIOS folders, by name and checksum |
| melonDS | `linux.melonds` | nds, nintendo-dsi | Yes | No: changed in the emulator | No: changed in the emulator | Yes: BIOS folders, by name and checksum |
| mGBA | `linux.mgba` | gb, gba, gbc | Yes | No: changed in the emulator | No: changed in the emulator | Yes: optional; BIOS folders, by name and checksum |
| Mupen64Plus (m64p) | `linux.mupen64plus` | n64 | Yes | No: changed in the emulator | No: changed in the emulator | Not needed |
| Panda3DS | `linux.panda3ds` | 3ds, new-nintendo-3ds | No: Panda3DS keeps its saves in a layout Fuse doesn't read yet; Azahar's saves sync. | No: changed in the emulator | No: changed in the emulator | Not needed |
| PCSX2 | `linux.pcsx2` | ps2 | Yes | Yes: for every game and for one game | Yes | Yes: BIOS folders, by name and checksum |
| PICO-8 | `linux.pico8` | pico8 | No: PICO-8 keeps cartridge data in its own folder by cartridge; Fuse doesn't keep it in step yet. | No: changed in the emulator | No: changed in the emulator | Not needed |
| Play! | `linux.play` | ps2 | Yes | No: changed in the emulator | No: changed in the emulator | Yes: BIOS folders, by name and checksum |
| Fuse Ports | `linux.port` | ports | Yes | No: changed in the emulator | No: changed in the emulator | Not needed |
| PPSSPP | `linux.ppsspp` | psp | Yes | Yes: for every game and for one game | Yes | Not needed |
| Redream | `linux.redream` | dc | Yes | No: changed in the emulator | No: changed in the emulator | Yes: optional; BIOS folders, by name and checksum |
| RetroArch | `linux.retroarch` | 3do, 3ds, 64dd, amiga, amiga-cd32, amstrad-gx4000, appleii, arcade, arcadia-2001, arduboy, atari-jaguar-cd, atari2600, atari5200, atari7800, atari8bit, bbcmicro, c-plus-4, c64, chailove, colecovision, commodore-cdtv, creativision, dc, doom, dos, fairchild-channel-f, famicom, fds, g-and-w, gamegear, gb, gba, gbc, genesis, handheld-electronic-lcd, intellivision, j2me, jaguar, laserdisc, lowresnx, lutro, lynx, mac, mega-duck-slash-cougar-boy, model2, model3, msx, msx-turbo, msx2plus, multivision, n64, nds, neo-geo-cd, neo-geo-pocket, neo-geo-pocket-color, neogeoaes, nes, new-nintendo-3ds, ngc, nintendo-dsi, odyssey-2, palm-os, pc-8800-series, pc-9800-series, philips-cd-i, pico8, pokemon-mini, ps2, psp, psx, quake, rpg-maker, satellaview, saturn, scummvm, sega32, segacd, sfam, sg1000, sharp-x68000, sms, snes, spectravideo, stv, sufami-turbo, supervision, tg16, thomson-mo5, tic-80, turbografx-cd, uzebox, vectrex, vic-20, videopac-g7400, vircon32, virtualboy, wasm-4, wii, wiiu, win3x, win9x, wonderswan, wonderswan-color, x1, z-machine, zx81 | Yes | Yes: for every game and for one game | Yes | Yes: BIOS folders, by name and checksum |
| RetroArch (Steam) | `linux.retroarch-steam` | 3do, 3ds, 64dd, amiga, amiga-cd32, amstrad-gx4000, appleii, arcade, arcadia-2001, arduboy, atari-jaguar-cd, atari2600, atari5200, atari7800, atari8bit, bbcmicro, c-plus-4, c64, chailove, colecovision, commodore-cdtv, creativision, dc, doom, dos, fairchild-channel-f, famicom, fds, g-and-w, gamegear, gb, gba, gbc, genesis, handheld-electronic-lcd, intellivision, j2me, jaguar, laserdisc, lowresnx, lutro, lynx, mac, mega-duck-slash-cougar-boy, model2, model3, msx, msx-turbo, msx2plus, multivision, n64, nds, neo-geo-cd, neo-geo-pocket, neo-geo-pocket-color, neogeoaes, nes, new-nintendo-3ds, ngc, nintendo-dsi, odyssey-2, palm-os, pc-8800-series, pc-9800-series, philips-cd-i, pico8, pokemon-mini, ps2, psp, psx, quake, rpg-maker, satellaview, saturn, scummvm, sega32, segacd, sfam, sg1000, sharp-x68000, sms, snes, spectravideo, stv, sufami-turbo, supervision, tg16, thomson-mo5, tic-80, turbografx-cd, uzebox, vectrex, vic-20, videopac-g7400, vircon32, virtualboy, wasm-4, wii, wiiu, win3x, win9x, wonderswan, wonderswan-color, x1, z-machine, zx81 | Yes | Yes: for every game and for one game | Yes | Yes: BIOS folders, by name and checksum |
| RPCS3 | `linux.rpcs3` | ps3 | Yes | Yes: for every game and for one game | No: changed in the emulator | Yes: installed inside it: checked to be there |
| Ruffle | `linux.ruffle` | flash | No: Flash games keep their saves in Ruffle's own storage by site; Fuse doesn't keep it in step yet. | No: changed in the emulator | No: changed in the emulator | Not needed |
| Ryujinx | `linux.ryujinx` | switch | Yes | No: changed in the emulator | No: changed in the emulator | Yes: installed inside it: checked to be there |
| ScummVM | `linux.scummvm` | scummvm | Yes | No: changed in the emulator | No: changed in the emulator | Not needed |
| shadPS4 | `linux.shadps4` | ps4 | Yes | No: changed in the emulator | No: changed in the emulator | Not needed |
| SharpEmu | `linux.sharpemu` | ps5 | No: This emulator is early, and where it keeps saves still changes between versions. | No: changed in the emulator | No: changed in the emulator | Not needed |
| Vita3K | `linux.vita3k` | psvita | Yes | No: changed in the emulator | No: changed in the emulator | Yes: installed inside it: checked to be there |
| xemu | `linux.xemu` | xbox | No: xemu keeps saves inside its hard disk image, which Fuse can't open safely. | No: changed in the emulator | No: changed in the emulator | Yes: BIOS folders, by name and checksum |
| Xenia | `linux.xenia` | xbox360 | Yes | No: changed in the emulator | No: changed in the emulator | Not needed |

## Windows

| Emulator | Id | Systems | Saves | Settings | Cheats | Firmware |
| --- | --- | --- | --- | --- | --- | --- |
| Azahar | `windows.azahar` | 3ds, new-nintendo-3ds | Yes | No: changed in the emulator | No: changed in the emulator | Not needed |
| AzaharPlus | `windows.azaharplus` | 3ds, new-nintendo-3ds | Yes | No: changed in the emulator | No: changed in the emulator | Not needed |
| Cemu | `windows.cemu` | wiiu | Yes | No: changed in the emulator | No: changed in the emulator | Not needed |
| Citra | `windows.citra` | 3ds, new-nintendo-3ds | Yes | No: changed in the emulator | No: changed in the emulator | Not needed |
| Citron | `windows.citron` | switch | Yes | No: changed in the emulator | No: changed in the emulator | Yes: installed inside it: checked to be there |
| Dolphin | `windows.dolphin` | ngc, wii | Yes | Yes: for every game and for one game | No: changed in the emulator | Not needed |
| DOSBox Staging | `windows.dosbox-staging` | dos | No: DOS games save inside their own folders; keep those folders in step with Syncthing. | No: changed in the emulator | No: changed in the emulator | Not needed |
| DOSBox-X | `windows.dosbox-x` | dos | No: DOS games save inside their own folders; keep those folders in step with Syncthing. | No: changed in the emulator | No: changed in the emulator | Not needed |
| DuckStation | `windows.duckstation` | psx | Yes | Yes: for every game and for one game | Yes | Yes: BIOS folders, by name and checksum |
| Eden | `windows.eden` | switch | Yes | Yes: for every game and for one game | No: changed in the emulator | Yes: installed inside it: checked to be there |
| Flycast | `windows.flycast` | arcade, dc | Yes | No: changed in the emulator | No: changed in the emulator | Yes: optional; BIOS folders, by name and checksum |
| KytyPS5 | `windows.kytyps5` | ps5 | No: This emulator is early, and where it keeps saves still changes between versions. | No: changed in the emulator | No: changed in the emulator | Not needed |
| Lime3DS | `windows.lime3ds` | 3ds, new-nintendo-3ds | Yes | No: changed in the emulator | No: changed in the emulator | Not needed |
| MAME | `windows.mame` | arcade, neogeoaes | Yes | No: changed in the emulator | No: changed in the emulator | Yes: BIOS folders, by name and checksum |
| Mandarine | `windows.mandarine` | 3ds, new-nintendo-3ds | Yes | No: changed in the emulator | No: changed in the emulator | Not needed |
| Mednafen | `windows.mednafen` | famicom, fds, gamegear, gb, gba, gbc, genesis, lynx, neo-geo-pocket, neo-geo-pocket-color, nes, psx, saturn, sfam, sms, snes, tg16, turbografx-cd, virtualboy, wonderswan, wonderswan-color | Yes | No: changed in the emulator | No: changed in the emulator | Yes: BIOS folders, by name and checksum |
| melonDS | `windows.melonds` | nds, nintendo-dsi | Yes | No: changed in the emulator | No: changed in the emulator | Yes: BIOS folders, by name and checksum |
| mGBA | `windows.mgba` | gb, gba, gbc | Yes | No: changed in the emulator | No: changed in the emulator | Yes: optional; BIOS folders, by name and checksum |
| Mupen64Plus | `windows.mupen64plus` | n64 | Yes | No: changed in the emulator | No: changed in the emulator | Not needed |
| Panda3DS | `windows.panda3ds` | 3ds, new-nintendo-3ds | No: Panda3DS keeps its saves in a layout Fuse doesn't read yet; Azahar's saves sync. | No: changed in the emulator | No: changed in the emulator | Not needed |
| PCSX2 | `windows.pcsx2` | ps2 | Yes | Yes: for every game and for one game | Yes | Yes: BIOS folders, by name and checksum |
| PICO-8 | `windows.pico8` | pico8 | No: PICO-8 keeps cartridge data in its own folder by cartridge; Fuse doesn't keep it in step yet. | No: changed in the emulator | No: changed in the emulator | Not needed |
| Play! | `windows.play` | ps2 | Yes | No: changed in the emulator | No: changed in the emulator | Yes: BIOS folders, by name and checksum |
| Fuse Ports | `windows.port` | ports | Yes | No: changed in the emulator | No: changed in the emulator | Not needed |
| PPSSPP | `windows.ppsspp` | psp | Yes | Yes: for every game and for one game | Yes | Not needed |
| Redream | `windows.redream` | dc | Yes | No: changed in the emulator | No: changed in the emulator | Yes: optional; BIOS folders, by name and checksum |
| RetroArch | `windows.retroarch` | 3do, 3ds, 64dd, amiga, amiga-cd32, amstrad-gx4000, appleii, arcade, arcadia-2001, arduboy, atari-jaguar-cd, atari2600, atari5200, atari7800, atari8bit, bbcmicro, c-plus-4, c64, chailove, colecovision, commodore-cdtv, creativision, dc, doom, dos, fairchild-channel-f, famicom, fds, g-and-w, gamegear, gb, gba, gbc, genesis, handheld-electronic-lcd, intellivision, j2me, jaguar, laserdisc, lowresnx, lutro, lynx, mac, mega-duck-slash-cougar-boy, model2, model3, msx, msx-turbo, msx2plus, multivision, n64, nds, neo-geo-cd, neo-geo-pocket, neo-geo-pocket-color, neogeoaes, nes, new-nintendo-3ds, ngc, nintendo-dsi, odyssey-2, palm-os, pc-8800-series, pc-9800-series, philips-cd-i, pico8, pokemon-mini, ps2, psp, psx, quake, rpg-maker, satellaview, saturn, scummvm, sega32, segacd, sfam, sg1000, sharp-x68000, sms, snes, spectravideo, stv, sufami-turbo, supervision, tg16, thomson-mo5, tic-80, turbografx-cd, uzebox, vectrex, vic-20, videopac-g7400, vircon32, virtualboy, wasm-4, wii, wiiu, win3x, win9x, wonderswan, wonderswan-color, x1, z-machine, zx81 | Yes | Yes: for every game and for one game | Yes | Yes: BIOS folders, by name and checksum |
| RPCS3 | `windows.rpcs3` | ps3 | Yes | Yes: for every game and for one game | No: changed in the emulator | Yes: installed inside it: checked to be there |
| Ruffle | `windows.ruffle` | flash | No: Flash games keep their saves in Ruffle's own storage by site; Fuse doesn't keep it in step yet. | No: changed in the emulator | No: changed in the emulator | Not needed |
| Ryujinx | `windows.ryujinx` | switch | Yes | No: changed in the emulator | No: changed in the emulator | Yes: installed inside it: checked to be there |
| ScummVM | `windows.scummvm` | scummvm | Yes | No: changed in the emulator | No: changed in the emulator | Not needed |
| shadPS4 | `windows.shadps4` | ps4 | Yes | No: changed in the emulator | No: changed in the emulator | Not needed |
| SharpEmu | `windows.sharpemu` | ps5 | No: This emulator is early, and where it keeps saves still changes between versions. | No: changed in the emulator | No: changed in the emulator | Not needed |
| Vita3K | `windows.vita3k` | psvita | Yes | No: changed in the emulator | No: changed in the emulator | Yes: installed inside it: checked to be there |
| xemu | `windows.xemu` | xbox | No: xemu keeps saves inside its hard disk image, which Fuse can't open safely. | No: changed in the emulator | No: changed in the emulator | Yes: BIOS folders, by name and checksum |
| Xenia | `windows.xenia` | xbox360 | Yes | No: changed in the emulator | No: changed in the emulator | Not needed |

## Macos

| Emulator | Id | Systems | Saves | Settings | Cheats | Firmware |
| --- | --- | --- | --- | --- | --- | --- |
| Azahar | `macos.azahar` | 3ds, new-nintendo-3ds | Yes | No: changed in the emulator | No: changed in the emulator | Not needed |
| Cemu | `macos.cemu` | wiiu | Yes | No: changed in the emulator | No: changed in the emulator | Not needed |
| Citra | `macos.citra` | 3ds, new-nintendo-3ds | Yes | No: changed in the emulator | No: changed in the emulator | Not needed |
| Dolphin | `macos.dolphin` | ngc, wii | Yes | Yes: for every game and for one game | No: changed in the emulator | Not needed |
| DOSBox Staging | `macos.dosbox-staging` | dos | No: DOS games save inside their own folders; keep those folders in step with Syncthing. | No: changed in the emulator | No: changed in the emulator | Not needed |
| DOSBox-X | `macos.dosbox-x` | dos | No: DOS games save inside their own folders; keep those folders in step with Syncthing. | No: changed in the emulator | No: changed in the emulator | Not needed |
| DuckStation | `macos.duckstation` | psx | Yes | Yes: for every game and for one game | Yes | Yes: BIOS folders, by name and checksum |
| Eden | `macos.eden` | switch | Yes | Yes: for every game and for one game | No: changed in the emulator | Yes: installed inside it: checked to be there |
| Flycast | `macos.flycast` | arcade, dc | Yes | No: changed in the emulator | No: changed in the emulator | Yes: optional; BIOS folders, by name and checksum |
| Lime3DS | `macos.lime3ds` | 3ds, new-nintendo-3ds | Yes | No: changed in the emulator | No: changed in the emulator | Not needed |
| MAME | `macos.mame` | arcade, neogeoaes | Yes | No: changed in the emulator | No: changed in the emulator | Yes: BIOS folders, by name and checksum |
| Mandarine | `macos.mandarine` | 3ds, new-nintendo-3ds | Yes | No: changed in the emulator | No: changed in the emulator | Not needed |
| Mednafen | `macos.mednafen` | famicom, fds, gamegear, gb, gba, gbc, genesis, lynx, neo-geo-pocket, neo-geo-pocket-color, nes, psx, saturn, sfam, sms, snes, tg16, turbografx-cd, virtualboy, wonderswan, wonderswan-color | Yes | No: changed in the emulator | No: changed in the emulator | Yes: BIOS folders, by name and checksum |
| melonDS | `macos.melonds` | nds, nintendo-dsi | Yes | No: changed in the emulator | No: changed in the emulator | Yes: BIOS folders, by name and checksum |
| mGBA | `macos.mgba` | gb, gba, gbc | Yes | No: changed in the emulator | No: changed in the emulator | Yes: optional; BIOS folders, by name and checksum |
| Mupen64Plus | `macos.mupen64plus` | n64 | Yes | No: changed in the emulator | No: changed in the emulator | Not needed |
| Panda3DS | `macos.panda3ds` | 3ds, new-nintendo-3ds | No: Panda3DS keeps its saves in a layout Fuse doesn't read yet; Azahar's saves sync. | No: changed in the emulator | No: changed in the emulator | Not needed |
| PCSX2 | `macos.pcsx2` | ps2 | Yes | Yes: for every game and for one game | Yes | Yes: BIOS folders, by name and checksum |
| PICO-8 | `macos.pico8` | pico8 | No: PICO-8 keeps cartridge data in its own folder by cartridge; Fuse doesn't keep it in step yet. | No: changed in the emulator | No: changed in the emulator | Not needed |
| Play! | `macos.play` | ps2 | Yes | No: changed in the emulator | No: changed in the emulator | Yes: BIOS folders, by name and checksum |
| Fuse Ports | `macos.port` | ports | Yes | No: changed in the emulator | No: changed in the emulator | Not needed |
| PPSSPP | `macos.ppsspp` | psp | Yes | Yes: for every game and for one game | Yes | Not needed |
| Redream | `macos.redream` | dc | Yes | No: changed in the emulator | No: changed in the emulator | Yes: optional; BIOS folders, by name and checksum |
| RetroArch | `macos.retroarch` | 3do, 3ds, 64dd, amiga, amiga-cd32, amstrad-gx4000, appleii, arcade, arcadia-2001, arduboy, atari-jaguar-cd, atari2600, atari5200, atari7800, atari8bit, bbcmicro, c-plus-4, c64, chailove, colecovision, commodore-cdtv, creativision, dc, doom, dos, fairchild-channel-f, famicom, fds, g-and-w, gamegear, gb, gba, gbc, genesis, handheld-electronic-lcd, intellivision, j2me, jaguar, laserdisc, lowresnx, lutro, lynx, mac, mega-duck-slash-cougar-boy, model2, model3, msx, msx-turbo, msx2plus, multivision, n64, nds, neo-geo-cd, neo-geo-pocket, neo-geo-pocket-color, neogeoaes, nes, new-nintendo-3ds, ngc, nintendo-dsi, odyssey-2, palm-os, pc-8800-series, pc-9800-series, philips-cd-i, pico8, pokemon-mini, ps2, psp, psx, quake, rpg-maker, satellaview, saturn, scummvm, sega32, segacd, sfam, sg1000, sharp-x68000, sms, snes, spectravideo, stv, sufami-turbo, supervision, tg16, thomson-mo5, tic-80, turbografx-cd, uzebox, vectrex, vic-20, videopac-g7400, vircon32, virtualboy, wasm-4, wii, wiiu, win3x, win9x, wonderswan, wonderswan-color, x1, z-machine, zx81 | Yes | Yes: for every game and for one game | Yes | Yes: BIOS folders, by name and checksum |
| RPCS3 | `macos.rpcs3` | ps3 | Yes | Yes: for every game and for one game | No: changed in the emulator | Yes: installed inside it: checked to be there |
| Ruffle | `macos.ruffle` | flash | No: Flash games keep their saves in Ruffle's own storage by site; Fuse doesn't keep it in step yet. | No: changed in the emulator | No: changed in the emulator | Not needed |
| Ryujinx | `macos.ryujinx` | switch | Yes | No: changed in the emulator | No: changed in the emulator | Yes: installed inside it: checked to be there |
| ScummVM | `macos.scummvm` | scummvm | Yes | No: changed in the emulator | No: changed in the emulator | Not needed |
| shadPS4 | `macos.shadps4` | ps4 | Yes | No: changed in the emulator | No: changed in the emulator | Not needed |
| SharpEmu | `macos.sharpemu` | ps5 | No: This emulator is early, and where it keeps saves still changes between versions. | No: changed in the emulator | No: changed in the emulator | Not needed |
| Vita3K | `macos.vita3k` | psvita | Yes | No: changed in the emulator | No: changed in the emulator | Yes: installed inside it: checked to be there |
| xemu | `macos.xemu` | xbox | No: xemu keeps saves inside its hard disk image, which Fuse can't open safely. | No: changed in the emulator | No: changed in the emulator | Yes: BIOS folders, by name and checksum |

## RetroArch cores

| Core | Systems | Saves |
| --- | --- | --- |
| `81` | zx81 | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `a5200` | atari5200 | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `amiarcadia` | arcadia-2001 | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `amiberry` | amiga, amiga-cd32, commodore-cdtv | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `applewin` | appleii | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `ardens` | arduboy | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `arduous` | arduboy | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `atari800` | atari5200, atari8bit | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `azahar` | 3ds, new-nintendo-3ds | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `b2` | bbcmicro | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `blastem` | colecovision, gamegear, genesis, sega32, segacd, sg1000, sms | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `bluemsx` | colecovision, msx, msx-turbo, msx2plus, sg1000, spectravideo | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `boom3` | doom | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `boom3_xp` | doom | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `bsnes` | gb, gbc, satellaview, sfam, snes, sufami-turbo | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `bsnes-jg` | satellaview, sufami-turbo | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `bsnes_hd_beta` | satellaview, sfam, snes, sufami-turbo | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `bsnes_mercury_accuracy` | satellaview, sfam, snes, sufami-turbo | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `cap32` | amstrad-gx4000 | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `cdi2015` | philips-cd-i | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `cemu` | wiiu | No: Cemu's core keeps Wii U saves in its own storage. Cemu itself syncs: play these with it. |
| `chailove` | chailove | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `citra` | 3ds, new-nintendo-3ds | No: Citra's core keeps 3DS saves on its own emulated SD card. Azahar syncs 3DS saves: play these with it. |
| `citra2018` | 3ds, new-nintendo-3ds | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `clownmdemu` | genesis, segacd | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `crocods` | amstrad-gx4000 | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `desmume` | nds | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `desmume2015` | nds | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `dice` | arcade | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `dirksimple` | laserdisc | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `dolphin` | ngc, wii | No: Dolphin's core keeps GameCube and Wii saves inside its own user folder. Dolphin itself syncs: play these with it. |
| `dosbox_core` | dos | No: This DOSBox core saves inside the game's own folder. DOSBox Pure keeps saves Fuse syncs. |
| `dosbox_pure` | dos, win3x, win9x | Yes: its own save archives |
| `dosbox_svn` | dos | No: This DOSBox core saves inside the game's own folder. DOSBox Pure keeps saves Fuse syncs. |
| `easyrpg` | rpg-maker | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `fbalpha2012` | arcade | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `fbneo` | arcade, neo-geo-cd, neogeoaes | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `fceumm` | famicom, fds, nes | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `flycast` | arcade, dc | Yes: its own VMU files |
| `fmsx` | msx, msx2plus | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `freechaf` | fairchild-channel-f | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `freeintv` | intellivision | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `frodo` | c64 | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `fsuae` | amiga | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `gambatte` | gb, gbc | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `gearboy` | gb, gbc | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `gearcoleco` | colecovision | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `geargrafx` | tg16, turbografx-cd | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `gearlynx` | lynx | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `gearsystem` | gamegear, multivision, sg1000, sms | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `genesis_plus_gx` | gamegear, genesis, segacd, sg1000, sms | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `genesis_plus_gx_wide` | gamegear, genesis, segacd, sg1000, sms | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `geolith` | arcade, neo-geo-cd, neogeoaes | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `gpsp` | gba | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `gw` | g-and-w, handheld-electronic-lcd | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `handy` | lynx | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `hbmame` | arcade | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `holani` | lynx | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `jollycv` | colecovision, creativision | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `kronos` | arcade, saturn, stv | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `lowresnx` | lowresnx | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `lutro` | lutro | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `mame` | appleii, arcade, arcadia-2001, atari7800, creativision, fairchild-channel-f, g-and-w, handheld-electronic-lcd, intellivision, laserdisc, mega-duck-slash-cougar-boy, model2, model3, neogeoaes, odyssey-2, stv, supervision, vectrex, videopac-g7400 | No: MAME keeps each game's NVRAM and high scores in its own folders, not as a save RetroArch writes. |
| `mame2000` | arcade | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `mame2003` | arcade | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `mame2003_plus` | arcade | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `mame2010` | arcade | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `mamearcade` | arcade, model2, model3, neogeoaes, stv | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `mamemess` | g-and-w, handheld-electronic-lcd | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `mednafen_lynx` | lynx | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `mednafen_ngp` | neo-geo-pocket, neo-geo-pocket-color | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `mednafen_pce` | tg16, turbografx-cd | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `mednafen_pce_fast` | tg16, turbografx-cd | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `mednafen_psx` | psx | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `mednafen_psx_hw` | psx | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `mednafen_saturn` | saturn | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `mednafen_supafaust` | sfam, snes | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `mednafen_supergrafx` | tg16, turbografx-cd | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `mednafen_vb` | virtualboy | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `mednafen_wswan` | wonderswan, wonderswan-color | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `melonds` | nds, nintendo-dsi | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `melondsds` | nds, nintendo-dsi | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `mesen` | famicom, fds, nes | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `mesen-s` | satellaview | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `mesen2` | famicom, fds, gamegear, gb, gba, gbc, nes, satellaview, sfam, sms, snes, tg16, turbografx-cd, wonderswan, wonderswan-color | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `mgba` | gb, gba, gbc | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `minivmac` | mac | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `mojozork` | z-machine | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `mu` | palm-os | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `mupen64plus_next` | 64dd, n64 | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `mupen64plus_next_gles3` | 64dd, n64 | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `nekop2` | pc-9800-series | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `neocd` | neo-geo-cd | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `nestopia` | famicom, fds, nes | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `noods` | gba, nds | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `np2kai` | pc-9800-series | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `o2em` | odyssey-2, videopac-g7400 | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `opera` | 3do | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `parallel_n64` | 64dd, n64 | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `pcee2` | ps2 | No: This PlayStation 2 core keeps memory cards in its own folder. PCSX2 itself syncs its cards. |
| `pcsx2` | ps2 | No: LRPS2 keeps PlayStation 2 memory cards in its own folder. PCSX2 itself syncs its cards: play these with it. |
| `pcsx_rearmed` | psx | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `picodrive` | gamegear, genesis, sega32, segacd, sms | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `pokemini` | pokemon-mini | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `potator` | supervision | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `ppsspp` | psp | Yes: its own memory stick |
| `prboom` | doom | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `prosystem` | atari7800 | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `puae` | amiga, amiga-cd32, commodore-cdtv | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `puae2021` | amiga, amiga-cd32, commodore-cdtv | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `px68k` | sharp-x68000 | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `quasi88` | pc-8800-series | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `quicknes` | famicom, nes | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `race` | neo-geo-pocket, neo-geo-pocket-color | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `retro8` | pico8 | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `rustynes` | famicom, fds, nes | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `same_cdi` | philips-cd-i | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `sameboy` | gb, gbc | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `sameduck` | mega-duck-slash-cougar-boy | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `scummvm` | scummvm | No: ScummVM's core keeps each game's saves in its own saves folder, by engine. Fuse doesn't keep those in step yet. |
| `skyemu` | gb, gba, gbc, nds | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `smsplus` | gamegear, sms | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `snes9x` | satellaview, sfam, snes, sufami-turbo | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `snes9x2005_plus` | satellaview, sfam, snes, sufami-turbo | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `snes9x2010` | satellaview, sfam, snes, sufami-turbo | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `squirreljme` | j2me | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `stella` | atari2600 | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `stella2014` | atari2600 | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `stella2023` | atari2600 | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `supermodel` | arcade, model3 | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `swanstation` | psx | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `tgbdual` | gb, gbc | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `theodore` | thomson-mo5 | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `tia` | atari2600 | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `tic80` | tic-80 | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `tyrquake` | quake | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `uzem` | uzebox | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `vba_next` | gba | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `vbam` | gb, gba, gbc | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `vecx` | vectrex | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `vice_x128` | c64 | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `vice_x64` | c64 | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `vice_x64sc` | c64 | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `vice_xplus4` | c-plus-4 | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `vice_xscpu64` | c64 | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `vice_xvic` | vic-20 | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `vircon32` | vircon32 | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `virtualjaguar` | atari-jaguar-cd, jaguar | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `virtualxt` | dos | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `vitaquake2` | quake | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `vitaquake2-rogue` | quake | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `vitaquake2-xatrix` | quake | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `vitaquake2-zaero` | quake | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `vitaquake3` | quake | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `wasm4` | wasm-4 | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `x1` | x1 | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `yabasanshiro` | saturn | Yes: save RAM RetroArch writes (.srm, .rtc) |
| `yabause` | saturn | Yes: save RAM RetroArch writes (.srm, .rtc) |
