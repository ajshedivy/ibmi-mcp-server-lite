# SKELETON .spec for the IBM i OSS environment (/QOpenSys/pkgs), modeled on
# ThePrez/ServiceCommander-IBMi's service-commander.spec (the public reference
# for Java/Maven RPMs on IBM i).
#
# IBM i specifics:
#   - RPM macros resolve under PASE: %{_bindir}=/QOpenSys/pkgs/bin,
#     %{_libdir}=/QOpenSys/pkgs/lib, %{_sysconfdir}=/QOpenSys/etc.
#   - Files are owned by qsys with group *none (there is no root on IBM i).
#   - BuildArch is noarch: this package ships only a jar + scripts.
#
# TODO:
#   - Java 17 runtime: IBM's yum repo currently ships openjdk-11 only, and the
#     MCP Java SDK needs 17+. Options: wait for an openjdk-17/21 RPM, require
#     IBM Semeru Runtime Certified Edition 17 (5770-JV1), or downlevel the
#     server. Until resolved this spec cannot produce a runnable package.
#   - Verify BuildRequires on a real build partition and fill %changelog.
%undefine _disable_source_fetch
Name: ibmi-mcp-server-lite
Version: 0.1.0
Release: 0
License: Apache-2.0
Summary: Skeleton MCP server for IBM i (YAML SQL tools over Mapepire)
Url: https://github.com/IBM/ibmi-mcp-server
BuildArch: noarch

# ca-certificates-mozilla is required for maven to download dependencies on IBM i
# (without it the trust store is empty and maven fails).
BuildRequires: ca-certificates-mozilla
BuildRequires: make-gnu
BuildRequires: maven
# TODO: replace with the Java 17 runtime package once available.
BuildRequires: openjdk-11

Requires: bash
Requires: coreutils-gnu
# TODO: replace with the Java 17 runtime package once available.
Requires: openjdk-11

Source0: https://github.com/IBM/ibmi-mcp-server/archive/refs/tags/v%{version}.tar.gz#/%{name}-%{version}.tar.gz

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
%config(noreplace) %{_sysconfdir}/sc/services/%{name}.yaml

%changelog
* Wed Jun 10 2026 IBM i MCP project <noreply@ibm.com> - 0.1.0-0
- Initial skeleton package (not yet buildable; see TODOs above)
