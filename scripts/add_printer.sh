#!/bin/bash
# Register a detected Canon printer as a CUPS queue using lpadmin.

if [ "$EUID" -ne 0 ]; then
    echo "Please run as root." >&2
    exit 1
fi

echo "Detecting Canon printers over USB..."
PRINTER=$(lsusb | grep "04a9:" | head -n 1)

if [ -z "$PRINTER" ]; then
    echo "No Canon printers found."
    exit 1
fi

VID=$(echo "$PRINTER" | grep -oP '(?<=ID )04a9:[0-9a-fA-F]{4}')
MODEL=$(echo "$PRINTER" | cut -c34- | sed 's/[[:space:]]*$//')
echo "Found Canon printer: $MODEL ($VID)"

QUEUE_NAME="Canon_$(echo "$MODEL" | tr ' ' '_')"
lpadmin -p "$QUEUE_NAME" -m "Canon/Canon-${MODEL// /_}.ppd" -v "usb://$VID" -E && \
    echo "Printer added as '$QUEUE_NAME'."
