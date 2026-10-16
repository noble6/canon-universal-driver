# Canon IVEC print protocol

IVEC is the job protocol spoken by Canon PIXMA, MAXIFY and similar inkjets released from about 2013 onwards (G, GM, GX, E, TS, TR, MG, MB, iB, XK and others). A printer that speaks it lists `IVEC` in the `CMD:` field of its IEEE-1284 device ID:

```
MFG:Canon;CMD:BJRaster3,NCCe,IVEC;SOJ:CHMP;MDL:G3010 series;CLS:PRINTER;DES:Canon G3010 series;VER:1.020;STA:10;CID:CA_IVEC1TYPE4_IJP;
```

This document describes the protocol as observed on the wire. The open driver in this repository reproduces Canon's output byte for byte on the models it was checked against (see [Verification](#verification)).

## Stream layout

A job is a sequence of small XML documents written back to back, with no separators or length prefixes. Page images follow their `SendData` command as raw binary. Over USB, the stream goes to the printer class bulk-OUT endpoint, which the standard CUPS `usb` backend handles.

```
StartJob
[SetJobConfiguration]          V2 only, when the model has a datetime capability
SetConfiguration               paper size / type, borderless, colour, duplex
  per page:
  VendorCmd SetPageConfiguration   nextpage=ON if more pages follow, OFF on the last page
  SendData                          format=PWGRaster, datasize=N
  <N bytes: complete PWG Raster stream for this one page>
EndJob
```

All documents share this envelope. Note the space before `?>`. One command, `SetJobConfiguration`, is sent without it, and printers accept both forms.

```xml
<?xml version="1.0" encoding="utf-8" ?><cmd xmlns:ivec="http://www.canon.com/ns/cmd/2008/07/common/" xmlns:vcn="http://www.canon.com/ns/cmd/2008/07/canon/"><ivec:contents><ivec:operation>OPERATION</ivec:operation><ivec:param_set servicetype="print"><ivec:jobID>00000002</ivec:jobID>...</ivec:param_set></ivec:contents></cmd>
```

The `xmlns:vcn` declaration is present only on commands that carry `vcn:` elements: `StartJob`, `VendorCmd` and `SetJobConfiguration`.

### Job framing variants

| Variant | Job ID | Models | Start of job |
|---|---|---|---|
| V2 | `00000002` | most models from ~2016 | `StartJob` with `jobname/username/computername`, `job_description` (CUPS job UUID in CDATA) and `vcn:host_environment=linux`, followed by `SetJobConfiguration` (`mismatch_mode=none`, `datetime=YYYYMMDDhhmmss`) |
| V1 | `00000001` | E200/E400/E460/E480, G3000/G4000, iB4000, MB/MG/TS older models | A separate clock job: `StartJob` → `VendorCmd ModeShift ijmode=1` → BJL `SetTime` → `EndJob`. Then the real job starts with a short `StartJob` (`bidi=0`, `forcepmdetection=OFF` only). |

BJL blocks are binary, not XML:

```
1B 5B 4B 02 00 00 1F  "BJLSTART\nControlMode=Common\nSetTime=20261016095559\nBJLEND\n"
```

### SetConfiguration

```xml
<ivec:papersize>iso_a4_210x297mm</ivec:papersize>
<ivec:papertype>stationery</ivec:papertype>
<ivec:borderlessprint>OFF</ivec:borderlessprint>
<ivec:printcolormode>color</ivec:printcolormode>     <!-- or monochrome -->
<ivec:duplexprint>OFF</ivec:duplexprint>
```

- **Paper sizes** are PWG 5101.1 self-describing names (`na_letter_8.5x11in`, `na_index-4x6_4x6in`, `jpn_hagaki_100x148mm`), plus `custom_canon_WxHmm` for Canon-specific sizes.
- **Paper types** are `stationery` (plain), `photographic`, or `custom-media-type-canon-N`. The N numbering is model-specific; the per-model tables are in `models/ivec_models.json`.
- **Grayscale** sets `printcolormode=monochrome`. On most models the raster is still sent as sRGB. On models with the *optimization* flag (G3080/G3090/G4080/G4090, GX5100/5500/6100/7100, BX110, TR160, TS8800, XK130), grayscale pages are sent as 1-channel sGray (`cupsColorSpace` 18) using `(2126 R + 7152 G + 722 B) / 10000`. In colour mode the host renders the whole job first; if no page contains a non-neutral pixel, the job goes out as `monochrome` with gray pages.
- **Duplex** sets `duplexprint=ON`. For long-edge duplex (`DuplexNoTumble`), the host rotates odd pages (1, 3, 5, ...) by 180 degrees. Short-edge duplex sends all pages unrotated.

### Page data (PWG Raster)

Each page is its own complete PWG Raster stream (`RaS2` sync word, one 1796-byte header, compressed lines):

- 8-bit sRGB (`cupsColorSpace` 19), 3 colours, chunked, at the model resolution (600 dpi on every listed model).
- `cupsWidth` × `cupsHeight` equals the model's **printable area** for that paper size. For example, A4 on a G3010 is 4800 × 6826 px, and A4 borderless is 5092 × 7181 px, slightly larger than the sheet so the image bleeds off the edges.
- The rendered page is fitted into that area: aspect ratio preserved, centred, nearest-neighbour sampling with the end pixels pinned. Fill is white.
- Header details that Canon's driver sets, and that this driver copies:
  - `TotalPageCount` (`cupsInteger[0]`) = 1
  - `AlternatePrimary` (`cupsInteger[7]`) = `0xFFFFFF`
  - The `ImageBox*` words (`cupsInteger[3..6]`) hold `ImagingBBox_points × dpi`, **without** the `/72` the PWG spec calls for.
  - `MediaType`, `PageSizeName`, `RenderingIntent`, `ImagingBoundingBox` and `PageSize` are copied from the CUPS raster header.

## Maintenance

Maintenance jobs use `servicetype="maintenance"`, always with job ID `00000002`:

```
StartJob            (no forcepmdetection)
[SetJobConfiguration datetime]      if the maintenance capability lists <ivec:datetime/>
<Operation>         parameters come BEFORE the jobID element
EndJob
```

| Operation | `ivec:type` | Extra | What it does |
|---|---|---|---|
| `Cleaning` | `regular` | `inkgroup` = `all`, `group1`, `group2` | Print head cleaning |
| `Cleaning` | `deep` | `inkgroup` | Deep cleaning |
| `Cleaning` | `choke` | `inkgroup` | Ink flush / system cleaning (uses a lot of ink) |
| `RollerCleaning` | `roller`, `platen` | | Paper feed roller cleaning, bottom plate cleaning |
| `TestPrint` | `nozzle_check` | | Nozzle check pattern |
| `TestPrint` | `auto_registration`, `half_auto_registration`, `regi_check`, `manual_registration` (`id`) | | Print head alignment variants |

Each model lists exactly which of these it supports in its maintenance capability. That list becomes `*cnivecMaint` in the PPD, and `cmdtocanonivec` refuses anything not listed.

Older models (those that used Canon's `cmdtocanonij2`) take maintenance as a BJL line wrapped in the V1 clock-job framing: `@Cleaning=1ALL` or `@TestPrint=NozzleCheck`.

Canon's own Linux filter writes `encoding="job_descriptionutf-8"` in the maintenance `SetJobConfiguration`, a string-concatenation bug that printers ignore. This driver writes `utf-8`.

## Status

The printer answers **every** read of the USB bulk-IN endpoint with a complete `GetStatusResponse` document. It does not push status on its own; read about once a second. It can also be asked explicitly with

```xml
<?xml version="1.0" encoding="utf-8" ?><cmd xmlns:ivec="http://www.canon.com/ns/cmd/2008/07/common/"><ivec:contents><ivec:operation>GetStatus</ivec:operation><ivec:param_set servicetype="print"></ivec:param_set></ivec:contents></cmd>
```

and the reply is a `GetStatusResponse` with

| Element | Values seen in Canon's driver |
|---|---|
| `ivec:status` | `idle`, `processing`, `stopped`, `notready`, `busying`, `canceling`, `waitingprint`, `printing`, `held`, ... |
| `ivec:status_detail` | `MediaEmpty`, `MediaJam`, `DoorOpen`, `MarkerSupplyAttention`, `DeviceBusy`, `AttentionRequired` |
| `ivec:current_support_code` | Canon support code shown in manuals, e.g. `1000` (paper out), `1300` (jam) |

Observed on a G3010 while out of paper: `idle` -> `processing` -> `stopped` / `MediaEmpty` / `1000`. The USB printer-class port status also sets its paper-empty bit (`0x20`).

There is no host-side "resume" operation. After paper is loaded, the job does **not** continue by itself; the user presses the printer's Black or Color button and it goes back to `processing`.

### Cancelling a job on the printer

Stopping the data stream is not enough, because the printer keeps the job and waits for paper. What works (verified on a G3010):

1. USB printer-class `SOFT_RESET` (control request `0x21`, `bRequest` 2, `wIndex` = interface). This drops queued data, but on its own leaves the printer in `stopped` / `MediaEmpty`.
2. `CancelJob` with the job's service type and ID:
   ```xml
   <?xml version="1.0" encoding="utf-8" ?><cmd xmlns:ivec="http://www.canon.com/ns/cmd/2008/07/common/"><ivec:contents><ivec:operation>CancelJob</ivec:operation><ivec:param_set servicetype="print"><ivec:jobID>00000002</ivec:jobID></ivec:param_set></ivec:contents></cmd>
   ```
   The printer goes `busying` / `DeviceBusy` / `D103`, then `idle`, and the error lamp clears.
3. Another `SOFT_RESET`. After `CancelJob` the printer silently discards all input, including later jobs, until it is reset.

The stock CUPS `usb` backend cannot do this. It ignores SIGTERM while a driver feeds it, its reset waits for a write that never completes while the printer is stalled, and it never sends `CancelJob`. That is why this project ships its own `canonivec` backend.

The `canonivec` backend reads this status during every job and maps it to CUPS `printer-state-reasons` (`media-empty-error`, `media-jam-error`, `door-open-error`, `other-error`). `rastertocanonivec` does the same through the back channel when a queue uses the stock `usb` backend. `canon-ivec-notify` turns the reasons into desktop notifications.

## Capabilities

Every IVEC printer can describe itself with a `GetCapabilityResponse` XML document: data formats, paper sizes with printable areas and margins, paper types, which paper types allow borderless and colour, print qualities, resolutions, input bins and maintenance operations. Canon's Linux package embeds these responses in its PPDs in an obfuscated form. `research/` contains the harness that decodes them, using Canon's library at research time only, and `research/build_models.py` turns them into `models/ivec_models.json`.

A future version could request the capability from the printer over the USB IN endpoint and support unknown models fully automatically. Until then, unknown IVEC printers get the conservative generic profile.

## Verification

`research/compare.sh` runs the same document through Canon's official cnijfilter2 6.81 pipeline and through this driver, and compares the output after normalising the embedded clock. `research/compare_cmd.sh` does the same for maintenance commands. Results are recorded in `research/RESULTS.md`.
