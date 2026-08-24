# SPDX-License-Identifier: GPL-3.0-or-later
#!/bin/bash
echo "Testing CUPS filter integration logic..."
echo "Mock raster data" | ./canonfilter 1 user title 1 "" 
if [ $? -eq 0 ]; then
    echo "Filter test passed."
else
    echo "Filter test failed."
fi
