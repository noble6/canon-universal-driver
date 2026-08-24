# Canon Universal Driver for Linux

Open-source CUPS driver bringing native Linux print support to Canon inkjet printers that the vendor has abandoned — PIXMA G, E, iP, MG, TR/TS, and imageCLASS series — built on reverse engineering of Canon's printer command protocols.

> ⚠️ **Project status: early development (`v0.1.0`).** The core pipeline is functional and printing works end-to-end over Wi-Fi. USB data-path integration is in progress. See [Project State](#project-state).

## Why

Canon ships no Linux drivers for most of its modern and older printer lines (PIXMA G/E/iP/MG/TR/TS, several imageCLASS models). This project fills that gap with standard CUPS filters and direct hardware access via libusb — no proprietary code, purely interoperability-driven reverse engineering.

## Project State

| Component | Status | Notes |
|---|---|---|
| CUPS raster filter (`canonfilter`) | ✅ Working | Converts CUPS raster → device commands |
| G-series protocol (PIXMA G1010/G2010/G3010/G4010) | ✅ Implemented | ESC-sequence job setup, PackBits-compressed raster lines |
| Wi-Fi printing | ✅ Working | Via CUPS driverless IPP (AirPrint-class); custom BJNP network stack is a roadmap item |
| USB data path | 🚧 In progress | libusb backend exists; filter output routing over bulk transfers is next |
| Other protocol families (E/iP/MG/TR-TS/imageCLASS) | 🧪 Scaffolded | Ops interface defined, implementations pending |
| PPD generation | ✅ Working | 16 model PPDs auto-generated from `models.json` via Jinja2 |

## Architecture

```
Application → CUPS → Ghostscript/poppler (rasterize) → canonfilter (this driver)
    → Canon ESC/P-raster command stream → printer (IPP/network today, libusb/USB next)
```

- **`src/filter/`** — CUPS filter: reads `cupsRaster` streams, dispatches per-page through the active protocol implementation.
- **`src/protocol/`** — pluggable protocol layer. Each printer family implements a `canon_protocol_ops_t` vtable (job init, resolution/media setup, raster transmission, status, teardown). Includes a hand-written PackBits RLE codec for Canon's compressed raster format.
- **`src/backend/`** — transports. libusb-1.0 backend (device open by VID/PID, interface claim, bulk endpoints).
- **`models/models.json`** — single source of truth: 16 models across 6 families (USB IDs, protocol family, features, max DPI).
- **`ppd/`** — Jinja2 template + generator producing one PPD per model at build time.

## Supported Models

See [`models/models.json`](models/models.json) for the authoritative list of USB product IDs:

PIXMA **G1010 · G2010 · G3010 · G4010** · **E410 · E460 · E3100** · **iP2870 · iP7270** · **MG2570 · MG3070 · MG3110** · **TR4570 · TS3370** · imageCLASS **MF3010 · MF232w**

Your model isn't listed? Protocol families are shared across series — see [`docs/ADDING_NEW_MODEL.md`](docs/ADDING_NEW_MODEL.md) to add yours.

## Building

Requires: `cups`, `libusb-1.0`, `python-jinja2`, `meson`, `ninja`

```bash
meson setup build
ninja -C build          # builds canonfilter + generates all PPDs
sudo ninja -C build install
```

Or with plain make:

```bash
make
sudo make install
```

## Setup

Connect the printer, then either use the CUPS web interface (`http://localhost:631`) or:

```bash
sudo ./scripts/add_printer.sh
```

Wi-Fi users: add the printer as a driverless IPP queue from the CUPS web UI (discovered under *Administration → Find New Printers*), then select the generated PPD for your model.

## Roadmap

- [ ] Route filter output through the libusb backend (native USB printing)
- [ ] Implement remaining protocol families (E, iP, MG, TR/TS, imageCLASS)
- [ ] Status/ink-level reporting over USB interrupt endpoint
- [ ] Canon BJNP network discovery/printing protocol
- [ ] Debian/RPM packaging alongside the AUR PKGBUILD

## Contributing

The easiest high-value contribution is hardware testing: capture your printer's behavior and open an issue/PR. Adding a new model is often just a JSON entry — see [`docs/ADDING_NEW_MODEL.md`](docs/ADDING_NEW_MODEL.md).

## License

GPL-3.0-or-later — see [LICENSE](LICENSE).

## Legal

This driver is produced through independent clean-room reverse engineering for interoperability purposes. It contains no Canon proprietary code. **Not affiliated with or endorsed by Canon Inc.**

