#!/bin/bash
# Render a document through Canon's reference pipeline.
#   refprint.sh <model e.g. canong3010> <in.pdf> <out.bin> [-o opt=val ...]
W=${W:-$HOME/.cache/canon-ivec-re}
model=$1 in=$2 out=$3
shift 3
exec cupsfilter -c "$W/ref/cups-files.conf" \
    -p "$W/cnijfilter2/pkg/cnijfilter2/usr/share/cups/model/$model.ppd" \
    -m printer/foo -o job-uuid=urn:uuid:1 "$@" -e "$in" > "$out" 2> "$out.log"
