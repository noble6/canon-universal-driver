# Canon IVEC open driver
CC       ?= gcc
CFLAGS   ?= -O2 -g
CFLAGS   += -Wall -Wextra -Wno-format-truncation $(shell pkg-config --cflags cups)
LDLIBS   += $(shell pkg-config --libs cups)
PYTHON   ?= python3

PREFIX      ?= /usr
SERVERBIN   ?= $(shell pkg-config --variable=cups_serverbin cups 2>/dev/null || echo $(PREFIX)/lib/cups)
PPDDIR      ?= $(PREFIX)/share/ppd/canon-ivec
BINDIR      ?= $(PREFIX)/bin
DOCDIR      ?= $(PREFIX)/share/doc/canon-ivec-driver
SYSCONFDIR  ?= /etc
UDEVDIR     ?= $(shell pkg-config --variable=udevdir udev 2>/dev/null || echo $(PREFIX)/lib/udev)

FILTERS = out/rastertocanonivec out/cmdtocanonivec out/canonivec
IVEC    = src/ivec/ivec.c src/ivec/ivec.h
STATUS  = src/ivec/status.c src/ivec/status.h

all: $(FILTERS) out/ppd/.stamp

out/rastertocanonivec: src/ivec/rastertocanonivec.c $(IVEC) $(STATUS)
	@mkdir -p out
	$(CC) $(CFLAGS) -pthread -o $@ src/ivec/rastertocanonivec.c src/ivec/ivec.c src/ivec/status.c $(LDFLAGS) $(LDLIBS)

out/canonivec: src/backend/canonivec.c $(STATUS)
	@mkdir -p out
	$(CC) $(CFLAGS) -pthread -Isrc/ivec $(shell pkg-config --cflags libusb-1.0) -o $@ src/backend/canonivec.c src/ivec/status.c $(LDFLAGS) $(LDLIBS) $(shell pkg-config --libs libusb-1.0)

out/cmdtocanonivec: src/ivec/cmdtocanonivec.c $(IVEC)
	@mkdir -p out
	$(CC) $(CFLAGS) -o $@ src/ivec/cmdtocanonivec.c src/ivec/ivec.c $(LDFLAGS) $(LDLIBS)

out/ppd/.stamp: models/ivec_models.json ppd/generate_ivec_ppd.py
	rm -rf out/ppd
	$(PYTHON) ppd/generate_ivec_ppd.py $< out/ppd
	@touch $@

out/test_status: tests/test_status.c $(STATUS)
	@mkdir -p out
	$(CC) $(CFLAGS) -pthread -Isrc/ivec -o $@ tests/test_status.c src/ivec/status.c $(LDFLAGS) $(LDLIBS)

check: all out/test_status
	out/test_status
	$(PYTHON) -B tests/test_notify.py
	@$(PYTHON) -B tests/test_print_helper.py
	@$(PYTHON) -B scripts/canon-ivec-flip --svg out/flip.svg && grep -q '</svg>' out/flip.svg && echo "PASS flip diagram"
	tests/run_tests.sh

install: all
	install -Dm755 out/rastertocanonivec "$(DESTDIR)$(SERVERBIN)/filter/rastertocanonivec"
	install -Dm755 out/cmdtocanonivec "$(DESTDIR)$(SERVERBIN)/filter/cmdtocanonivec"
	# 0700: cupsd runs root-only backends as root, which USB access needs
	install -Dm700 out/canonivec "$(DESTDIR)$(SERVERBIN)/backend/canonivec"
	install -d "$(DESTDIR)$(PPDDIR)"
	install -m644 out/ppd/*.ppd "$(DESTDIR)$(PPDDIR)/"
	install -Dm755 scripts/canonctl "$(DESTDIR)$(BINDIR)/canonctl"
	install -Dm755 scripts/canon-ivec-notify "$(DESTDIR)$(BINDIR)/canon-ivec-notify"
	install -Dm755 scripts/canon-ivec-flip "$(DESTDIR)$(BINDIR)/canon-ivec-flip"
	install -Dm755 scripts/canon-ivec-print "$(DESTDIR)$(BINDIR)/canon-ivec-print"
	# right-click "Print" in Dolphin (KDE)
	install -Dm644 data/canon-ivec-print.desktop "$(DESTDIR)$(PREFIX)/share/kio/servicemenus/canon-ivec-print.desktop"
	install -Dm644 data/canon-ivec.tmpfiles "$(DESTDIR)$(PREFIX)/lib/tmpfiles.d/canon-ivec.conf"
	install -Dm644 data/71-canon-ivec.rules "$(DESTDIR)$(UDEVDIR)/rules.d/71-canon-ivec.rules"
	install -Dm644 data/canon-ivec-notify.desktop "$(DESTDIR)$(SYSCONFDIR)/xdg/autostart/canon-ivec-notify.desktop"
	install -Dm644 README.md "$(DESTDIR)$(DOCDIR)/README.md"
	install -Dm644 docs/PROTOCOL_IVEC.md "$(DESTDIR)$(DOCDIR)/PROTOCOL_IVEC.md"

uninstall:
	rm -f "$(DESTDIR)$(SERVERBIN)/filter/rastertocanonivec" "$(DESTDIR)$(SERVERBIN)/filter/cmdtocanonivec"
	rm -f "$(DESTDIR)$(SERVERBIN)/backend/canonivec"
	rm -rf "$(DESTDIR)$(PPDDIR)" "$(DESTDIR)$(DOCDIR)"
	rm -f "$(DESTDIR)$(BINDIR)/canonctl" "$(DESTDIR)$(BINDIR)/canon-ivec-notify" "$(DESTDIR)$(BINDIR)/canon-ivec-flip"
	rm -f "$(DESTDIR)$(BINDIR)/canon-ivec-print" "$(DESTDIR)$(PREFIX)/share/kio/servicemenus/canon-ivec-print.desktop"
	rm -f "$(DESTDIR)$(SYSCONFDIR)/xdg/autostart/canon-ivec-notify.desktop"
	rm -f "$(DESTDIR)$(UDEVDIR)/rules.d/71-canon-ivec.rules"
	rm -f "$(DESTDIR)$(PREFIX)/lib/tmpfiles.d/canon-ivec.conf"

clean:
	rm -rf out

.PHONY: all check install uninstall clean
