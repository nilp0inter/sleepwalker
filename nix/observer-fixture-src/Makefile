CC ?= cc
CFLAGS ?= -O2 -Wall -Wextra -std=c11
LDFLAGS ?=
LDLIBS ?= -lreadline

all: sleepwalker-readline-fixture

sleepwalker-readline-fixture: readline-fixture.c
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $< $(LDLIBS)

install: all
	mkdir -p $(PREFIX)/bin
	cp sleepwalker-readline-fixture $(PREFIX)/bin/

clean:
	rm -f sleepwalker-readline-fixture

.PHONY: all install clean
