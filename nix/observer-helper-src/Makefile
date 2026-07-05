CC ?= cc
CFLAGS ?= -O2 -Wall -Wextra -std=c11
LDFLAGS ?=

all: sleepwalker-hid-observer

sleepwalker-hid-observer: observer-helper.c
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $<

install: all
	mkdir -p $(PREFIX)/bin
	cp sleepwalker-hid-observer $(PREFIX)/bin/

clean:
	rm -f sleepwalker-hid-observer

.PHONY: all install clean