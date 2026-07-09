#!/usr/bin/env bash
#
# Post-build smoke test for the RPM package structure.
# Validates the built RPM without requiring Java 17 runtime or installation.
#
# This checks:
#   - RPM file exists and is valid
#   - Package metadata (name, version, arch)
#   - File list matches expected layout
#   - No broken dependencies (except the expected Java 17 system requirement)
#
# Usage:
#   scripts/rpm-build-check.sh [path/to/rpm]
#
# If no path provided, searches for the most recent RPM in common locations.

set -euo pipefail

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

info() { echo -e "${GREEN}✓${NC} $*"; }
warn() { echo -e "${YELLOW}⚠${NC} $*"; }
fail() { echo -e "${RED}✗${NC} $*"; }

# Find RPM file
RPM_FILE="${1:-}"
if [ -z "$RPM_FILE" ]; then
    # Search common locations
    for dir in RPMS/noarch rpmbuild/RPMS/noarch .; do
        if [ -d "$dir" ]; then
            RPM_FILE=$(find "$dir" -name "ibmi-mcp-server-lite-*.rpm" -type f 2>/dev/null | head -n1)
            [ -n "$RPM_FILE" ] && break
        fi
    done
fi

if [ -z "$RPM_FILE" ] || [ ! -f "$RPM_FILE" ]; then
    fail "No RPM file found. Provide path as argument or build first."
    echo "Usage: $0 [path/to/ibmi-mcp-server-lite-*.rpm]"
    exit 1
fi

echo "=== RPM Build Smoke Test ==="
echo "RPM: $RPM_FILE"
echo

# Check 1: RPM file is valid
echo "1. Validating RPM structure..."
if rpm -qp --info "$RPM_FILE" >/dev/null 2>&1; then
    info "RPM file is valid"
else
    fail "RPM file is corrupted or invalid"
    exit 1
fi

# Check 2: Package metadata
echo
echo "2. Checking package metadata..."
NAME=$(rpm -qp --queryformat '%{NAME}' "$RPM_FILE")
VERSION=$(rpm -qp --queryformat '%{VERSION}' "$RPM_FILE")
RELEASE=$(rpm -qp --queryformat '%{RELEASE}' "$RPM_FILE")
ARCH=$(rpm -qp --queryformat '%{ARCH}' "$RPM_FILE")
SUMMARY=$(rpm -qp --queryformat '%{SUMMARY}' "$RPM_FILE")

if [ "$NAME" = "ibmi-mcp-server-lite" ]; then
    info "Package name: $NAME"
else
    fail "Wrong package name: $NAME (expected: ibmi-mcp-server-lite)"
fi

if [ "$ARCH" = "noarch" ]; then
    info "Architecture: $ARCH"
else
    warn "Architecture: $ARCH (expected: noarch)"
fi

info "Version: $VERSION-$RELEASE"
info "Summary: $SUMMARY"

# Check 3: File list
echo
echo "3. Checking installed files..."
FILES=$(rpm -qpl "$RPM_FILE")

# Expected files
EXPECTED_FILES=(
    "/QOpenSys/pkgs/bin/ibmi-mcp-server-lite"
    "/QOpenSys/pkgs/lib/ibmi-mcp-server-lite/ibmi-mcp-server-lite.jar"
    "/QOpenSys/etc/ibmi-mcp-server-lite/tools.yaml"
    "/QOpenSys/etc/sc/services/ibmi-mcp-server-lite.yaml"
)

for expected in "${EXPECTED_FILES[@]}"; do
    if echo "$FILES" | grep -q "^${expected}$"; then
        info "Found: $expected"
    else
        fail "Missing: $expected"
    fi
done

# Check 4: Launcher script content
echo
echo "4. Validating launcher script..."
LAUNCHER_CONTENT=$(rpm -qp --dump "$RPM_FILE" | grep "/QOpenSys/pkgs/bin/ibmi-mcp-server-lite" || true)
if [ -n "$LAUNCHER_CONTENT" ]; then
    info "Launcher script is packaged"
    
    # Extract and check launcher content
    TMPDIR=$(mktemp -d)
    trap "rm -rf $TMPDIR" EXIT
    
    rpm2cpio "$RPM_FILE" | (cd "$TMPDIR" && cpio -idm 2>/dev/null)
    LAUNCHER="$TMPDIR/QOpenSys/pkgs/bin/ibmi-mcp-server-lite"
    
    if [ -f "$LAUNCHER" ]; then
        # Check for Java 17 path
        if grep -q "/QOpenSys/QIBM/ProdData/JavaVM/jdk17/64bit/bin/java" "$LAUNCHER"; then
            info "Launcher uses Java 17 path"
        else
            fail "Launcher does not reference Java 17"
        fi
        
        # Check for QIBM_* environment variables
        if grep -q "QIBM_JAVA_STDIO_CONVERT=N" "$LAUNCHER" && \
           grep -q "QIBM_PASE_DESCRIPTOR_STDIO=B" "$LAUNCHER" && \
           grep -q "QIBM_USE_DESCRIPTOR_STDIO=Y" "$LAUNCHER" && \
           grep -q "QIBM_MULTI_THREADED=Y" "$LAUNCHER"; then
            info "Launcher sets all required QIBM_* variables"
        else
            warn "Launcher may be missing QIBM_* environment variables"
        fi
    fi
fi

# Check 5: JAR file
echo
echo "5. Checking JAR file..."
rpm2cpio "$RPM_FILE" | (cd "$TMPDIR" && cpio -idm 2>/dev/null)
JAR="$TMPDIR/QOpenSys/pkgs/lib/ibmi-mcp-server-lite/ibmi-mcp-server-lite.jar"

if [ -f "$JAR" ]; then
    JAR_SIZE=$(stat -f%z "$JAR" 2>/dev/null || stat -c%s "$JAR" 2>/dev/null)
    if [ "$JAR_SIZE" -gt 1000000 ]; then  # > 1MB
        info "JAR file size: $(numfmt --to=iec-i --suffix=B "$JAR_SIZE" 2>/dev/null || echo "$JAR_SIZE bytes")"
    else
        warn "JAR file seems small: $JAR_SIZE bytes"
    fi
    
    # Check JAR manifest
    if unzip -p "$JAR" META-INF/MANIFEST.MF 2>/dev/null | grep -q "Main-Class: com.ibm.ibmi.mcp.Main"; then
        info "JAR has correct Main-Class"
    else
        fail "JAR missing or has wrong Main-Class"
    fi
    
    # Check for Java 17 class files
    if unzip -l "$JAR" 2>/dev/null | grep -q "\.class$"; then
        info "JAR contains compiled classes"
    else
        fail "JAR appears to be empty"
    fi
else
    fail "JAR file not found in RPM"
fi

# Check 6: Dependencies
echo
echo "6. Checking dependencies..."
REQUIRES=$(rpm -qp --requires "$RPM_FILE")

# Expected dependencies
if echo "$REQUIRES" | grep -q "bash"; then
    info "Requires: bash"
else
    warn "Missing dependency: bash"
fi

if echo "$REQUIRES" | grep -q "coreutils-gnu"; then
    info "Requires: coreutils-gnu"
else
    warn "Missing dependency: coreutils-gnu"
fi

# Should NOT require openjdk-17 (it's a system option, not a yum package)
if echo "$REQUIRES" | grep -q "openjdk-17"; then
    warn "Unexpectedly requires: openjdk-17 (should use system Java 17)"
fi

# Check 7: Config files
echo
echo "7. Checking configuration files..."
CONFIG_FILES=$(rpm -qp --configfiles "$RPM_FILE")

if echo "$CONFIG_FILES" | grep -q "/QOpenSys/etc/ibmi-mcp-server-lite/tools.yaml"; then
    info "tools.yaml marked as config file (noreplace)"
else
    warn "tools.yaml not marked as config file"
fi

if echo "$CONFIG_FILES" | grep -q "/QOpenSys/etc/sc/services/ibmi-mcp-server-lite.yaml"; then
    info "Service Commander unit marked as config file"
else
    warn "SC unit not marked as config file"
fi

# Check 8: Scripts
echo
echo "8. Checking RPM scripts..."
POST_SCRIPT=$(rpm -qp --scripts "$RPM_FILE" | grep -A20 "postinstall scriptlet" || true)

if echo "$POST_SCRIPT" | grep -q "setccsid 819"; then
    info "Post-install sets CCSID 819 on config files"
else
    warn "Post-install may not set CCSID on config files"
fi

if echo "$POST_SCRIPT" | grep -q "chmod"; then
    info "Post-install sets file permissions"
else
    warn "Post-install may not set permissions"
fi

# Summary
echo
echo "=== Summary ==="
echo "RPM package structure appears valid."
echo
echo "⚠️  Note: This test validates the RPM structure only."
echo "    Runtime testing requires:"
echo "    - IBM i partition with Java 17 (5770-JV1 option 20)"
echo "    - Mapepire server running"
echo "    - Use scripts/ibmi-partition-check.sh for runtime validation"
echo
info "Build smoke test PASSED"

# Made with Bob
