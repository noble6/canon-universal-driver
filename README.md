# Canon PIXMA / MAXIFY driver for Linux (IVEC)

An open-source CUPS driver for Canon inkjet printers on Linux, covering the PIXMA G (MegaTank) series G1010/G2010/G3010/G4010, G3020, G5080, G6080 and GX, plus E, TS, TR, MG, MX, MB, iB, XK and GM. It provides printing, head cleaning, nozzle checks, alignment and paper-out alerts, with no Canon binaries.

[![CI](https://github.com/noble6/canon-universal-driver/actions/workflows/ci.yml/badge.svg)](https://github.com/noble6/canon-universal-driver/actions/workflows/ci.yml)
![License: GPL-3.0-or-later](https://img.shields.io/badge/license-GPL--3.0--or--later-blue)
![Models: 164](https://img.shields.io/badge/models-164-informational)

If you kept a Windows partition only because your Canon printer or its maintenance tools did not work on Linux, this project is for you.

## Highlights

- **164 printer models** with full profiles: paper sizes, margins, media types, borderless, duplex, and which maintenance operations each one supports. See [docs/SUPPORTED_MODELS.md](docs/SUPPORTED_MODELS.md). Other Canon printers that speak the same protocol fall back to a generic profile.
- **Exactly what Canon's driver sends.** For every model, the output was compared byte for byte with Canon's official Linux driver: 1194 of 1194 jobs and maintenance commands match ([research/RESULTS.md](research/RESULTS.md)).
- **Fully open.** No proprietary libraries, no 32-bit dependencies. Plain C on top of libcups, so it should build anywhere CUPS runs, including ARM boards like the Raspberry Pi.
- **The maintenance tools you were booting Windows for:** cleaning, deep cleaning, ink flush (system cleaning), nozzle check, print head alignment, roller and bottom plate cleaning.
- **Paper-out, jam and cover-open alerts** as desktop notifications, like the Windows status monitor, and a **Cancel** that really stops the printer (the stock CUPS USB backend cannot do that with Canon printers).
- **Works everywhere printing works:** LibreOffice, Firefox, Chrome, GTK and Qt apps, and the `lp` command line all see the printer and its options.

## Quick start (Arch Linux, Manjaro, EndeavourOS, CachyOS)

```bash
git clone https://github.com/noble6/canon-universal-driver.git
cd canon-universal-driver
sudo pacman -S --needed base-devel cups ghostscript python libusb
make && make check
sudo scripts/install-local.sh        # installs, creates the queue, starts notifications
lp /usr/share/cups/data/testprint
```

Or build a package: `cd install && makepkg -si`, then run `canonctl setup`.

### Debian, Ubuntu, Linux Mint

```bash
sudo apt install build-essential libcups2-dev libusb-1.0-0-dev cups cups-filters ghostscript python3 libnotify-bin
make && make check
sudo make install
canonctl setup
```

### Fedora

```bash
sudo dnf install gcc make cups-devel libusb1-devel cups cups-filters ghostscript python3 libnotify
make && make check
sudo make install
canonctl setup
```

## Using it

`canonctl setup` picks the right profile from the printer's own model ID. Over USB it uses this driver. For printers on Wi-Fi it creates a driverless IPP queue, which the printer supports natively.

Print from any application. Useful options:

| Option | Where you find it | Values |
|---|---|---|
| Paper size | every print dialog | A4, Letter, Legal, A5, B5, 4x6, 5x7, 8x10, envelopes, ... and "(borderless)" variants |
| Color mode | every print dialog | Color, Grayscale |
| Print quality | every print dialog ("Quality") | Draft, Normal, High (on models that support it) |
| Media type | LibreOffice: Properties. Chrome: "Print using system dialog" | Plain paper, glossy photo papers, matte, envelopes, ... |
| 2-sided | every print dialog | Long edge, Short edge. Models without a duplex unit (G3010 and other ink-tank models) get guided manual 2-sided printing: the front sides print, a window shows a picture of how to put the stack back (blank side facing you, TOP edge in first), and **Continue** prints the backs (or run `canonctl continue`). |

From the shell: `lp -o PageSize=4x6.bl -o MediaType=glossygold photo.jpg`, or list options with `lpoptions -l`.

### Maintenance

```bash
canonctl nozzle          # nozzle check pattern
canonctl clean           # print head cleaning, all inks  (clean group1 = black, group2 = colour)
canonctl deep-clean      # deep cleaning
canonctl flush           # ink flush / system cleaning; uses a lot of ink, asks first
canonctl align           # print head alignment
canonctl roller          # paper feed roller cleaning
canonctl platen          # bottom plate cleaning
canonctl status
```

The CUPS web interface (http://localhost:631) and desktop printer settings also offer *Clean Print Heads* and *Print Self-Test Page*.

### Paper out and other alerts

When the printer stops for paper, a jam or an open cover, a notification shows what happened and Canon's support code (1000 = out of paper). The fix is the same as on Windows: load paper, then press the printer's **Black** or **Color** button, and printing continues where it stopped. The printer does not continue by itself after paper is loaded.

**Cancel printing** in the notification, the print queue window or `cancel` aborts the job on the printer itself: its error lamp clears and it is ready for the next job. If CUPS paused the queue after an error, the notification offers **Resume** instead.

Notifications start automatically at login (`canon-ivec-notify`).

Alerts and cancel were verified on a G3010: paper out, resume with the Black button, and cancel while waiting for paper. Other models use the same status messages. If an alert does not appear on yours, run `sudo canonctl report` after reproducing it and attach the output to an issue.

### Scanning

Scanning is handled by SANE, which already supports most of these printers:

```bash
sudo pacman -S sane sane-airscan simple-scan     # USB: pixma backend, Wi-Fi: eSCL
canonctl scan
```

## How it works

Canon inkjets since about 2013 speak **IVEC**: a stream of small XML commands (`StartJob`, `SetConfiguration`, `SendData`, `EndJob`, ...) with each page sent as a standard PWG Raster image. This driver renders pages with Ghostscript, fits them to the model's printable area, and wraps them in IVEC commands. Its own USB backend (`canonivec://`) sends them, reads the printer's status the whole time, and can cancel a job on the printer.

The protocol was worked out by running Canon's official Linux driver in a sandbox and diffing its output across 164 models and every option combination. The full write-up is in [docs/PROTOCOL_IVEC.md](docs/PROTOCOL_IVEC.md), and [research/](research/) has the harness to reproduce it.

```
application -> CUPS -> Ghostscript -> rastertocanonivec -> canonivec backend <-> printer
                                        (IVEC + PWG)          (status, cancel)
canonctl / desktop -> cmdtocanonivec (maintenance)            canon-ivec-notify (alerts)
```

## Troubleshooting

See [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md). Start with:

```bash
canonctl doctor
canonctl detect
```

## Contributing

Hardware reports are the most valuable contribution: does your model print, do the margins look right, does the paper-out alert appear? See [CONTRIBUTING.md](CONTRIBUTING.md). Bug reports should include the output of `canonctl detect`, `canonctl doctor` and, for status problems, `sudo canonctl report`.

## License

GPL-3.0-or-later. This project contains no Canon code. Canon, PIXMA and MAXIFY are trademarks of Canon Inc. This project is not affiliated with or endorsed by Canon.
