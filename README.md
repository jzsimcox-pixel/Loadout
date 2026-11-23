# Loadout

Loadout sets up an Android gaming handheld: it detects the device, reads the SD
card, and prepares the systems you choose.

This is a work in progress.

## What works today

- Detects the handheld (maker, model, chipset, single or dual screen).
- Scans the SD card read-only, finds an ES-DE style ROMs folder, and counts
  games per system.
- Shows a grid of 28 systems with the ones that already have games selected.

## Planned

- Install and update emulators.
- Create missing system folders on the card.
- Point ES-DE at the installed emulators.
- Tuned settings, BIOS placement, GPU drivers, hotkeys.

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
