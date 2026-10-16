# Reverse-engineering harness

These tools use Canon's official Linux driver (cnijfilter2, built from the AUR into `~/.cache/canon-ivec-re`, never installed) as a **test oracle**. The driver in `src/` contains no Canon code and does not need any of this at build or run time.

| Script | Purpose |
|---|---|
| `setup_reference.sh` | Builds cnijfilter2 in a sandbox, wires up a private CUPS filter directory, and compiles the probes. |
| `capdump.c` | Decodes the obfuscated device ID and capability blocks embedded in Canon's PPDs. |
| `mapprobe.c` | Asks Canon's library which IVEC keyword each PPD media type and page size becomes. |
| `refprint.sh` | Renders a document through Canon's pipeline. |
| `ivecdump.py` | Pretty-prints an IVEC stream (`-f` shows the full XML). |
| `build_models.py` | Builds `models/ivec_models.json` from the above. |
| `compare.sh`, `compare_cmd.sh` | Byte-compare the open filters against Canon's for one model. |
| `sweep.sh` | Runs the comparisons for every model and writes `RESULTS.md`. |

Regenerating everything from scratch takes about 20 minutes, mostly in `mapprobe`:

```bash
research/setup_reference.sh
W=~/.cache/canon-ivec-re; export LD_LIBRARY_PATH=$W/ref/lib
for f in $W/cnijfilter2/pkg/cnijfilter2/usr/share/cups/model/*.ppd; do b=$(basename $f .ppd)
  $W/ref/mapprobe $f > $W/maps/$b.txt; $W/ref/capdump $f > $W/caps/$b.txt; done
# reference jobs for protocol detection (research/refprint.sh for each model into $W/refall/)
python3 research/build_models.py models/ivec_models.json
make && python3 ppd/generate_ivec_ppd.py models/ivec_models.json $W/ppds && research/sweep.sh
```
