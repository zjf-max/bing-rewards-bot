#!/bin/bash
# 真机部署与验证辅助
set -e
cd "$(dirname "$0")"
# 本机 adb 路径写在 local.env 的 ADB=... 里；没配就用 PATH 上的 adb
if [ -f local.env ]; then
    . ./local.env
fi
ADB="${ADB:-adb}"

cmd="$1"

case "$cmd" in
  install)
    "$ADB" install -r -g bingbot.apk
    echo "--- 开启无障碍 ---"
    "$ADB" shell settings put secure enabled_accessibility_services com.a11y.bingbot/.BingA11yService
    "$ADB" shell settings put secure accessibility_enabled 1
    echo "--- 关闭电池优化 ---"
    "$ADB" shell cmd deviceidle whitelist +com.a11y.bingbot || true
    echo "--- 启动界面 ---"
    "$ADB" shell am start -n com.a11y.bingbot/.MainActivity
    ;;

  enable)
    "$ADB" shell settings put secure enabled_accessibility_services com.a11y.bingbot/.BingA11yService
    "$ADB" shell settings put secure accessibility_enabled 1
    "$ADB" shell am start -n com.a11y.bingbot/.MainActivity
    ;;

  run)
    "$ADB" logcat -c
    "$ADB" shell am force-stop com.a11y.bingbot
    "$ADB" shell am start -n com.a11y.bingbot/.MainActivity
    sleep 1
    ;;

  logs)
    "$ADB" logcat -v time -s BingBot:V AndroidRuntime:E | tail -60
    ;;

  state)
    echo "=== 无障碍是否开启 ==="
    "$ADB" shell settings get secure enabled_accessibility_services
    "$ADB" shell settings get secure accessibility_enabled
    echo "=== 前台 ==="
    "$ADB" shell "dumpsys window | grep mCurrentFocus"
    ;;

  *)
    echo "用法: bash test.sh {install|enable|run|logs|state}"
    ;;
esac
