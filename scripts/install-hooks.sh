#!/usr/bin/env bash
# install-hooks.sh —— 把仓库内 hooks/ 安装到 .git/hooks/
#
# 用法: scripts/install-hooks.sh
# 安装后每次 commit 前自动跑 P0 lint 闸门（超大函数/类/超长参数列表等）。
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$REPO_ROOT/hooks/pre-commit"
DST="$REPO_ROOT/.git/hooks/pre-commit"

if [ ! -f "$SRC" ]; then
  echo "错误：找不到 $SRC" >&2
  exit 1
fi

cp "$SRC" "$DST"
chmod +x "$DST"
echo "已安装 pre-commit hook → .git/hooks/pre-commit"
echo "（重新 clone 仓库后需重跑本脚本，因为 .git/hooks 不在版本控制内）"
