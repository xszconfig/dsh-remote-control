#!/usr/bin/env bash
# lint.sh —— dsh-remote-control (App) lint 统一入口
#
# 用法:
#   scripts/lint.sh p0   只查 P0 高风险规则（超大函数/类/超长参数列表），命中即 exit 1
#   scripts/lint.sh      全量 detekt（默认，风格级告警为建议项，不阻塞 commit）
#
# P0 闸门语义：任何 P0 命中 → 非 0 退出码（pre-commit hook 据此拦截 commit）。
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="${1:-all}"

# 构建环境：Android Studio 自带 JDK（未显式设置时兜底）
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
  export JAVA_HOME="/Applications/Android Studio 2026.1.1 Quail 1.app/Contents/jbr/Contents/Home"
fi
# 本机构建直连，禁用一切代理
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dhttp.proxyHost= -Dhttps.proxyHost= -DsocksProxyHost="

cd "$REPO_ROOT"

run_detekt() {
  local task="$1"
  local report="$2"
  local rc=0
  ./gradlew --console=plain ":composeApp:${task}" || rc=$?
  # 逐条问题：detekt 的 txt 报告一行一条；命中时打印，便于直接定位
  local report_file="build/reports/detekt/${report}.txt"
  if [ -f "$report_file" ] && [ -s "$report_file" ]; then
    echo "──── ${task} 报告 ────"
    cat "$report_file"
  fi
  return $rc
}

case "$MODE" in
  p0)
    run_detekt detektP0 detektP0
    ;;
  all|full|"")
    run_detekt detekt detekt
    ;;
  *)
    echo "用法: $0 [p0|all]" >&2
    exit 2
    ;;
esac
