# Adding a New Canon Printer

We store known configurations in `models/models.json`.

1. Plug in your printer.
2. Run `lsusb` and look for Vendor `04a9`.
3. Note the Product ID (e.g., `1823` for G3010).
4. Add it to `models.json`.
5. Run the PPD generator to update the build list.
6. Submit a Pull Request!
