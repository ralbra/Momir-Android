# Contributing Support for New Thermal Printers

Thank you for helping expand printer compatibility! Even if you are not a developer, you can provide the information needed to add support for your thermal printer.

In most cases, **you do not need to own any special hardware or write any code**. Simply collecting Bluetooth information and a few screenshots is enough.

---

# Step 1 – Tell us about your printer

Please provide:

- Manufacturer (e.g. Phomemo)
- Model (e.g. M110)
- Firmware version (if available)
- Android phone model
- Android version

---

# Step 2 – Bluetooth Information

Install **nRF Connect for Mobile** from the Play Store.

1. Turn on your printer.
2. Open nRF Connect.
3. Scan for Bluetooth devices.
4. Find your printer.
5. Tap **Connect**.
6. Wait until all services are discovered.

Please provide either:

- an exported device report (preferred), or
- screenshots of:
  - Advertisement information
  - Services
  - Characteristics

The following information is especially useful:

- Device Name
- MAC Address
- Manufacturer Data
- Service UUIDs
- Writable Characteristics
- Notify Characteristics

---

# Step 3 – Bluetooth HCI Snoop Log (Highly Recommended)

This allows us to reverse engineer the communication protocol if your printer is not already supported.

## Enable HCI Logging

On Android:

Settings → About Phone

Tap **Build Number** seven times to enable Developer Options.

Then:

Settings → Developer Options

Enable:

**Bluetooth HCI snoop log**

---

## Capture a Print

1. Enable Bluetooth HCI logging.
2. Restart Bluetooth (or reboot the phone).
3. Open the manufacturer's official printing app.
4. Print a very small test label (for example "Hello").
5. Disable Bluetooth HCI logging.

The log file is usually located at:

```
/sdcard/btsnoop_hci.log
```

or can be shared directly from Android depending on the device.

Compress the file into a ZIP before uploading.

---

# Step 4 – Printer Specifications

Please tell us:

- Maximum label width
- Supported label widths
- Resolution (DPI) if known
- Continuous labels or die-cut labels
- Black mark or gap sensing
- Maximum printable width

If you have a user manual, attach it.

---

# Step 5 – Photos

Please include:

- Front of the printer
- Label compartment
- Bottom sticker (model number)
- Bluetooth pairing screen
- Manufacturer app showing the connected printer

---

# Step 6 – Open a GitHub Issue

Please include:

- Printer model
- Phone model
- Android version
- Everything collected above

Attach:

- nRF Connect export
- Bluetooth HCI log (ZIP)
- Photos
- Manual (if available)

---

# Optional: Developer Information

If you are a developer, the following information is extremely helpful:

- BLE MTU
- Write characteristic UUID
- Notify characteristic UUID
- Write type
  - Write With Response
  - Write Without Response
- Packet size
- Command protocol
- Initialization commands
- Print commands
- End-of-job command

---

# Reference: Tiny Print X6H

This is what we found out about the X6H while adding support for it. It shows what the
"Developer Information" above looks like for a finished driver.

## Device

- Advertises as `X6h-XXXX` (for example `X6h-E16D`).
- Dual-mode: it offers classic Bluetooth (SPP and other profiles) as well as BLE. The app uses BLE.
- Protocol: the "tiny" family from [TiMini-Print](https://github.com/Dejniel/TiMini-Print)
  (profile `x6h`): 384 dots print width, 200 dpi, one bit per pixel, RLE-compressed rows.

## BLE services

Found with a `bleak` scan on Linux (see the workaround below).

| Service | Characteristic | Properties | Used by the app |
| --- | --- | --- | --- |
| `AE30` | `AE01` | write without response | **Yes, print data** |
| `AE30` | `AE02` | notify | **Yes, flow control and status replies** |
| `AE30` | `AE03` | write without response | No |
| `AE30` | `AE04` | notify | Subscribed, never sent anything in our tests |
| `AE30` | `AE05` | indicate | Subscribed, never sent anything in our tests |
| `AE30` | `AE10` | read, write | No |
| `AE3A` | `AE3B` | write without response | No |
| `AE3A` | `AE3C` | notify | No |

All UUIDs have the standard form `0000XXXX-0000-1000-8000-00805f9b34fb`. The printer's UUID list
also contains `AF30` (visible in BlueZ output), but no GATT service with that UUID exists.

Settings that work: MTU request 512 (512 was negotiated), writes without response, 180-byte chunks.

**Connection order matters.** Request the MTU, wait for it, discover services, wait, and only
then write the notification descriptors one after another. When these steps overlapped, a
descriptor write never completed and the first data write failed with a busy GATT stack.

## Packet format

Every command is framed as:
```51 78 | cmd | 00 | length (2 bytes, little-endian) | payload | crc8(payload) | FF```


The CRC is CRC-8 with polynomial `0x07` and initial value 0. The job the app sends:

| Command | Meaning |
| --- | --- |
| `A4` | Darkness (blackening): payload `0x30 + level`, level 1-5 |
| `AF` | Energy: 9500 as 2 bytes little-endian |
| `BE` | Image mode: `00` |
| `BD` | Feed speed 10, repeated after every 200 rows |
| `BF` / `A2` | One row each: `BF` is the RLE row, `A2` the raw row (LSB-first), used when RLE is not smaller |
| `BD` | Feed padding 12 |
| `A1` | Paper feed, payload `30 00`, sent twice |
| `BD` | Feed padding 12 again |
| `A3` | State query, always the last packet |

## Flow control and completion

The printer sends its notifications on `AE02`:

- **Pause:** `51 78 AE 01 01 00 10 70 FF`. The app stops sending (it waits up to 5 s).
- **Resume:** `51 78 AE 01 01 00 00 00 FF`. The app continues.
- **Job finished:** the answer to the final `A3` query, `51 78 A3 01 03 00 <3 payload bytes> <crc> FF`.
  It arrives only after the printer has worked through the whole job (about 14-17 s for a card).

Observed `A3` payloads:

| Payload | Seen when | Reply time after the last data write |
| --- | --- | --- |
| `00 15 28` | Normal print | about 14-17 s |
| `01 1b 28` | Printer not ready (the print did not happen) | about 2 s |

The first payload byte is non-zero when the printer needs attention, and the app reports that as
"check paper / cover". What the other bytes and the individual bits mean is not known yet.

## Troubleshooting on Linux (bleak / BlueZ)

Because the X6H is dual-mode, BlueZ may connect over classic Bluetooth instead of BLE. Symptoms:

- `bluetoothctl connect` fails with `org.bluez.Error.Failed br-connection-refused`, and
- `bleak` raises `failed to discover services, device disconnected`.

Fix: force BlueZ to LE only.

1. Remove the old pairing: `bluetoothctl remove <address>`
2. In `/etc/bluetooth/main.conf`, under `[General]`, set `ControllerMode = le`
3. Restart the service: `sudo systemctl restart bluetooth`
4. Scan and connect again; the GATT services above should now be listed.

`ControllerMode = le` disables classic Bluetooth for **all** devices on that computer, so set it
back when you are done.

---

# Privacy

The Bluetooth HCI log only contains Bluetooth traffic between your phone and nearby devices. While it normally does not contain personal information, please review it before sharing if you have privacy concerns.

---

# Thank You!

Every new printer supported by the project starts with information provided by users like you. Even if you can't write code, your logs and screenshots can make your printer compatible for everyone.
