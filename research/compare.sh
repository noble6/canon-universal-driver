#!/bin/bash
# Byte-compare the open filter against Canon's reference for one model.
#   compare.sh <model-id e.g. canong3010> <in.pdf> [-o opt=val ...]
# Exit 0 when identical apart from the embedded clock.
W=${W:-$HOME/.cache/canon-ivec-re}
HERE=$(cd "$(dirname "$0")" && pwd)
id=$1 in=$2
shift 2
mkdir -p "$W/ours/bin/filter" "$W/cmp"
[ -e "$W/ours/bin/filter/pdftopdf" ] || ln -sf /usr/lib/cups/filter/* "$W/ours/bin/filter/"
rm -f "$W/ours/bin/filter/canonfilter"
B=$(readlink -f "$W/build")
ln -sf "$B/rastertocanonivec" "$W/ours/bin/filter/rastertocanonivec"
ln -sf "$B/cmdtocanonivec" "$W/ours/bin/filter/cmdtocanonivec"
printf "ServerBin %s\nDataDir /usr/share/cups\n" "$(readlink -f "$W/ours")/bin" > "$W/ours/cups-files.conf"
ppd=$(grep -l "^\*% model-id: $id\$" "$W"/ppds/*.ppd 2>/dev/null | head -1)
[ -n "$ppd" ] || { echo "no PPD for $id"; exit 2; }
"$HERE/refprint.sh" "$id" "$in" "$W/cmp/$id.ref" "$@"
cupsfilter -c "$W/ours/cups-files.conf" -p "$ppd" -m printer/foo -o job-uuid=urn:uuid:1 "$@" -e "$in" \
    > "$W/cmp/$id.ours" 2> "$W/cmp/$id.ours.log"
norm() { sed -E 's/(datetime>|SetTime=)[0-9]{14}/\1T/g' "$1"; }
if cmp -s <(norm "$W/cmp/$id.ref") <(norm "$W/cmp/$id.ours"); then
    echo "IDENTICAL $id $*"
else
    echo "DIFFERENT $id $* (ref $(stat -c%s "$W/cmp/$id.ref") ours $(stat -c%s "$W/cmp/$id.ours"))"
    exit 1
fi
