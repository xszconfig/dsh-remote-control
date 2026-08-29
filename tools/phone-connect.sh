#!/usr/bin/env bash
# 真机连接桌面端 bridge 的一键设置。
#
# 桌面端 dsh web 仅监听 127.0.0.1，手机无法局域网直连；USB 连接时依赖
# adb reverse 把手机的 127.0.0.1:<port> 反向转发到电脑。reverse 规则
# 不持久——每次重插 USB / 重启 adb 后都需要重跑本脚本。
#
# 用法：tools/phone-connect.sh [port]    # 默认 3080
set -euo pipefail

PORT="${1:-3080}"

# 找第一台 USB 真机（排除模拟器）
SERIAL="$(adb devices | awk 'NR>1 && $2=="device" && $1 !~ /^emulator-/ {print $1; exit}')"
if [ -z "$SERIAL" ]; then
  echo "✗ 未找到 USB 真机（模拟器已忽略）。请用数据线连接手机并允许 USB 调试。" >&2
  exit 1
fi

adb -s "$SERIAL" reverse "tcp:$PORT" "tcp:$PORT"
echo "✓ 已为 $SERIAL 设置 reverse：手机 127.0.0.1:$PORT → 电脑 :$PORT"
echo "  手机 App 点击设备或扫码即可连接；重插 USB 后需重跑本脚本。"
