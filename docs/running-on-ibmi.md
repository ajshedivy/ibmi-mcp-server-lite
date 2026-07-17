# Running on IBM i

The deployment unit is the shaded fat jar (`target/ibmi-mcp-server-lite-<version>.jar`).
This page covers deploying to the IFS, the PASE specifics, the RPM pipeline, and the supported Java 17 runtime on IBM i.

## Current status (verified June 2026 on IBM i 7.4)

| Scenario | Status |
|---|---|
| Server anywhere with Java 17 → SQL on IBM i via Mapepire | ✅ works (see `scripts/smoke-test.py`) |
| Server on IBM i under yum `openjdk-11` | ❌ `UnsupportedClassVersionError` (SDK needs class file 61 / Java 17) |
| Server on IBM i under IBM Technology for Java 17 (5770-JV1 option 20) | ✅ supported — install option 20 and point `JAVA` at its path (below) |

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

## JUnit integration tests (live Mapepire)

A Failsafe profile exercises the Java query path (`SourceManager` → `SqlToolHandler`)
against a live IBM i. This is the Java-level pipeline check; `scripts/smoke-test.py`
remains the full stdio MCP protocol smoke test.

```bash
cp .env.example .env   # fill DB2i_HOST / USER / PASS (and PORT if needed)
./mvnw verify -Pintegration-tests
```

- The profile is explicit opt-in: only `-Pintegration-tests` runs these tests. A plain
  `./mvnw test` / `package` / `verify` never does, even with `DB2i_*` exported
  (Maven does not read `.env` itself; the IT helper reads `.env` at test runtime).
- If credentials are missing, the IT suite is **skipped**, not failed.
- The sample tools YAML reads `DB2i_PORT` (falling back to 8076 when unset), so both IT
  classes honor a non-default Mapepire port.

## TLS hostname verification

As of mapepire-sdk **0.1.3**, `ignore-unauthorized: true` relaxes both certificate-*chain*
validation and TLS hostname (SAN) verification — matching the Node.js SDK's
`rejectUnauthorized` / `ignoreUnauthorized` semantics. With the default
`ignore-unauthorized: false`, hostname verification remains fully enforced.

Practical consequences:

- Self-signed or SAN-mismatched Mapepire certs work when you set
  `ignore-unauthorized: true` (DNS aliases, loopback names not in the cert SAN, etc.).
- Leave `ignore-unauthorized: false` (the default) when the connect host matches a name
  in the Mapepire server certificate
  (`openssl s_client -connect host:8076 | openssl x509 -noout -ext subjectAltName`).

## RPM packaging

`.github/workflows/rpm-ibmi.yml` runs the public IBM i RPM pipeline used by
[ServiceCommander-IBMi](https://github.com/ThePrez/ServiceCommander-IBMi) (the same
ecosystem as [mapepire-server](https://github.com/Mapepire-IBMi/mapepire-server), which
itself ships a fat jar + zip from GitHub Actions and is RPM-packaged by IBM internally):

1. A GitHub-hosted runner `rsync`s the source tree to an IBM i build partition over SSH
   (`--rsync-path=/QOpenSys/pkgs/bin/rsync`).
2. `/QOpenSys/pkgs/bin/rpmbuild -bb --build-in-place packaging/rpm/ibmi-mcp-server-lite.spec`
   runs **on the IBM i**; `%build`/`%install` delegate to the repo `Makefile`.
3. RPMs are `scp`'d back, uploaded as an Actions artifact, and (on `v*` tag builds)
   attached to the matching GitHub Release.

The spec (`packaging/rpm/ibmi-mcp-server-lite.spec`) is `noarch` and installs under
`/QOpenSys/pkgs/{bin,lib}` + `/QOpenSys/etc`. After `yum install`, the launcher is
`/QOpenSys/pkgs/bin/ibmi-mcp-server-lite`, the jar is at
`/QOpenSys/pkgs/lib/ibmi-mcp-server-lite/`, and `tools.yaml` is at
`/QOpenSys/etc/ibmi-mcp-server-lite/` (CCSID 819).

### Enabling the pipeline

The workflow stays inert until the `ENABLE_IBMI_RPM_BUILD` repository variable is set to
`true` and the `IBMI_BUILD_SYS` / `IBMI_BUILD_USRPRF` / `IBMI_BUILD_PVTKEY` secrets exist.
The build partition must have:

- `rpmbuild`, `rsync`, `maven`, `make-gnu`, `ca-certificates-mozilla` (all from IBM's
  yum repo; `ca-certificates-mozilla` is required or Maven's TLS trust store is empty).
- **IBM Technology for Java 17** (Semeru Certified Edition, 5770-JV1 option 20) at
  `/QOpenSys/QIBM/ProdData/JavaVM/jdk17/64bit`. Java 17 is needed both to build
  (`pom.xml` sets `maven.compiler.release=17`) and to run. It is a system OS option, not
  a yum RPM, so the spec declares no `openjdk-17` dependency.

A Service Commander unit (`packaging/ibmi/service-commander-def.yaml`) is shipped and
now functional: it launches the server with `--transport http` (implemented via embedded
Jetty), so `sc start ibmi-mcp-server-lite` runs it as a long-lived daemon on port 3010 —
provided Service Commander (`sc`) is installed (the package does not hard-require it).
The default stdio transport is still spawned per-client and needs no daemon manager. HTTP
serves plain text (no TLS); terminate TLS at a reverse proxy or private-network boundary
if needed.
