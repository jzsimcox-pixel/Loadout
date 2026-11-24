# Loadout

Loadout sets up an Android gaming handheld: it detects the device, reads the SD
card, and prepares the systems you choose.

This is a work in progress.

## What works today

- Detects the handheld (maker, model, chipset, single or dual screen).
- Scans the SD card read-only, finds an ES-DE style ROMs folder, and counts
  games per system.
- Shows a grid of 28 systems with the ones that already have games selected.
- Installs and updates emulators that have a download source set up
  (Nintendo DS, 3DS and PlayStation 2 so far). Android asks before each install.
- Creates missing system folders on the card after showing the list.

## ES-DE

"Set up ES-DE" reads ES-DE's own list of emulators and sets, per system, the
one you chose in Loadout. It shows every change first, skips anything ES-DE
does not list, and copies each file to `*.loadout-backup` before editing it.

## Emulator settings

"Emulator settings" compares each installed emulator's settings with the
templates in `app/src/main/assets/templates`, shows what would change, and
merges only those keys. Each file is copied to `*.loadout-backup` first.

Android keeps each app's `Android/data` folder closed to other apps, and most
emulators store their settings there. To reach them, Loadout connects to
Android's own wireless debugging from inside the app. A guide walks through
the one-time setup: Developer options, Wireless debugging, and a pairing code
typed into a Loadout notification. Through that connection Loadout only reads
settings files and overwrites ones that already exist. It switches wireless
debugging off again when you leave the screen.

## System logos

Loadout ships no console logos. Tiles show logos that are already on the
device: first any images you place in `Loadout/icons` (named after the system
folder, for example `nes.png` or `ps2.svg`), then the logos inside installed
ES-DE themes. Without either, a plain icon is shown.

## Planned

- Download sources for the remaining emulators.
- BIOS placement, resolution matching, GPU drivers, hotkeys.

## What Loadout never does

It does not download games or BIOS files. It only organises files that are
already on the device.

## Building

Every push builds a debug APK with GitHub Actions and posts it to the Releases
page as "Test build".

To build locally:

    ./gradlew assembleDebug

## Recipes

Systems are defined in `app/src/main/assets/recipes.json`: folder names, file
types, and the recommended emulator for each system.

## Third-party code

- [libadb-android](https://github.com/MuntashirAkon/libadb-android), used under
  its Apache-2.0 option, with its companion sun-security-android library.
- [Conscrypt](https://github.com/google/conscrypt), Apache-2.0.
- [AndroidSVG](https://github.com/BigBadaboom/androidsvg), Apache-2.0.
