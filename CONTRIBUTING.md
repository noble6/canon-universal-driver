# Contributing

Thanks for helping Canon printers work on Linux.

## Hardware reports

The most useful contribution is telling us how your printer behaves. Open an issue with:

- `canonctl detect` output (it includes the printer's device ID),
- what you printed and the options used,
- what went wrong (photos of misprinted pages help with margin problems),
- for alerts or stuck jobs: `sudo canonctl report` after reproducing the problem.

Reports that a model **works** are just as welcome. They are how the supported list becomes trustworthy.

## Adding a model

Profiles are generated from capability data in `models/ivec_models.json`. For printers Canon's Linux package knows about, the `research/` harness regenerates everything (see `research/README.md`). For other IVEC printers, open a "new model" issue with the `canonctl detect` output; the generic profile works in the meantime.

## Code changes

```bash
make && make check
```

- Keep the filters plain C on top of libcups only. No new runtime dependencies without discussion.
- Changes to what is sent to the printer must keep `research/sweep.sh` at 100% identical, or explain in the pull request why the output now differs from Canon's driver.
- Never copy code from Canon's packages. Their open parts are GPL-2.0-only and incompatible with this project's GPL-3.0-or-later. Behaviour is documented in `docs/PROTOCOL_IVEC.md`; implement from that.
- Match the surrounding style: four-space indent, short comments that explain why.

## Releases

Version numbers live in `install/PKGBUILD` and `CHANGELOG.md`.
