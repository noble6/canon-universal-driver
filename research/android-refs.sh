#!/bin/bash
# Reference streams for the Android port's tests (android/core ReferenceTest):
# the Linux filter's output for a few model types, rebuilt by the Kotlin code
# from the decoded pixels and compared byte for byte.
#   W=~/.cache/canon-ivec-re research/android-refs.sh   then   cd android && gradle :core:test
W=${W:-$HOME/.cache/canon-ivec-re}
OUT=${IVEC_REFS:-$W/and}
mkdir -p "$OUT"
ppd() { grep -l "^\*% model-id: $1\$" "$W"/ppds/*.ppd | head -1; }
job() {
    local id=$1 out=$2 doc=$3; shift 3
    cupsfilter -c "$W/ours/cups-files.conf" -p "$(ppd "$id")" -m printer/foo -o job-uuid=urn:uuid:1 "$@" \
        -e "$doc" > "$OUT/$out.bin" 2> "$OUT/$out.log"
}
cmd() {
    printf '#CUPS-COMMAND\n%s\n' "$3" > "$OUT/cmd.txt"
    cupsfilter -c "$W/ours/cups-files.conf" -p "$(ppd "$1")" -m printer/foo -o job-uuid=urn:uuid:1 \
        -i application/vnd.cups-command -e "$OUT/cmd.txt" > "$OUT/$2.bin" 2>/dev/null
}
t3=$W/t/t3.pdf gray=$W/t/gray.pdf
job canong3010 g3010 "$t3"
job canong3010 g3010-bl "$t3" -o PageSize=A4.bl -o MediaType=glossygold -o CNBorderlessCover=False
job canong3010 g3010-gray "$t3" -o ColorModel=Gray -o cupsPrintQuality=High
job canong3010 g3010-draft "$t3" -o cupsPrintQuality=Draft
job canong3080 g3080-dup "$t3" -o Duplex=DuplexNoTumble
job canong3080 g3080-gray "$t3" -o ColorModel=Gray -o Duplex=DuplexTumble
job canong3080 g3080-auto "$gray"
job canone200 e200 "$t3"
cmd canong3010 m-g3010-clean "Clean all"
cmd canong3010 m-g3010-deep "com.canon.Cleaning deep group1"
cmd canong3010 m-g3010-nozzle "PrintSelfTestPage"
cmd canonts8000 m-ts8000-clean "Clean all"
cmd canonts8000 m-ts8000-nozzle "PrintSelfTestPage"
cmd canone200 m-e200-clean "Clean all"
ls -l "$OUT"/*.bin
