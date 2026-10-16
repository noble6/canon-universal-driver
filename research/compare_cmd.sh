#!/bin/bash
# Compare maintenance command output: compare_cmd.sh <model-id> "<command>"
# Canon's broken encoding="job_descriptionutf-8" declaration and the block of
# NUL bytes its filter writes on some models (BX110) are normalised away.
W=${W:-$HOME/.cache/canon-ivec-re}
id=$1 cmd=$2
mkdir -p "$W/cmp"
printf '#CUPS-COMMAND\n%s\n' "$cmd" > "$W/cmp/cmd.txt"
ppd=$(grep -l "^\*% model-id: $id\$" "$W"/ppds/*.ppd | head -1)
cupsfilter -c "$W/ref/cups-files.conf" -p "$W/cnijfilter2/pkg/cnijfilter2/usr/share/cups/model/$id.ppd" \
    -m printer/foo -o job-uuid=urn:uuid:1 -i application/vnd.cups-command -e "$W/cmp/cmd.txt" > "$W/cmp/$id.mref" 2>/dev/null
cupsfilter -c "$W/ours/cups-files.conf" -p "$ppd" \
    -m printer/foo -o job-uuid=urn:uuid:1 -i application/vnd.cups-command -e "$W/cmp/cmd.txt" > "$W/cmp/$id.mours" 2>"$W/cmp/$id.mours.log"
norm() { tr -d '\000' < "$1" | sed -E 's/(datetime>|SetTime=)[0-9]{14}/\1T/g; s/job_descriptionutf-8/utf-8/g'; }
if [ ! -s "$W/cmp/$id.mref" ]; then echo "NOREF $id $cmd ($(grep -m1 ERROR "$W/cmp/$id.mours.log"))"
elif cmp -s <(norm "$W/cmp/$id.mref") <(norm "$W/cmp/$id.mours"); then echo "IDENTICAL $id $cmd"
else echo "DIFFERENT $id $cmd"; exit 1; fi
