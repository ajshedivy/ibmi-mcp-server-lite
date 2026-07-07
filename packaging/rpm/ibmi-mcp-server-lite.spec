# .spec for the IBM i OSS environment (/QOpenSys/pkgs), modeled on
# ThePrez/ServiceCommander-IBMi's service-commander.spec (the public reference
# for Java/Maven RPMs on IBM i).
#
# IBM i specifics:
#   - RPM macros resolve under PASE: %{_bindir}=/QOpenSys/pkgs/bin,
#     %{_libdir}=/QOpenSys/pkgs/lib, %{_sysconfdir}=/QOpenSys/etc.
#   - Files are owned by qsys with group *none (there is no root on IBM i).
#   - BuildArch is noarch: this package ships only a jar + scripts.
#
# Java 17 runtime: this package uses IBM Technology for Java 17 (Semeru Certified
# Edition, 5770-JV1 option 20) at /QOpenSys/QIBM/ProdData/JavaVM/jdk17/64bit. That is
# a system OS option, not a yum RPM, so there is no openjdk-17 dependency to declare;
# the launcher (packaging/ibmi/ibmi-mcp-server-lite.sh) and Makefile hardcode the path.
# The build also runs under Java 17 (pom.xml sets maven.compiler.release=17), so the
# build partition must have option 20 installed. See docs/running-on-ibmi.md.
%undefine _disable_source_fetch
Name: ibmi-mcp-server-lite
Version: 0.1.0
Release: 1
License: Apache-2.0
Summary: Minimal MCP server for IBM i (YAML SQL tools over Mapepire)
Url: https://github.com/ajshedivy/ibmi-mcp-server-lite
BuildArch: noarch

# ca-certificates-mozilla is required for maven to download dependencies on IBM i
# (without it the trust store is empty and maven fails).
BuildRequires: ca-certificates-mozilla
BuildRequires: make-gnu
BuildRequires: maven

Requires: bash
Requires: coreutils-gnu

Source0: https://github.com/ajshedivy/ibmi-mcp-server-lite/archive/refs/tags/v%{version}.tar.gz#/%{name}-%{version}.tar.gz

%description
A minimal MCP (Model Context Protocol) server for IBM i written in Java.
Tools are defined declaratively in YAML (compatible with the IBM i MCP Server
tool schema) and execute SQL against Db2 for i through the Mapepire daemon.

%prep
%setup -n %{name}-%{version}

%build
%make_build all VERSION=%{version}

%install
%make_install INSTALL_ROOT=%{buildroot} VERSION=%{version}

%post
# Keep /QOpenSys/etc config readable and tagged with an ASCII CCSID.
if [ -e %{_sysconfdir}/%{name} ]; then
    chmod 755 %{_sysconfdir}/%{name}
    /QOpenSys/usr/bin/find %{_sysconfdir}/%{name}/ -type f -exec chmod 644 {} \;
    /QOpenSys/usr/bin/find %{_sysconfdir}/%{name}/ -type f -exec setccsid 819 {} \;
fi

%files
%defattr(-, qsys, *none)
%{_bindir}/%{name}
%{_libdir}/%{name}
%config(noreplace) %{_sysconfdir}/%{name}/*
# Service Commander unit (start_cmd uses --transport http, which is now supported).
# Functional when Service Commander (sc) is installed; harmless otherwise. The Makefile
# always installs it, so it exists in the buildroot and rpmbuild succeeds.
%config(noreplace) %{_sysconfdir}/sc/services/%{name}.yaml

%changelog
* Tue Jul 07 2026 ibmi-mcp-server-lite <xavier.stevermer@ibm.com> - 0.1.0-1
- First working RPM: builds and runs on IBM Technology for Java 17 (5770-JV1
  option 20); Source0 points at the ibmi-mcp-server-lite repo; no openjdk-17 dep.

* Wed Jun 10 2026 IBM i MCP project <noreply@ibm.com> - 0.1.0-0
- Initial skeleton package (not yet buildable; see TODOs above)
