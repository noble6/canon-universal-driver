# Troubleshooting

Start with:

```bash
canonctl doctor      # installation and common problems
canonctl detect      # what CUPS sees on USB and the network
```

## The printer is not detected

- `lsusb | grep 04a9` should list the printer. `lsusb` sometimes shows the wrong model name (for example the G3010, `04a9:183b`, is listed as "MG3110"). That is harmless; the driver uses the model ID the printer reports itself.
- Make sure CUPS is running: `sudo systemctl enable --now cups`.
- If `ipp-usb` is installed, it can claim the printer's USB interface. Try `sudo systemctl stop ipp-usb`.

## Cancel does not stop the printer

The queue must use the `canonivec://` backend. Check with `lpstat -v`. If it shows `usb://Canon/...`, switch it:

```bash
sudo lpadmin -p <queue> -v "$(lpinfo -v | awk '/canonivec:/{print $2; exit}')"
```

(`sudo scripts/install-local.sh` does this automatically.)

## Jobs stay "processing" or the queue shows "Paused"

- CUPS stops a queue after a failed job when its error policy is `stop-printer`. Re-enable it with `cupsenable <queue>`, or use the notification's **Resume** button.
- The printer may be waiting for you: load paper, close covers, then press its Black or Color button. It does not continue by itself.
- To see what happened: `sudo cupsctl --debug-logging`, print again, then `sudo tail -100 /var/log/cups/error_log`.

## Margins, borderless, photo paper

- Borderless sizes are listed as "(borderless)" (`4x6.bl`, `A4.bl`, ...). Canon only allows borderless printing on photo media types, so choose a photo paper type too.
- An unlisted printer using the *IVEC Generic* profile gets conservative margins. Please open a "new model" issue.

## The printer shows up twice

`system-config-printer` auto-creates a `usb://` queue for every USB printer it sees, next to the `canonivec://` queue from `canonctl setup`. `sudo scripts/install-local.sh` removes the duplicate and installs `71-canon-ivec.rules`, which stops it coming back. To remove one by hand: `sudo lpadmin -x <duplicate-queue>`.

## 2-sided printing: backs upside down or in the wrong order

On printers without a duplex unit the driver prints the fronts, then asks you to put the stack back with the **blank side facing you and the TOP edge going in first**, without reordering it. If the back sides still come out upside down on your model, set **Manual 2-sided back side** to **Turned** (print dialog options, or `lpoptions -p <queue> -o CNManualBack=Turned`) and please report your model in an issue.

## KDE says "A printer support package ... system-config-printer"

Install it: `sudo pacman -S system-config-printer`. KDE's printer settings use it for extra features.

## Chrome shows only a few options

Chrome's own print preview only shows paper size, colour, 2-sided, copies and layout. For media type and the rest, use **Print using system dialog** (Ctrl+Shift+P).

## No paper-out notification

1. `canonctl doctor` should report "printer notifications running". If not, start `canon-ivec-notify &` or log out and back in.
2. Alerts work for USB queues using the `canonivec://` backend (see above). Network (driverless IPP) queues get them from the printer itself through CUPS.
3. Reproduce the problem, then run `sudo canonctl report` and attach the output to an issue. It shows the raw status messages the printer sent.

## Scanning

- `scanimage -L` should list the printer. USB uses SANE's `pixma` backend; Wi-Fi uses `sane-airscan` (eSCL).
- If USB scanning only works as root, unplug and reconnect the printer after installing `sane` so its udev rules apply.

## Removing the driver

```bash
sudo lpadmin -x <queue>
sudo make uninstall
```
