#!/usr/bin/env bash
#
# DESKTOP-MACOS-PACKAGE build pipeline:
#   mvn package (server fat jar) → dashboard build → jlink trimmed JRE →
#   stage resources → electron-builder dmg (arm64).
#
# Run from the skillforge-desktop module:  npm run dist
set -euo pipefail

DESKTOP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "${DESKTOP_DIR}/.." && pwd)"
STAGING="${DESKTOP_DIR}/staging"

echo "==> repo root: ${REPO_ROOT}"
echo "==> desktop:   ${DESKTOP_DIR}"

# ── Resolve a JDK 17 with jlink ───────────────────────────────────────────────
JDK_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
JLINK="${JDK_HOME}/bin/jlink"
if [[ ! -x "${JLINK}" ]]; then
  echo "ERROR: jlink not found at ${JLINK}. Set JAVA_HOME to a JDK 17." >&2
  exit 1
fi
echo "==> using JDK: ${JDK_HOME}"

# ── 1. Build the server fat jar ───────────────────────────────────────────────
echo "==> [1/6] mvn package skillforge-server"
( cd "${REPO_ROOT}" && mvn -pl skillforge-server -am package -DskipTests -q )

# ── 2. Build the dashboard ────────────────────────────────────────────────────
echo "==> [2/6] npm build skillforge-dashboard"
( cd "${REPO_ROOT}/skillforge-dashboard" && npm run build )

# ── 3. Clean + recreate staging ───────────────────────────────────────────────
echo "==> [3/6] reset staging dir"
rm -rf "${STAGING}"
mkdir -p "${STAGING}/server" "${STAGING}/web" "${STAGING}/system-skills"

# ── 4. jlink trimmed JRE (flat image: staging/jre/bin/java) ───────────────────
echo "==> [4/6] jlink trimmed JRE"
# jdk.charsets + jdk.localedata are REQUIRED for zh-CN users: GB18030/GBK file
# reads throw UnsupportedCharsetException and zh-CN locale formatting fails without
# them — a runtime crash that does NOT trigger the build-time jlink fallback.
JLINK_MODULES="java.base,java.logging,java.sql,java.naming,java.desktop,java.management,java.security.jgss,java.security.sasl,java.instrument,java.net.http,java.xml,java.transaction.xa,java.scripting,java.compiler,java.rmi,java.prefs,jdk.unsupported,jdk.crypto.ec,jdk.zipfs,jdk.management,jdk.charsets,jdk.localedata"
rm -rf "${STAGING}/jre"
"${JLINK}" \
  --module-path "${JDK_HOME}/jmods" \
  --add-modules "${JLINK_MODULES}" \
  --strip-debug \
  --no-header-files \
  --no-man-pages \
  --compress=2 \
  --output "${STAGING}/jre"

# ── 5. Stage fat jar + dashboard dist ─────────────────────────────────────────
echo "==> [5/6] stage server jar + web"
SERVER_JAR="$(find "${REPO_ROOT}/skillforge-server/target" -maxdepth 1 -name 'skillforge-server-*.jar' ! -name '*.original' | head -n1)"
if [[ -z "${SERVER_JAR}" ]]; then
  echo "ERROR: no skillforge-server fat jar found in target/" >&2
  exit 1
fi
echo "    server jar: ${SERVER_JAR}"
cp "${SERVER_JAR}" "${STAGING}/server/skillforge-server.jar"

DASH_DIST="${REPO_ROOT}/skillforge-dashboard/dist"
if [[ ! -f "${DASH_DIST}/index.html" ]]; then
  echo "ERROR: dashboard dist/index.html missing (build failed?)" >&2
  exit 1
fi
cp -R "${DASH_DIST}/." "${STAGING}/web/"

# system-skills (browser/clawhub/github/grill-me/skill-creator/skillhub): required
# by SkillForgeHomeResolver.getSystemSkillsDir(). Bundled here, synced into
# ~/.skillforge/system-skills at launch (main.js).
SYS_SKILLS="${REPO_ROOT}/system-skills"
if [[ ! -d "${SYS_SKILLS}" ]]; then
  echo "ERROR: system-skills dir missing at ${SYS_SKILLS}" >&2
  exit 1
fi
cp -R "${SYS_SKILLS}/." "${STAGING}/system-skills/"

# ── 6. electron-builder dmg ───────────────────────────────────────────────────
echo "==> [6/6] electron-builder --mac dmg --arm64"
( cd "${DESKTOP_DIR}" && npx electron-builder --mac dmg --arm64 )

echo "==> DONE. Artifacts:"
ls -la "${DESKTOP_DIR}/dist/"*.dmg 2>/dev/null || echo "    (no dmg found — check electron-builder output)"
