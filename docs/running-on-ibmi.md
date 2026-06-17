# Running on IBM i

The deployment unit is the shaded fat jar (`target/ibmi-mcp-server-lite-<version>.jar`).
This page covers deploying to the IFS, the PASE specifics, the RPM skeleton, and the one
open blocker: **a Java 17 runtime on IBM i**.

## Current status (verified June 2026 on IBM i 7.4)

| Scenario | Status |
|---|---|
| Server anywhere with Java 17 → SQL on IBM i via Mapepire | ✅ works (see `scripts/smoke-test.py`) |
| Server on IBM i under yum `openjdk-11` | ❌ `UnsupportedClassVersionError` (SDK needs class file 61 / Java 17) |
| Server on IBM i under a Java 17 runtime | ⏳ blocked on runtime availability — details below |

The MCP Java SDK requires **Java 17**. On IBM i today:

- `yum` (IBM's `/QOpenSys/pkgs` repo) ships **openjdk-11 only** (verified against the
  repo metadata at `public.dhe.ibm.com/software/ibmi/products/pase/rpms/repo`).
- The classic JV1 JDKs on a stock system are Java 8 and 11
  (`/QOpenSys/QIBM/ProdData/JavaVM/jdk80|jdk11`).
- **Java 17 IS supported on IBM i** — as *IBM Technology for Java 17* (IBM Semeru
  Runtime Certified Edition, OpenJ9), shipped as **5770-JV1 option 20** for IBM i 7.4+
  (GA May 2023; install from ESS/OS media, PTF group SF99665 ≥18 on 7.4 / SF99955 ≥5 on
  7.5 / SF99965 ≥1 on 7.6). `JAVA_HOME=/QOpenSys/QIBM/ProdData/JavaVM/jdk17/64bit`.
  **Java 21** followed in September 2025 as **option 21** on IBM i 7.5+
  (`.../jdk21/64bit`). Install one of these and point `JAVA` in the launcher at it.
  The test partition used during development had neither option installed — only
  options for Java 8 and 11.

### What we tried (so you don't have to)

AIX builds of Java 17 do **not** run in PASE as-is. Both fail at load with
`Dependent module libperfstat.a(shr_64.o) could not be loaded` — PASE does not provide
the AIX `libperfstat` library:

- IBM Semeru 17 (OpenJ9) AIX ppc64: imports `perfstat_cpu`, `perfstat_cpu_total`,
  `perfstat_memory_total`, `perfstat_partition_total`.
- Eclipse Temurin 17 (Hotspot) AIX ppc64: imports only `perfstat_cpu_total` and
  `perfstat_partition_total`.

A stub `libperfstat.a(shr_64.o)` (compiled in PASE with gcc, placed on `LIBPATH`) might
satisfy the loader — Hotspot has fallback paths when perfstat calls fail — but this is an
**unsupported hack**; don't do it on a shared system. The supported answer is Semeru
Certified Edition 17, or wait for a Java 17+ RPM in IBM's yum repo.

## Manual deployment

```bash
# from your workstation
scp target/ibmi-mcp-server-lite-0.1.0.jar tools/sample-tools.yaml user@myibmi:ibmi-mcp-server-lite/

# on the IBM i (ssh user@myibmi), once a Java 17 runtime exists:
cd ~/ibmi-mcp-server-lite
QIBM_JAVA_STDIO_CONVERT=N QIBM_PASE_DESCRIPTOR_STDIO=B \
QIBM_USE_DESCRIPTOR_STDIO=Y QIBM_MULTI_THREADED=Y \
$JAVA17_HOME/bin/java -jar ibmi-mcp-server-lite-0.1.0.jar --tools sample-tools.yaml
```

The `QIBM_*` variables are the standard PASE/Java stdio-descriptor settings (the same set
the Mapepire launcher uses). They matter here because **MCP stdio framing runs over
stdin/stdout** — without them, EBCDIC/ASCII conversion can corrupt protocol frames.
`packaging/ibmi/ibmi-mcp-server-lite.sh` wraps all of this.

When the server runs on the same partition as Mapepire, point the source at the loopback
name that matches the Mapepire certificate (see below), or regenerate the Mapepire
certificate to include the name you use.

## TLS hostname verification

mapepire-java differs from the Node SDK: `ignore-unauthorized: true` disables
certificate-*chain* validation but **not hostname verification** (the underlying
Java-WebSocket library enables HTTPS endpoint identification unconditionally). The
connection fails with `No subject alternative DNS name matching <host> found` when the
host you connect to isn't in the certificate's SAN.

Practical consequences:

- Connect using a hostname that appears in the Mapepire server certificate
  (`openssl s_client -connect host:8076 | openssl x509 -noout -ext subjectAltName`).
- DNS aliases of the same system fail even with `ignore-unauthorized: true`
  (e.g. a cert for `common1.frankeni.com` rejects `common1.iinthecloud.com`).
- Fixing this properly means overriding `onSetSSLParameters` in mapepire-java's
  WebSocket client when `rejectUnauthorized=false` — a good upstream contribution
  (see the roadmap).

## RPM packaging skeleton

`.github/workflows/rpm-ibmi.yml` skeletons the public IBM i RPM pipeline used by
[ServiceCommander-IBMi](https://github.com/ThePrez/ServiceCommander-IBMi) (the same
ecosystem as [mapepire-server](https://github.com/Mapepire-IBMi/mapepire-server), which
itself ships a fat jar + zip from GitHub Actions and is RPM-packaged by IBM internally):

1. A GitHub-hosted runner `rsync`s the source tree to an IBM i build partition over SSH
   (`--rsync-path=/QOpenSys/pkgs/bin/rsync`).
2. `/QOpenSys/pkgs/bin/rpmbuild -bb --build-in-place packaging/rpm/ibmi-mcp-server-lite.spec`
   runs **on the IBM i**; `%build`/`%install` delegate to the repo `Makefile`.
3. RPMs are `scp`'d back and uploaded as artifacts / release assets.

The workflow is inert until the `ENABLE_IBMI_RPM_BUILD` repository variable is set and
the `IBMI_BUILD_SYS` / `IBMI_BUILD_USRPRF` / `IBMI_BUILD_PVTKEY` secrets exist. The spec
(`packaging/rpm/ibmi-mcp-server-lite.spec`) is `noarch`, installs under
`/QOpenSys/pkgs/{bin,lib}` + `/QOpenSys/etc`, and carries TODO markers (most
importantly the Java 17 `Requires`). A Service Commander unit
(`packaging/ibmi/service-commander-def.yaml`) is included for when the HTTP transport
exists — stdio servers are spawned per-client and don't need a daemon manager.
