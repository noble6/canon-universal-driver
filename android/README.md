# IVEC Print for Android

Prints to Canon IVEC printers (PIXMA, MAXIFY, G series) connected over USB, from any app's Print menu. It's the same driver as the Linux one, ported to Kotlin, and it works without root. Status: **early, needs testing on real printers**.

## What works

- Every model in the Linux driver (164 profiles plus the two generic ones), detected from the printer's device ID
- Paper sizes including borderless, color / grayscale, paper type and print quality (under "More options" in the print dialog)
- 2-sided printing: automatic on printers with a duplex unit, guided manual mode on the others (a notification tells you how to put the stack back, then Continue prints the backs)
- Paper out, paper jam and cover open show up as a blocked job with the reason; cancelling a job stops the printer
- Maintenance in the app: nozzle check, cleaning, deep cleaning, ink flush, roller and bottom plate cleaning, head alignment (whatever the model accepts)

## Setup

1. Install the APK and connect the printer (USB-C or OTG adapter on phones). Android asks whether to open IVEC Print for the device; allow it.
2. Settings > Connected devices > Printing (or the button in the app): turn on **IVEC Print**.
3. Print from any app and pick the Canon printer.

If the printer isn't in the model list, the app uses a generic profile. You can pick another model under "Printer profile", the same as `canonctl setup --model` on Linux.

## Building

```
cd android
./gradlew :core:test :app:assembleDebug
```

`core` is plain Kotlin (job format, PWG Raster encoder, maintenance, status parsing). Its tests rebuild streams written by the Linux filter and compare them byte for byte; make the reference streams with `research/android-refs.sh` (needs the Linux build and the `research/` setup). Without the references, only the self-contained tests run.

`app` contains the print service, USB transport, PDF rendering (`PdfRenderer`, in bands at 600 dpi), the options screen and the maintenance screen.
