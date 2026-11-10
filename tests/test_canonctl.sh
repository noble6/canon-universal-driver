#!/bin/bash
# canonctl helpers: V1/V2 generic choice and the maintenance capability check.
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CANONCTL_LIB=1 CANON_IVEC_PPDDIR="$ROOT/out/ppd" source "$ROOT/scripts/canonctl"
fail=0
expect() { if [ "$2" = "$3" ]; then echo "PASS canonctl $1"; else echo "FAIL canonctl $1: got '$2' want '$3'"; fail=1; fi; }

# generic profile choice from the device ID
expect "V1 generic (IVECPLI)" "$(basename "$(generic_ppd 'MFG:Canon;CMD:BJL,BJRaster3,BSCCe,NCCe,IVEC,IVECPLI;SOJ:BJNP2,BJNPe;MDL:G1000 series;')")" Canon-IVEC_Generic_older_models-ivec.ppd
expect "V1 generic (no CHMP)" "$(basename "$(generic_ppd 'MFG:Canon;CMD:BJRaster3,NCCe,IVEC;MDL:X series;')")" Canon-IVEC_Generic_older_models-ivec.ppd
expect "V2 generic" "$(basename "$(generic_ppd 'MFG:Canon;CMD:BJRaster3,NCCe,IVEC;SOJ:CHMP;MDL:Y series;')")" Canon-IVEC_Generic-ivec.ppd

# every profiled model with a known device ID: the V1 rule agrees with its protocol
bad=0 n=0
while IFS=$'\t' read -r proto id; do
    n=$((n + 1))
    if is_v1_device "$id"; then want=1; else want=2; fi
    [ "$proto" = "$want" ] || { echo "  rule mismatch: $id"; bad=1; }
done < <(python3 -c "
import json
for m in json.load(open('$ROOT/models/ivec_models.json'))['models']:
    if 'MFG:' in m['device_id'] and not m['id'].startswith('generic'):
        print(f\"{m['protocol']}\t{m['device_id']}\")")
expect "V1 rule matches all $n known device IDs" "$bad" 0

# capability check (exits non-zero when refused)
supported() { ( queue_ppd() { echo "$ROOT/out/ppd/$1"; }; check_supported "$@" ) 2>/dev/null && echo yes || echo no; }
g=Canon-G3010_series-ivec.ppd ts=Canon-TS8000_series-ivec.ppd
expect "G3010 deep clean all" "$(supported $g Cleaning deep all)" yes
expect "G3010 deep clean color refused" "$(supported $g Cleaning deep group2)" no
expect "G3010 align" "$(supported $g Align -)" yes
expect "G3010 flush" "$(supported $g Cleaning choke all)" yes
expect "TS8000 clean" "$(supported $ts Cleaning regular all)" yes
expect "TS8000 nozzle" "$(supported $ts TestPrint nozzle_check)" yes
expect "TS8000 align refused" "$(supported $ts Align -)" no
expect "TS8000 deep clean refused" "$(supported $ts Cleaning deep all)" no
exit $fail
