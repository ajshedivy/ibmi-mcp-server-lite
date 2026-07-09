# Build/install targets consumed by the RPM spec (%make_build / %make_install),
# following the ServiceCommander-IBMi pattern for Java Maven RPMs on IBM i.
#
# JAVA_HOME points at IBM Technology for Java 17 (Semeru Certified Edition,
# 5770-JV1 option 20). Java 17 is required both to build (pom.xml sets
# maven.compiler.release=17) and to run — see docs/running-on-ibmi.md.

VERSION ?= 0.1.0
INSTALL_ROOT ?=
JAVA_HOME ?= /QOpenSys/QIBM/ProdData/JavaVM/jdk17/64bit
MVN ?= mvn

PKG = ibmi-mcp-server-lite
BIN_DIR = $(INSTALL_ROOT)/QOpenSys/pkgs/bin
LIB_DIR = $(INSTALL_ROOT)/QOpenSys/pkgs/lib/$(PKG)
ETC_DIR = $(INSTALL_ROOT)/QOpenSys/etc/$(PKG)
SC_DIR  = $(INSTALL_ROOT)/QOpenSys/etc/sc/services

all:
	JAVA_HOME=$(JAVA_HOME) $(MVN) -B -Djava.net.preferIPv4Stack=true package

install:
	install -m 755 -D packaging/ibmi/$(PKG).sh $(BIN_DIR)/$(PKG)
	install -m 644 -D target/$(PKG)-$(VERSION).jar $(LIB_DIR)/$(PKG).jar
	install -m 644 -D LICENSE $(LIB_DIR)/LICENSE 2>/dev/null || true
	install -m 644 -D tools/sample-tools.yaml $(ETC_DIR)/tools.yaml
	install -m 644 -D packaging/ibmi/service-commander-def.yaml $(SC_DIR)/$(PKG).yaml

.PHONY: all install
