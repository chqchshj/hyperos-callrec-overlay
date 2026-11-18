#!/bin/bash
# 一键：打包 → 安装 → 写 LSPosed 配置 → 软重启 zygote
#   用法: ./deploy.sh <ip>:<port>   (无线调试地址，见 README「部署」)
#   注意: 软重启会让手机重启 framework，ADB 会断 60~90 秒
set -e
D=${1:?usage: ./deploy.sh <ip>:<port>}
cd "$(dirname "$0")"
./build.sh

adb -s "$D" install -r build/CallRecAuto.apk | tail -1 || true
NEW=$(adb -s "$D" shell "pm path com.nil.callrec" | sed 's/package://' | tr -d '\r')
if [ -z "$NEW" ]; then
  # MIUI/HyperOS 常见：INSTALL_FAILED_USER_RESTRICTED（锁屏 / USB 安装开关没开）
  echo "normal install failed, retry as root ..."
  adb -s "$D" push build/CallRecAuto.apk /data/local/tmp/CallRecAuto.apk >/dev/null
  adb -s "$D" shell "su -c 'pm install -r -d /data/local/tmp/CallRecAuto.apk'" | tail -1
  NEW=$(adb -s "$D" shell "pm path com.nil.callrec" | sed 's/package://' | tr -d '\r')
fi
[ -n "$NEW" ] || { echo "install failed, abort"; exit 1; }
echo "apk: $NEW"

adb -s "$D" shell "su -c 'cp /data/adb/lspd/config/modules_config.db /sdcard/mc_tmp.db'" >/dev/null
adb -s "$D" pull /sdcard/mc_tmp.db /tmp/mc_tmp.db >/dev/null 2>&1
python3 - "$NEW" <<'PY'
import sqlite3, sys
c = sqlite3.connect('/tmp/mc_tmp.db')
P = 'com.nil.callrec'
c.execute("insert or replace into modules(module_pkg_name, apk_path) values(?,?)", (P, sys.argv[1]))
c.execute("insert or replace into modules_state(module_pkg_name, user_id, enabled, scope_request_blocked) values(?,0,1,0)", (P,))
for app in ('com.android.incallui', 'com.android.soundrecorder', 'com.miui.audiomonitor'):
    c.execute("insert or replace into scope(module_pkg_name, app_pkg_name, user_id) values(?,?,0)", (P, app))
c.commit()
PY
adb -s "$D" push /tmp/mc_tmp.db /data/local/tmp/mc_tmp.db >/dev/null 2>&1
adb -s "$D" shell "su -c 'killall -9 lspd; sleep 1; cp /data/local/tmp/mc_tmp.db /data/adb/lspd/config/modules_config.db; rm -f /data/adb/lspd/config/modules_config.db-wal /data/adb/lspd/config/modules_config.db-shm; cd /data/adb/modules/zygisk_lsposed && ./daemon --force >/dev/null 2>&1 & sleep 2; setprop ctl.restart zygote'" >/dev/null 2>&1

sleep 45
adb connect "$D" >/dev/null 2>&1 || true
sleep 5
adb devices | tail -2
