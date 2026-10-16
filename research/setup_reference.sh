#!/bin/bash
# Build a sandboxed copy of Canon's official cnijfilter2 pipeline (no root,
# nothing installed) so its output can be used as ground truth for the
# open IVEC filter.  Everything lands in $W.
set -e
W=${W:-$HOME/.cache/canon-ivec-re}
HERE=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$W"

if [ ! -f "$W/cnijfilter2/pkg/cnijfilter2/usr/lib/cups/filter/rastertocanonij" ]; then
    rm -rf "$W/cnijfilter2"
    git clone -q https://aur.archlinux.org/cnijfilter2.git "$W/cnijfilter2"
    (cd "$W/cnijfilter2" && makepkg -f --noconfirm >/dev/null)
fi

P=$W/cnijfilter2/pkg/cnijfilter2
SRC=$(ls -d "$W"/cnijfilter2/src/cnijfilter2-source-*/ | head -1); SRC=${SRC%/}
T=$W/ref
mkdir -p "$T/lib" "$T/bin/filter" "$T/bin/backend"

# pacman's ldconfig would normally create the soname links
for f in "$P"/usr/lib/*.so.*; do
    ln -sf "$f" "$T/lib/$(readelf -d "$f" | sed -n 's/.*soname: \[\(.*\)\]/\1/p')"
done

# rastertocanonij hardcodes /usr/bin for its helpers; rebuild a copy pointing at the package dir
if [ ! -x "$T/r2c/src/rastertocanonij" ]; then
    rm -rf "$T/r2c"
    cp -r "$SRC/rastertocanonij" "$T/r2c"
    (cd "$T/r2c" && { make distclean >/dev/null 2>&1 || true; } &&
        ./autogen.sh --prefix=/usr --enable-progpath="$P/usr/bin" >/dev/null 2>&1 && make >/dev/null 2>&1)
fi

ln -sf /usr/lib/cups/filter/* "$T/bin/filter/"
rm -f "$T/bin/filter/canonfilter" "$T/bin/filter/rastertocanonij"
for f in "$P"/usr/lib/cups/filter/cmdtocanonij*; do
    rm -f "$T/bin/filter/${f##*/}"
    printf '#!/bin/sh\nexport LD_LIBRARY_PATH=%s\nexec %s "$@"\n' "$T/lib" "$f" > "$T/bin/filter/${f##*/}"
    chmod +x "$T/bin/filter/${f##*/}"
done
# cupsfilter scrubs LD_LIBRARY_PATH, so wrap the filter
printf '#!/bin/sh\nexport LD_LIBRARY_PATH=%s\nexec %s "$@"\n' "$T/lib" "$T/r2c/src/rastertocanonij" \
    > "$T/bin/filter/rastertocanonij"
chmod +x "$T/bin/filter/rastertocanonij"
printf 'ServerBin %s\nDataDir /usr/share/cups\n' "$T/bin" > "$T/cups-files.conf"

INC=$SRC/tocanonij/include
gcc -O1 -o "$T/capdump" "$HERE/capdump.c" -ldl
gcc -O1 -I"$INC" -I"$INC/cncl" -o "$T/mapprobe" "$HERE/mapprobe.c" -ldl

echo "PPDS=$P/usr/share/cups/model"
echo "REF=$T"
