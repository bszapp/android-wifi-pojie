DESTDIR		?=
PREFIX		?= /usr
SBINDIR		?= $(PREFIX)/sbin
MANDIR		?= $(PREFIX)/share/man

SRC		= src

export DESTDIR PREFIX

all: clean
	$(MAKE) -C $(SRC)

install: all
	$(MAKE) -C $(SRC) install
	install -D -m 0644 man/mdk4.8 $(DESTDIR)$(MANDIR)/man8/mdk4.8

.PHONY : clean
clean:
	$(MAKE) -C $(SRC) clean

test:
	$(MAKE) -C $(SRC) test

distclean: clean
