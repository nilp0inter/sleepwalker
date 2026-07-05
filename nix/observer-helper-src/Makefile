CC ?= cc
CFLAGS ?= -O2 -Wall -Wextra -std=c11
LDFLAGS ?=

all: sleepwalker-hid-observer sleepwalker-text-sink

sleepwalker-hid-observer: observer-helper.c
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $<

sleepwalker-text-sink: text-sink.c
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $<

install: all
	mkdir -p $(PREFIX)/bin
	cp sleepwalker-hid-observer $(PREFIX)/bin/
	cp sleepwalker-text-sink $(PREFIX)/bin/

clean:
	rm -f sleepwalker-hid-observer sleepwalker-text-sink

.PHONY: all install clean