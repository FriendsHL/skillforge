#!/usr/bin/env bash
set -euo pipefail

# 用法:
#   ./docs/p6-rollout-toggle.sh phase-a
#   ./docs/p6-rollout-toggle.sh phase-b
#   ./docs/p6-rollout-toggle.sh rollback-read
#   ./docs/p6-rollout-toggle.sh rollback-all

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
CFG_FILE="${ROOT_DIR}/skillforge-server/src/main/resources/application.yml"

if [[ ! -f "${CFG_FILE}" ]]; then
  echo "配置文件不存在: ${CFG_FILE}"
  exit 1
fi

PHASE="${1:-}"
if [[ -z "${PHASE}" ]]; then
  echo "缺少阶段参数"
  exit 1
fi

set_cfg() {
  local key="$1"
  local value="$2"
  # macOS sed
  sed -i '' -E "s@^([[:space:]]*${key}:[[:space:]]*).*\$@\\1${value}@g" "${CFG_FILE}"
}

case "${PHASE}" in
  phase-a)
    set_cfg "row-read-enabled" "false"
    set_cfg "row-write-enabled" "true"
    set_cfg "dual-read-verify-enabled" "false"
    set_cfg "backfill-enabled" "true"
    ;;
  phase-b)
    set_cfg "row-read-enabled" "true"
    set_cfg "row-write-enabled" "true"
    set_cfg "dual-read-verify-enabled" "true"
    set_cfg "backfill-enabled" "false"
    ;;
  phase-c)
    set_cfg "row-read-enabled" "true"
    set_cfg "row-write-enabled" "true"
    set_cfg "dual-read-verify-enabled" "false"
    set_cfg "backfill-enabled" "false"
    ;;
  rollback-read)
    set_cfg "row-read-enabled" "false"
    set_cfg "row-write-enabled" "true"
    set_cfg "dual-read-verify-enabled" "false"
    ;;
  rollback-all)
    set_cfg "row-read-enabled" "false"
    set_cfg "row-write-enabled" "false"
    set_cfg "dual-read-verify-enabled" "false"
    set_cfg "backfill-enabled" "false"
    ;;
  *)
    echo "未知阶段: ${PHASE}"
    exit 1
    ;;
esac

echo "已切换到 ${PHASE}"
echo "请重启服务使配置生效"
