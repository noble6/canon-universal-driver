# Changelog

## 1.6.0

- Canon Photo Print: borderless photos are laid out for the part of the page that actually lands on the paper. The printer prints past the edges by a model-specific amount (G3010: 2 mm top, 2.8 mm sides, 5 mm bottom); the preview now shows the real crop and nothing important is cut off at the bottom.
- PPDs carry the borderless overhang per paper size (`*cnivecOverhang`, from each model's capability data).
- `research/pwg2png.py` decodes the PWG pages of an IVEC stream, optionally cropped to the paper.

## 1.5.0

- Canon Photo Print: **Color** panel (brightness, contrast, saturation, cyan / magenta / yellow balance) with live preview; faster preview for large photos.
- `canonctl clean|deep-clean|flush` accept `black` and `color` like the Windows tools (Canon ink groups 1 and 2).

## 1.4.0

- **Canon Photo Print** (`canon-photo-print`): photo printing app with live preview; borderless 4x6 / 5x7 / A4 and other sizes from the printer's own list, 1/2/4/6/9 photos per sheet, fill or fit, automatic rotation and EXIF orientation, photo paper and High quality by default. In the application menu, "Open With" and Dolphin's right-click "Print photos...".

## 1.3.0

- Right-click **Print / Print 2-sided / Print in grayscale** in Dolphin (KDE service menu) via `canon-ivec-print`; office documents are converted with LibreOffice first.
- Packages for every release, built by GitHub Actions: an Arch package plus a pacman repository (`Server = .../releases/latest/download`) and a Debian/Ubuntu `.deb`.
- AUR-ready recipe in `packaging/arch`.

## 1.2.0

- Print quality: standard `cupsPrintQuality` option (Draft / Normal / High), mapped from IPP `print-quality`, so every dialog shows it. Normal keeps the output identical to Canon's driver; High sends `printquality=best`; Draft sends `fast_draft` at the model's draft resolution (300 dpi on G3010 and similar) on paper types that allow it.
- Guided manual 2-sided printing on the 44 models without a duplex unit: the standard 2-sided option prints the fronts, then a window with a step-by-step drawing (`canon-ivec-flip`, Qt or GTK) shows how to put the stack back, and Continue (or `canonctl continue`) prints the backs. One instruction for long and short edge: blank side facing you, TOP edge in first. Page order and orientation verified on a G3010; `CNManualBack=Turned` adjusts printers whose paper path differs.
- Duplicate printers: `71-canon-ivec.rules` stops system-config-printer from adding a second `usb://` queue for Canon printers; `install-local.sh` removes existing duplicates.

## 1.1.0

- `canonivec` USB backend: reads the printer's status during every job, and cancelling a job now aborts it on the printer (soft reset, `CancelJob`, reset), and a job stopped with the printer's Stop button is reported as cancelled. The stock CUPS USB backend leaves Canon printers waiting until their Stop button is pressed.
- Printer status monitoring: paper out, paper jam, cover open and other errors are reported to CUPS with Canon's support code. Verified on a G3010.
- `canon-ivec-notify`: desktop notifications with Resume / Cancel job / Open queue, started at login.
- Standard `ColorModel` (Color / Grayscale) option, so Chrome, GTK and Qt dialogs offer black-and-white printing.
- Long-edge duplex rotates odd pages like Canon's driver; grayscale on "optimization" models sends 1-channel pages and auto-detects all-gray jobs.
- BJL-style maintenance on V2 models and maintenance capability details (`host_environment`) now match Canon byte for byte.
- `canonctl report`, `install-local.sh` updates existing queues, docs rewritten, CI added.

## 1.0.0

- First release of the IVEC open driver: `rastertocanonivec`, `cmdtocanonivec`, 164 model profiles plus a generic profile, `canonctl`.
- Replaces the earlier 0.1.0 prototype, whose ESC/P-style output did not work with Canon inkjets.
