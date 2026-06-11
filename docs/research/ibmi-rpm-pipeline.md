<!-- Research notes generated during initial development (June 2026). Facts were verified against the cited sources at that time; re-verify versions before relying on them. -->

# Research: How mapepire-server builds & ships for IBM i (QOpenSys)

## Key finding up front

**The public `Mapepire-IBMi/mapepire-server` repo does NOT build an RPM.** It builds a fat jar + zip on `ubuntu-latest`, attaches them to a GitHub Release, and deploys to a test IBM i over SSH using IBM's `@ibm/ibmi-ci` npm tool. The `yum install mapepire-server` RPM (documented as "Option 1: RPM (recommended)" in their sysadmin guide) is built by IBM's **internal** RPM build farm for the IBM i OSS repo — there is no public `.spec` anywhere mentioning mapepire (verified via GitHub code search: 0 hits for `mapepire extension:spec`).

**However**, the same author (Jesse Gorzinski / ThePrez, IBM i OSS lead) publishes the complete RPM-for-QOpenSys pipeline in **`ThePrez/ServiceCommander-IBMi`** (the `sc` tool that mapepire's package integrates with). That repo has the public `.spec`, and GitHub Actions that `rsync` source to an IBM i box and run `/QOpenSys/pkgs/bin/rpmbuild` over SSH. That is the pattern to mirror for a Java Maven RPM.

---

## 1. GitHub Actions workflows

### mapepire-server: `.github/workflows/build_release.yaml` (release, manual trigger)

```yaml
name: Release

on:
  workflow_dispatch:

jobs:
  build:

    runs-on: ubuntu-latest

    environment: OSSBUILD

    steps:
    - uses: actions/checkout@v3
      name: Check out

    - name: Override DNS for Sonatype repo
      run: |
        mkdir -p ~/.m2/
        touch ~/.m2/settings.xml 
        echo " <settings> <mirrors> <mirror> <id>centralhttps</id> <mirrorOf>central</mirrorOf> <name>Maven central https</name> <url>https://repo1.maven.org/maven2/</url> </mirror> </mirrors></settings>" >> ~/.m2/settings.xml 

    - name: Get Maven project version
      run: |
        echo "project_version=$(mvn -q -Dexec.executable="echo" -Dexec.args='${project.version}' --non-recursive org.codehaus.mojo:exec-maven-plugin:3.1.0:exec  --file pom.xml)" >> $GITHUB_ENV
        cat $GITHUB_ENV

    - name: Set up JDK 8
      uses: actions/setup-java@v4
      with:
        java-version: '8'
        distribution: 'temurin'
        cache: maven

    - name: Build with Maven (Java 8)
      run: mvn -B package --file pom.xml

    - name: List target directory
      run: ls -l target

    - name: Create jt400 pseudo-directory
      run: sudo mkdir -p /QIBM/ProdData/OS400/jt400/lib/

    - name: Change ownership of jt400 psudo-directory
      run: sudo chown $USER /QIBM/ProdData/OS400/jt400/lib/

    - name: Fetch jt400.jar
      run: sudo curl https://repo1.maven.org/maven2/net/sf/jt400/jt400/10.7/jt400-10.7.jar -o /QIBM/ProdData/OS400/jt400/lib/jt400.jar

    - name: Build with Maven
      run: mvn -B package --file pom.xml

    - name: Create staging directory
      run: |
        mkdir -p staging/opt/mapepire/lib/mapepire/
        mkdir -p staging/opt/mapepire/bin/

    - name: Populate staging directory
      run: |
        mv scripts/mapepire-start.sh staging/opt/mapepire/bin/mapepire
        mv target/mapepire-server-${{ env.project_version }}-jar-with-dependencies.jar staging/opt/mapepire/lib/mapepire/mapepire-server.jar
        mv LICENSE staging/opt/mapepire/lib/mapepire/LICENSE
        mv service-commander-def.yaml staging/opt/mapepire/lib/mapepire/mapepire.yaml
        mv conf/iprules.conf staging/opt/mapepire/iprules.conf
        mv conf/iprules-single.conf staging/opt/mapepire/iprules-single.conf

    - name: Create distribution .zip and move JAR file
      run: |
        pushd staging/opt/mapepire
        zip -r ../../../mapepire-server-${{ env.project_version }}.zip bin lib iprules.conf iprules-single.conf
        mv lib/mapepire/mapepire-server.jar ../../../mapepire-server.jar
        popd

    - name: Create the tag and release
      uses: softprops/action-gh-release@v1
      with:
        tag_name: v${{ env.project_version }}
        name: v${{ env.project_version }}
        files: |
          mapepire-server-${{ env.project_version }}.zip
          mapepire-server.jar

    - name: Install NPM Dependencies
      run: npm i -g @ibm/ibmi-ci

    - name: Deploy Server to IBM i
      run: | 
        ici \
          --rcwd "/home/${{ secrets.IBMI_USER }}" \
          --cmd "mkdir -p /opt/download/release" \
          --rcwd "/opt/download/release" \
          --cmd "rm -f mapepire-server-${{ env.project_version }}.zip" \
          --cmd "wget -O mapepire-server-${{ env.project_version }}.zip https://github.com/Mapepire-IBMi/mapepire-server/releases/latest/download/mapepire-server-${{ env.project_version }}.zip" \
          --cmd "mkdir -p /opt/mapepire/release" \
          --rcwd "/opt/mapepire/release" \
          --cmd "rm -fr bin lib" \
          --cmd "jar xvf /opt/download/release/mapepire-server-${{ env.project_version }}.zip" \
          --cmd "chmod +x bin/mapepire" \
          --cmd "chown -R qsys ." \
          --cmd "rm -fr /QOpenSys/etc/sc/services/mapepire.yaml" \
          --cmd "ln -sf /opt/mapepire/release/lib/mapepire/mapepire.yaml /QOpenSys/etc/sc/services/mapepire.yaml" \
          --cmd "mkdir -p /QOpenSys/etc/mapepire" \
          --cmd "mv -n /opt/mapepire/release/iprules.conf /QOpenSys/etc/mapepire/iprules.conf" \
          --cmd "mv -n /opt/mapepire/release/iprules-single.conf /QOpenSys/etc/mapepire/iprules-single.conf" \
          --cmd "sc -v check mapepire" \
          --cmd "sc -v stop mapepire" \
          --cmd "sc -v start mapepire" \
      env:
        IBMI_HOST: ${{ secrets.IBMI_HOST }}
        IBMI_USER: ${{ secrets.IBMI_USER }}
        IBMI_PASSWORD: ${{ secrets.IBMI_PASSWORD }}
        IBMI_SSH_PORT: ${{ secrets.IBMI_SSH_PORT }}
```

### mapepire-server: `.github/workflows/zip_dist_ci.yaml` (CI on push/PR to main)

Same shape as release: builds with JDK 8 + jt400 pseudo-dir, stages zip, uploads as an Actions artifact, then (push-to-main only, gated by `if: ${{ github.base_ref == '' }}`) deploys a **dev** instance to IBM i with `ici --push "."` (pushes the zip over SSH rather than wget), renames the sc unit to `mapepired` on port 8075 via `sed -i 's/name: .*/name: Mapepire Development Server/; s/check_alive: .*/check_alive: mapepired,8075/' service-commander-def.yaml`, symlinks to `/QOpenSys/etc/sc/services/mapepired.yaml`, and bounces it with `sc -v stop/start mapepired`. (Third workflow `codeql.yml` is just CodeQL scanning — not release-related.)

### ServiceCommander-IBMi: `.github/workflows/build_release.yml` — **the actual public RPM pipeline**

```yaml
name: Build new release
on:
  push:
    tags:
    - '*' # Push events to matching *, i.e. v1.0, v20.15.10

env:
  ssh_command: ssh ${{ secrets.IBMI_BUILD_USRPRF }}@${{ secrets.IBMI_BUILD_SYS }}
  scp_dist_command: scp -r ${{ secrets.IBMI_BUILD_USRPRF }}@${{ secrets.IBMI_BUILD_SYS }}:/home/${{ secrets.IBMI_BUILD_USRPRF }}/rpmbuild/RPMS/ppc64/ .
  remote_build_dir: /home/${{ secrets.IBMI_BUILD_USRPRF }}/build/${{ github.sha }}
  rsync_command: rsync -a --exclude='.*' --exclude='runners' --rsync-path=/QOpenSys/pkgs/bin/rsync ./ ${{ secrets.IBMI_BUILD_USRPRF }}@${{ secrets.IBMI_BUILD_SYS }}:/home/${{ secrets.IBMI_BUILD_USRPRF }}/build/${{ github.sha }}/

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
    - uses: actions/checkout@v2
    - name: Install private key
      run: |
        mkdir -p ~/.ssh
        chmod 0755 ~
        chmod 0700 ~/.ssh
        echo  "${{ secrets.IBMI_BUILD_PVTKEY }}" > ~/.ssh/id_rsa
        chmod 0600 ~/.ssh/id_rsa
    - name: Disable strict host key checking
      run: |
        echo "Host *" > ~/.ssh/config
        echo "  StrictHostKeyChecking no" >> ~/.ssh/config
    - name: Create build sandbox
      run: $ssh_command "mkdir -p $remote_build_dir"
    - name: Populate build sandbox
      run: $rsync_command
    - name: Get short SHA ID
      run: |
        echo "short_sha=$(echo ${{ github.sha }} | head -c 7)" >> $GITHUB_ENV
        cat $GITHUB_ENV
    - name: Create the release
      id: create_release
      uses: actions/create-release@v1
      env:
        GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
      with:
        tag_name: ${{ github.ref }}
        release_name: Release v${{ github.ref }}
        draft: false
        prerelease: false
    - name: Perform remote build
      run: $ssh_command "cd $remote_build_dir && rm -fr /home/${{ secrets.IBMI_BUILD_USRPRF }}/rpmbuild && PATH=/QOpenSys/pkgs/bin:/QOpenSys/usr/bin:/usr/bin time /QOpenSys/pkgs/bin/rpmbuild -ba service-commander.spec"
    - name: Retrieve artifact
      run: $scp_dist_command
    - name: Cleanup remote build dir
      if: always()
      run: $ssh_command "rm -fr $remote_build_dir"
    - name: List stuff
      run: find ppc64 -name *.rpm
    - name: Upload .zip file to release
      id: upload-release-asset 
      uses: actions/upload-release-asset@v1
      env:
        GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
      with:
        upload_url: ${{ steps.create_release.outputs.upload_url }}
        asset_path: ./ppc64/service-commander-${{ github.ref_name }}-0.ibmi7.3.ppc64.rpm
        asset_name: service-commander-${{ github.ref_name }}-0.ibmi7.3.ppc64.rpm
        asset_content_type: application/zip
```

### ServiceCommander-IBMi: `.github/workflows/ibmi-ci.yml` (CI RPM build, no publish)

Identical SSH/rsync scaffolding; the build step is the interesting variant — an in-place, no-source-fetch build:

```yaml
    - name: Perform remote build
      run: $ssh_command "cd $remote_build_dir && mkdir -p ./rpmbuild && PATH=/QOpenSys/pkgs/bin:/QOpenSys/usr/bin:/usr/bin time /QOpenSys/pkgs/bin/rpmbuild -bb --build-in-place --define '_topdir $remote_build_dir/rpmbuild' --define '_disable_source_fetch 1' service-commander.spec"
    - name: list RPMs
      run: $ssh_command "find $remote_build_dir -name \*.rpm"
    - name: Retrieve artifact
      run: $scp_dist_command
    - name: Cleanup remote build dir
      if: always()
      run: $ssh_command "rm -fr $remote_build_dir"
```

## 2. The .spec file (`ThePrez/ServiceCommander-IBMi/service-commander.spec`)

Header + scriptlets (changelog trimmed — it's ~150 lines of standard `%changelog` entries):

```spec
%undefine _disable_source_fetch
Name: service-commander
Version: 1.7.1
Release: 0
License: Apache-2.0
Summary: Utility for managing services and applications on IBM i
Url: https://github.com/ThePrez/ServiceCommander-IBMi

Obsoletes: sc

# ca-certificates-mozilla is required for maven to install dependencies,
# otherwise the trust store is empty and maven fails with
# InvalidAlgorithmParameterException: the trustAnchors parameter must be non-empty
BuildRequires: ca-certificates-mozilla
BuildRequires: make-gnu
BuildRequires: maven
BuildRequires: openjdk-11

Requires: bash
Requires: coreutils-gnu
Requires: db2util
Requires: nginx >= 1.23.0
Requires: openjdk-11
Requires: python39-ibm_db

Source0: https://github.com/ThePrez/ServiceCommander-IBMi/archive/refs/tags/v%{version}.tar.gz#/%{name}-%{version}.tar.gz

%description
A utility for unifying the daunting task of managing various services and
applications running on IBM i. ...

%prep
%setup -n ServiceCommander-IBMi-%{version}

%build
%make_build all SC_VERSION=%{version}

%install
%make_install INSTALL_ROOT=%{buildroot} SC_VERSION=%{version}

%post
if [ -e %{_sysconfdir}/sc ]; then
    chown -R qsys %{_sysconfdir}/sc
    chmod 755 %{_sysconfdir}/sc
fi
if [ -e %{_sysconfdir}/sc/services ]; then
    chmod 755 %{_sysconfdir}/sc/services
    /QOpenSys/usr/bin/find  %{_sysconfdir}/sc/services/ -type f -exec chmod 644 {} \;
    /QOpenSys/usr/bin/find  %{_sysconfdir}/sc/services/ -type l -exec chmod 644 {} \;
fi
if [ -e %{_sysconfdir}/sc/conf ]; then
    chmod 755 %{_sysconfdir}/sc/conf
    /QOpenSys/usr/bin/find  %{_sysconfdir}/sc/conf/ -type f -exec chmod 644 {} \;
    /QOpenSys/usr/bin/find  %{_sysconfdir}/sc/conf/ -type f -exec setccsid 819 {} \;
    /QOpenSys/usr/bin/find  %{_sysconfdir}/sc/conf/ -type l -exec chmod 644 {} \;
fi

%files
%defattr(-, qsys, *none)
%{_bindir}/sc*
%{_libdir}/sc
%{_libdir}/sc/native/scbash
%dir %{_sysconfdir}/sc/
%dir %{_sysconfdir}/sc/services/
%config(noreplace) %{_sysconfdir}/sc/conf/*
%config(noreplace) %{_sysconfdir}/sc/services/system/*
%config(noreplace) %{_sysconfdir}/sc/services/oss_common/*
%{_mandir}/man1/sc.1*
...
```

**IBM i specifics decoded:**
- On IBM i, RPM macros are prefixed: `%{_bindir}` = `/QOpenSys/pkgs/bin`, `%{_libdir}` = `/QOpenSys/pkgs/lib`, `%{_sysconfdir}` = `/QOpenSys/etc`, `%{_mandir}` = `/QOpenSys/pkgs/share/man`. The spec never hardcodes `/QOpenSys/pkgs` — the macros do it.
- **Arch**: no `BuildArch: noarch` — because it includes a compiled C shim (`scbash`), it builds as `ppc64`, producing `...-0.ibmi7.3.ppc64.rpm`. A pure-Java jar-only package could be `BuildArch: noarch`; sc isn't, and the workflow filename shows the `ibmi7.3` dist tag the IBM i rpmbuild applies.
- **Java**: handled as ordinary RPM deps — `BuildRequires: maven, openjdk-11, ca-certificates-mozilla` (the cert package note is a hard-won IBM i gotcha for Maven TLS) and `Requires: openjdk-11`. Note mapepire's start script instead uses the JV1 system JDK (`/QOpenSys/QIBM/ProdData/JavaVM/jdk80/64bit/bin/java`), which needs no RPM dependency at all.
- **Ownership**: `%defattr(-, qsys, *none)` — files owned by QSYS, group `*none` (IBM i convention; there's no root).
- `%build`/`%install` just delegate to a `Makefile` (`%make_build all` / `%make_install INSTALL_ROOT=%{buildroot}`). The Makefile runs `JAVA_HOME=/QOpenSys/pkgs/lib/jvm/openjdk-11 /QOpenSys/pkgs/bin/mvn -Djava.net.preferIPv4Stack=true package`, copies `target/sc-*-with-dependencies.jar` to `sc.jar`, then `install -m 555 -o qsys` everything under `${INSTALL_ROOT}/QOpenSys/pkgs/{bin,lib/sc}` and `${INSTALL_ROOT}/QOpenSys/etc/sc/...`, including `setccsid 819` on config files (IBM i CCSID tagging).
- `Source0` is the GitHub tag tarball with `%undefine _disable_source_fetch` at the top, so a release build (`rpmbuild -ba`) **downloads the source from GitHub itself**; CI overrides with `--build-in-place --define '_disable_source_fetch 1'` to build the rsynced working tree.

## 3. HOW the build executes on IBM i

**Not a self-hosted runner.** Both projects run on `runs-on: ubuntu-latest` and reach an IBM i partition over SSH. No `appleboy/ssh-action` — two plainer mechanisms:

- **ServiceCommander (RPM)**: raw `ssh`/`rsync`/`scp` with a private key from secrets (`IBMI_BUILD_PVTKEY`, `IBMI_BUILD_USRPRF`, `IBMI_BUILD_SYS`). Key is written to `~/.ssh/id_rsa`, `StrictHostKeyChecking no`, source rsynced to `/home/<user>/build/<sha>/` (note `--rsync-path=/QOpenSys/pkgs/bin/rsync` — the remote rsync isn't on the default PASE PATH), then:
  `PATH=/QOpenSys/pkgs/bin:/QOpenSys/usr/bin:/usr/bin time /QOpenSys/pkgs/bin/rpmbuild -ba service-commander.spec`
  Output lands in `~/rpmbuild/RPMS/ppc64/`, retrieved with `scp -r`, build dir cleaned with `if: always()`.
- **mapepire (zip deploy)**: `npm i -g @ibm/ibmi-ci`, then the `ici` CLI executes a scripted sequence of remote commands using env-var credentials (`IBMI_HOST`/`IBMI_USER`/`IBMI_PASSWORD`/`IBMI_SSH_PORT` — password auth, not key).
- mapepire's compile itself happens on Ubuntu: it fakes the IBM i filesystem with `sudo mkdir -p /QIBM/ProdData/OS400/jt400/lib/` + curl of jt400 10.7, because the jar's manifest hardcodes `Class-Path: /QIBM/ProdData/OS400/jt400/lib/jt400.jar` (jt400 is `<scope>provided</scope>` in pom.xml — at runtime it uses the OS-supplied JT400, keeping the fat jar smaller and DB driver in sync with the OS).

## 4. How artifacts get published

- **mapepire-server**: `softprops/action-gh-release@v1` creates tag `v<maven project.version>` and attaches `mapepire-server-<ver>.zip` + bare `mapepire-server.jar` as **GitHub Release assets**. Version is single-sourced from `pom.xml` via the exec-maven-plugin echo trick.
- **ServiceCommander**: `actions/create-release@v1` + `actions/upload-release-asset@v1` attach `service-commander-<tag>-0.ibmi7.3.ppc64.rpm` to the **GitHub Release** (triggered by tag push).
- **The yum repo** (`http://ibm.biz/ibmi-rpms`, i.e. IBM's `/QOpenSys/pkgs` repo at public.dhe.ibm.com): publication there is **not in any public workflow** — IBM's OSS team rebuilds/ships these internally. The public docs (`Mapepire-IBMi.github.io .../guides/sysadmin.md`) just say `yum install mapepire-server` is recommended, with the GitHub zip as the manual fallback. So for your skeleton: GitHub Release assets are the realistic publish target; a yum-repo push would be a separate `createrepo`+upload step you'd own yourself.

## 5. Service Commander integration (sc) and /QOpenSys/etc

`service-commander-def.yaml` (shipped in the zip as `lib/mapepire/mapepire.yaml`, symlinked to `/QOpenSys/etc/sc/services/mapepire.yaml` — the global sc service registry):

```yaml
name: Mapepire Server
dir: .
start_cmd: ../../bin/mapepire
check_alive: mapepire,8076
batch_mode: 'true'
sbmjob_opts: 'JOBQ(QUSRNOMAX)'
environment_vars:
- PATH=/QOpenSys/pkgs/bin:/QOpenSys/usr/bin:/usr/ccs/bin:/QOpenSys/usr/bin/X11:/usr/sbin:.:/usr/bin
```

- `check_alive: mapepire,8076` = job name + port liveness probe; `batch_mode`/`sbmjob_opts` submit it as an IBM i batch job in JOBQ QUSRNOMAX.
- Launcher `scripts/mapepire-start.sh` (installed as `bin/mapepire`, and as `/QOpenSys/pkgs/bin/mapepire` in the RPM version):

```sh
#!/QOpenSys/usr/bin/sh
QIBM_JAVA_STDIO_CONVERT=N QIBM_PASE_DESCRIPTOR_STDIO=B QIBM_USE_DESCRIPTOR_STDIO=Y QIBM_MULTI_THREADED=Y exec /QOpenSys/QIBM/ProdData/JavaVM/jdk80/64bit/bin/java -Xmx16g -Xms256M -jar $(/QOpenSys/usr/bin/dirname $0)/../lib/mapepire/mapepire-server.jar $*
```

(The `QIBM_*` env vars are mandatory PASE/Java stdio-descriptor settings on IBM i.)
- Config files `iprules.conf` / `iprules-single.conf` are moved (`mv -n`, never clobber) to `/QOpenSys/etc/mapepire/` — the deploy-time equivalent of `%config(noreplace)`. In sc's RPM, the analogous treatment is the `%config(noreplace) %{_sysconfdir}/sc/...` entries plus a `%post` that fixes `qsys` ownership/644 perms and runs `setccsid 819`.
- Deploy bounces the service with `sc -v check/stop/start mapepire`.

## Blueprint for your skeleton (Java Maven → IBM i RPM)

Combine the two: **mapepire's Maven/jar conventions** + **ServiceCommander's spec/SSH-rpmbuild workflow**:

1. **pom.xml**: `maven-assembly-plugin` with `jar-with-dependencies`; mark IBM-i-provided jars (e.g. jt400) `<scope>provided</scope>` with a manifest `Class-Path` pointing at the OS path; read the version with the exec-plugin echo trick.
2. **Makefile**: `all:` runs `/QOpenSys/pkgs/bin/mvn package` with `JAVA_HOME=/QOpenSys/pkgs/lib/jvm/openjdk-11`; `install:` uses `install -m ... -o qsys -D` into `${INSTALL_ROOT}/QOpenSys/pkgs/bin|lib/<pkg>` and `${INSTALL_ROOT}/QOpenSys/etc/<pkg>`, ships an sc `.yaml` into `${INSTALL_ROOT}/QOpenSys/etc/sc/services/`.
3. **.spec**: `Name/Version/Release: 0`; `Source0:` GitHub tag tarball with `#/%{name}-%{version}.tar.gz` rename + `%undefine _disable_source_fetch`; `BuildRequires: ca-certificates-mozilla, make-gnu, maven, openjdk-11`; `Requires: bash, coreutils-gnu, openjdk-11`; `%prep` = `%setup -n <Repo>-%{version}`; `%build` = `%make_build`; `%install` = `%make_install INSTALL_ROOT=%{buildroot}`; `%files` with `%defattr(-, qsys, *none)`, macro paths, `%config(noreplace)` for `/QOpenSys/etc` content; `BuildArch: noarch` if jar-only.
4. **CI workflow** (push/PR): ubuntu-latest → install `IBMI_BUILD_PVTKEY` → rsync (`--rsync-path=/QOpenSys/pkgs/bin/rsync`) to `~/build/$GITHUB_SHA` → `rpmbuild -bb --build-in-place --define '_topdir ...' --define '_disable_source_fetch 1' pkg.spec` → scp RPMs back → upload-artifact → always-cleanup.
5. **Release workflow** (tag push): same SSH scaffolding → `rpmbuild -ba pkg.spec` (fetches Source0 from the tag) → scp `~/rpmbuild/RPMS/ppc64/` (or `noarch/`) → attach `.rpm` to GitHub Release; optionally an `ici`-style smoke deploy + `sc stop/start`.
6. **Secrets needed**: `IBMI_BUILD_SYS`, `IBMI_BUILD_USRPRF`, `IBMI_BUILD_PVTKEY` (RPM path) and/or `IBMI_HOST`/`IBMI_USER`/`IBMI_PASSWORD`/`IBMI_SSH_PORT` (ibmi-ci path), in a GitHub `environment` (mapepire uses `OSSBUILD`).

Sources: `Mapepire-IBMi/mapepire-server` (`.github/workflows/build_release.yaml`, `zip_dist_ci.yaml`, `pom.xml`, `service-commander-def.yaml`, `scripts/mapepire-start.sh`, `DEVELOPMENT.md`), `Mapepire-IBMi/Mapepire-IBMi.github.io` (`src/content/docs/guides/sysadmin.md`), `ThePrez/ServiceCommander-IBMi` (`service-commander.spec`, `Makefile`, `.github/workflows/build_release.yml`, `ibmi-ci.yml`), all fetched from `main` on 2026-06-10.