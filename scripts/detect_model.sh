#!/bin/bash
# List Canon USB devices and point at the matching model entry.

PRINTER=$(lsusb | grep "04a9:")
if [ -z "$PRINTER" ]; then
    echo "No Canon printers detected."
    exit 1
fi

echo "Detected Canon devices:"
echo "$PRINTER"
echo ""
echo "Cross-reference the product ID against models.json to find your model."
