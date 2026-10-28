# Momir Android

Android app for the Momir printer flow: roll a random creature by mana value, preview the card, and print it on a Bluetooth LE thermal printer.

## Origin

This project is an Android-native adaptation of the original Momir project:
https://github.com/Devin-Cooper/momir-printer

This repository is a fork of [acocalypso/Momir-Android](https://github.com/acocalypso/Momir-Android).
The primary focus of this fork is the **Tiny Print X6H** thermal printer. The Phomemo printers
from the original project are still in the code but are **untested** here (see Printer Support).

## Features

- First launch bootstrap:
  - Downloads AtomicCards from https://mtgjson.com/api/v5/AtomicCards.json
  - Processes it into a compact local creature index
- UI flow:
  - Pick mana value (0-16)
  - Roll a random creature
  - Show card image from Scryfall
- Card rendering:
  - Long card names and creature types wrap onto extra lines instead of being cut off
- BLE printing:
  - Scan/connect to supported printers by Bluetooth name
  - One driver per printer family builds the byte stream and decides how data is paced
  - Print darkness setting (1-5) in the options menu (X6H only)
  - X6H: the printer's status reply is checked after each print, so a problem such as an open cover is reported instead of "Printed"

## Requirements

- Android Studio (latest stable)
- Android SDK 37
- JDK 21
- Android device with BLE support (recommended for printer testing)

## Getting Started

1. Clone the repository.
2. Open this folder in Android Studio.
3. Let Gradle sync complete.
4. Run the app on a device or emulator.

## Build From Command Line

Use Windows PowerShell from the project root:

```powershell
.\gradlew.bat :app:assembleDebug
```

Release build:

```powershell
.\gradlew.bat :app:assembleRelease
```

## Signed Release With GitHub Actions

The `Android Build and Release` workflow can produce a signed `app-release.apk` and attach it to the GitHub Release.

Add these repository secrets in GitHub:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

PowerShell helper to generate `ANDROID_KEYSTORE_BASE64` from your keystore file:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\your\release.keystore"))
```

Then trigger the workflow:

1. Push a tag like `v0.3.1`, or
2. Run `Android Build and Release` manually from the Actions tab and provide `tag_name`.

## Permissions

- Android 12+: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`
- Android 11 and below: `ACCESS_FINE_LOCATION`

## Printer Support

Printers are recognised by the name they advertise over Bluetooth. Name matching lives in the
drivers, and `PrinterDrivers` tries them from the most specific to the least specific.

| Printer | Name pattern | Status | Notes |
| --- | --- | --- | --- |
| Tiny Print X6H | starts with `X6H` (advertises e.g. `X6h-E16D`) | **Supported, tested** | 384 px wide, TiMini "tiny" protocol, darkness setting 1-5, completion and fault detection. |
| Phomemo M02S | contains `M02S`, or `Mr.in_` | Untested | 576 px. `Mr.in_` names also use this driver. |
| Phomemo M02 PRO | contains `M02 PRO` | Untested | 624 px, optional flow control on FF03. |
| Phomemo M02 | contains `M02` or `T02` | Untested | 384 px, conservative transfer mode. |
| Phomemo M04 / M04S / M04AS | starts with `M04`, or contains `M04S` / `M04AS` | Untested | Rotated 1232 px output. |

**About "untested":** the Phomemo drivers come from the original project and were refactored into
the driver structure used here. They have not been tested on real hardware since then. They may
work, but treat them as unverified. Reports and fixes are welcome.

If your printer advertises a different Bluetooth name, it will not be auto-detected until a driver
matches it. See `PRINTER_SUPPORT.md` for the information needed to add support for a new printer.

## Adding a Printer

1. Add a driver in `app/src/main/java/com/momir/android/printer/` (implement `PrinterDriver`: GATT
   UUIDs, name matching, image preparation, encoding, flow control).
2. Register it in `PrinterDrivers`. Put it before any driver whose name pattern overlaps.

## Project Structure

- `app/`: Android app module
  - `ble/`: protocol-free BLE transport (`BleTransport`), `GattSpec`, and the `FlowControl` contract
  - `printer/`: `PrinterDriver`, the `PrinterDrivers` registry, and one package per family (`phomemo/`, `tiny/`)
  - `render/`: card drawing (`CardRenderer`), dithering (`Monochrome`), image helpers (`ImageOps`)
  - `ui/`: `MainViewModel`; `data/` and `model/`: card database and types
- `gradle/`: Gradle wrapper configuration
- `build.gradle.kts`, `settings.gradle.kts`: project build setup

## Credits

- **[Momir Printer](https://github.com/Devin-Cooper/momir-printer)** by Devin Cooper: the original
  Momir project that this app adapts for Android.
- **[acocalypso/Momir-Android](https://github.com/acocalypso/Momir-Android)**: the Android app this
  repository is forked from, including the Phomemo printer support.
- **[TiMini-Print](https://github.com/Dejniel/TiMini-Print)** by Daniel Banecki (Dejniel), licensed
  under the Apache License 2.0. The X6H support is based on its reverse-engineered protocol. The
  packet framing, row encoding and job layout in `TinyEncoder`, and the pause/resume handling in
  `TinyFlow`, were ported from it to Kotlin, with the printer parameters taken from its `x6h` profile.
- **[MTGJSON](https://mtgjson.com/)**: card data (AtomicCards).
- **[Scryfall](https://scryfall.com/)**: card images.

## Notes

- First bootstrap can take time because the app downloads and processes a large JSON file.
- BLE write pacing may need slight tuning on some devices/printers.
