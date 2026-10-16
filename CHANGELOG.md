# Changelog

## 1.1.0

- `canonivec` USB backend: reads the printer's status during every job, and cancelling a job now aborts it on the printer (soft reset, `CancelJob`, reset). The stock CUPS USB backend leaves Canon printers waiting until their Stop button is pressed.
- Printer status monitoring: paper out, paper jam, cover open and other errors are reported to CUPS with Canon's support code. Verified on a G3010.
- `canon-ivec-notify`: desktop notifications with Resume / Cancel job / Open queue, started at login.
- Standard `ColorModel` (Color / Grayscale) option, so Chrome, GTK and Qt dialogs offer black-and-white printing.
- Long-edge duplex rotates odd pages like Canon's driver; grayscale on "optimization" models sends 1-channel pages and auto-detects all-gray jobs.
- BJL-style maintenance on V2 models and maintenance capability details (`host_environment`) now match Canon byte for byte.
- `canonctl report`, `install-local.sh` updates existing queues, docs rewritten, CI added.

## 1.0.0

- First release of the IVEC open driver: `rastertocanonivec`, `cmdtocanonivec`, 164 model profiles plus a generic profile, `canonctl`.
- Replaces the earlier 0.1.0 prototype, whose ESC/P-style output did not work with Canon inkjets.
