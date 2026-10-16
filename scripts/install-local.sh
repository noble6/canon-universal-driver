#!/bin/bash
# Install or update the IVEC open driver on this machine and set up the
# connected printer.  Safe to run again after pulling updates.
#   sudo scripts/install-local.sh
set -e
[ "$(id -u)" -eq 0 ] || { echo "run with sudo" >&2; exit 1; }
cd "$(dirname "$0")/.."
QUEUE=${QUEUE:-Canon_G3010}

echo "==> Removing leftovers of the old placeholder driver (if any)"
for q in Canon-G3010 Canon-G3010-IPP; do
    lpstat -p "$q" >/dev/null 2>&1 && lpadmin -x "$q" && echo "    removed queue $q"
done
rm -fv /usr/lib/cups/filter/canonfilter /etc/udev/rules.d/60-canon-universal.rules
for f in /usr/share/cups/model/Canon/Canon-PIXMA_*.ppd /usr/share/cups/model/Canon/Canon-imageCLASS_*.ppd; do
    [ -f "$f" ] && grep -q canonfilter "$f" && rm -v "$f"
done
rmdir /usr/share/cups/model/Canon 2>/dev/null || true
rm -rf build

echo "==> Desktop integration and scanning packages"
pacman -S --needed system-config-printer sane sane-airscan simple-scan

echo "==> Installing the driver"
make install
systemd-tmpfiles --create /usr/lib/tmpfiles.d/canon-ivec.conf
systemctl enable --now cups
systemctl restart cups
for _ in 1 2 3 4 5; do lpstat -r >/dev/null 2>&1 && break; sleep 1; done

if lpstat -p "$QUEUE" >/dev/null 2>&1; then
    # refresh the queue's copy of the PPD so new options show up
    mdl=$(lpinfo -l -v 2>/dev/null | sed -n 's/.*device-id = .*MDL:\([^;]*\);.*/\1/p' | head -1)
    ppd=$(grep -l -F "MDL:${mdl:-G3010 series};" /usr/share/ppd/canon-ivec/*.ppd | head -1)
    [ -n "$ppd" ] && lpadmin -p "$QUEUE" -P "$ppd" && echo "==> Updated queue $QUEUE ($(basename "$ppd"))"
    # move USB queues to the canonivec backend (status, working cancel)
    uri=$(lpinfo -v 2>/dev/null | awk '$2 ~ /^canonivec:/ {print $2}' | head -1)
    if [ -n "$uri" ] && lpstat -v "$QUEUE" | grep -q ' usb://'; then
        lpadmin -p "$QUEUE" -v "$uri" && echo "==> $QUEUE now uses $uri"
    fi
else
    echo "==> Creating the print queue"
    canonctl setup --name "$QUEUE"
fi
# drop usb:// duplicates of canonivec:// queues (system-config-printer's
# udev helper creates them; 71-canon-ivec.rules stops that from now on)
udevadm control --reload-rules 2>/dev/null || true
for sn in $(lpstat -v 2>/dev/null | sed -n 's/.* canonivec:.*serial=\([^&]*\).*/\1/p'); do
    lpstat -v 2>/dev/null | awk -v s="serial=$sn" '$4 ~ /^usb:\/\/Canon\// && index($4, s) {sub(":", "", $3); print $3}' |
        while read -r dup; do lpadmin -x "$dup" && echo "==> Removed duplicate queue $dup"; done
done
lpadmin -d "$QUEUE"
cupsenable "$QUEUE"; cupsaccept "$QUEUE"
lpstat -p -d

if [ -n "${SUDO_USER:-}" ]; then
    uid=$(id -u "$SUDO_USER")
    pkill -u "$SUDO_USER" -f 'python3 .*bin/canon-ivec-notify' || true
    sudo -u "$SUDO_USER" DBUS_SESSION_BUS_ADDRESS="unix:path=/run/user/$uid/bus" DISPLAY="${DISPLAY:-:0}" \
        WAYLAND_DISPLAY="${WAYLAND_DISPLAY:-wayland-0}" XDG_RUNTIME_DIR="/run/user/$uid" \
        setsid canon-ivec-notify >/dev/null 2>&1 < /dev/null &
    echo "==> Started printer notifications for $SUDO_USER (they also start at every login)"
fi
echo
echo "Done. Test:  lp /usr/share/cups/data/testprint"
