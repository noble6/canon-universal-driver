#!/bin/bash
# Offline tests: no printer and no Canon software needed.
# Renders a PDF through CUPS with the freshly built filters for a sample of
# models and validates the IVEC stream structure.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
fail=0

mkdir -p "$T/bin/filter"
ln -s /usr/lib/cups/filter/* "$T/bin/filter/" 2>/dev/null
rm -f "$T/bin/filter/canonfilter"
ln -sf "$ROOT/out/rastertocanonivec" "$ROOT/out/cmdtocanonivec" "$T/bin/filter/"
printf 'ServerBin %s\nDataDir /usr/share/cups\n' "$T/bin" > "$T/cups-files.conf"

printf '%%!PS\n/Helvetica findfont 30 scalefont setfont\n72 700 moveto (IVEC test page 1) show 1 0 0 setrgbcolor 72 400 200 200 rectfill showpage\n72 700 moveto (page 2) show 0 0 1 setrgbcolor 72 300 300 300 rectfill showpage\n' > "$T/t.ps"
gs -q -sDEVICE=pdfwrite -o "$T/t.pdf" "$T/t.ps" || { echo "ghostscript failed"; exit 1; }

validate() {
    python3 -I - "$1" "$2" <<'EOF'
import re, sys
d = open(sys.argv[1], 'rb').read()
want_pages = int(sys.argv[2])
ops, pos, pages, size_err = [], 0, 0, []
for m in re.finditer(rb'<\?xml.*?</cmd>', d, re.S):
    gap = d[pos:m.start()]
    if gap and not gap.startswith(b'RaS2') and not gap.startswith(b'\x1b[K'):
        sys.exit('unexpected bytes between commands')
    x = m.group()
    op = re.search(rb'<ivec:operation>(\w+)', x).group(1).decode()
    ops.append(op)
    pos = m.end()
    if op == 'SendData':
        n = int(re.search(rb'<ivec:datasize>(\d+)', x).group(1))
        blob = d[pos:pos + n]
        if not blob.startswith(b'RaS2') or d[pos + n:pos + n + 5] != b'<?xml':
            size_err.append(n)
        pages += 1
        pos += n
if pos != len(d): sys.exit('trailing data')
if size_err: sys.exit('datasize mismatch %s' % size_err)
if ops[0] != 'StartJob' or ops[-1] != 'EndJob': sys.exit('bad framing %s' % ops)
if 'SetConfiguration' not in ops: sys.exit('no SetConfiguration')
if pages != want_pages: sys.exit('pages %d != %d' % (pages, want_pages))
EOF
}

run() {
    local name=$1 ppd=$2 pages=$3
    shift 3
    if cupsfilter -c "$T/cups-files.conf" -p "$ppd" -m printer/foo -o job-uuid=urn:uuid:test "$@" \
        -e "$T/t.pdf" > "$T/out.bin" 2> "$T/log" && [ -s "$T/out.bin" ] && validate "$T/out.bin" "$pages"; then
        echo "PASS $name $*"
    else
        echo "FAIL $name $*"; grep -m3 ERROR "$T/log"; fail=1
    fi
}

cmdrun() {
    local name=$1 ppd=$2 cmd=$3 expect=$4
    printf '#CUPS-COMMAND\n%s\n' "$cmd" > "$T/cmd"
    if cupsfilter -c "$T/cups-files.conf" -p "$ppd" -m printer/foo -i application/vnd.cups-command \
        -e "$T/cmd" > "$T/out.bin" 2> "$T/log" && grep -q -a -- "$expect" "$T/out.bin"; then
        echo "PASS $name '$cmd'"
    else
        echo "FAIL $name '$cmd'"; grep -m3 ERROR "$T/log"; fail=1
    fi
}

P=$ROOT/out/ppd
n=$(ls "$P"/*.ppd | wc -l)
bad=$(for f in "$P"/*.ppd; do cupstestppd -I filters -W translations -q "$f" || echo "$f"; done)
if [ -z "$bad" ]; then echo "PASS cupstestppd ($n PPDs)"; else echo "FAIL cupstestppd: $bad"; fail=1; fi

g3010=$P/Canon-G3010_series-ivec.ppd
run G3010 "$g3010" 2
run G3010 "$g3010" 2 -o PageSize=4x6.bl -o MediaType=glossygold
run G3010 "$g3010" 2 -o ColorModel=Gray -o PageSize=Letter
run Generic "$P/Canon-IVEC_Generic-ivec.ppd" 2
dup=$(grep -l 'cnivecRotateBack' "$P"/*.ppd | head -1)
run "$(basename "$dup" -ivec.ppd)" "$dup" 2 -o Duplex=DuplexNoTumble
opt=$(grep -l 'cnivecOptimization' "$P"/*.ppd | head -1)
run "$(basename "$opt" -ivec.ppd)" "$opt" 2 -o ColorModel=Gray
run "$(basename "$opt" -ivec.ppd)" "$opt" 2
mapfile -t sample < <(printf '%s\n' "$P"/*.ppd | shuf -n 10)
for f in "${sample[@]}"; do run "$(basename "$f" -ivec.ppd)" "$f" 2; done
qrun() {
    local q=$1 expect=$2
    cupsfilter -c "$T/cups-files.conf" -p "$g3010" -m printer/foo -o cupsPrintQuality="$q" -e "$T/t.pdf" \
        > "$T/q.bin" 2>/dev/null
    if validate "$T/q.bin" 2 && grep -q -a -- "$expect" "$T/q.bin"; then echo "PASS G3010 quality $q"
    else echo "FAIL G3010 quality $q"; fail=1; fi
}
qrun High "<ivec:printquality>best</ivec:printquality>"
qrun Draft "<ivec:printquality>fast_draft</ivec:printquality>"
if cupsfilter -c "$T/cups-files.conf" -p "$g3010" -m printer/foo -e "$T/t.pdf" 2>/dev/null | grep -q -a printquality; then
    echo "FAIL G3010 quality Normal sends printquality"; fail=1
else echo "PASS G3010 quality Normal leaves it to the printer"; fi
cmdrun G3010 "$g3010" "Clean all" "<ivec:operation>Cleaning</ivec:operation>"
cmdrun G3010 "$g3010" "PrintSelfTestPage" "nozzle_check"
cmdrun G3010 "$g3010" "com.canon.Cleaning deep all" "<ivec:type>deep</ivec:type>"
cmdrun G3010 "$g3010" "com.canon.RollerCleaning platen" "<ivec:type>platen</ivec:type>"
bjl=$(grep -l 'cnivecMaintStyle: "bjl"' "$P"/*.ppd | head -1)
[ -n "$bjl" ] && cmdrun "$(basename "$bjl" -ivec.ppd)" "$bjl" "Clean all" "@Cleaning=1ALL"

exit $fail
