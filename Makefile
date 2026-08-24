CC=gcc
CFLAGS=-g -Wall -Isrc/protocol -Isrc/backend
LIBS=-lcups -lusb-1.0

all: canonfilter generate_ppds

canonfilter: src/filter/canon_filter.c src/protocol/g_series.c
	$(CC) $(CFLAGS) -o $@ $^ $(LIBS)

generate_ppds:
	python3 ppd/generate_ppd.py models/models.json ppd/templates ppd/out

install: all
	install -d $(DESTDIR)/usr/lib/cups/filter
	install -m 755 canonfilter $(DESTDIR)/usr/lib/cups/filter/canonfilter
	install -d $(DESTDIR)/usr/share/cups/model/Canon
	install -m 644 ppd/out/*.ppd $(DESTDIR)/usr/share/cups/model/Canon/
	install -d $(DESTDIR)/etc/udev/rules.d
	install -m 644 udev/60-canon-universal.rules $(DESTDIR)/etc/udev/rules.d/

clean:
	rm -f canonfilter
	rm -rf ppd/out
