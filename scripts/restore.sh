#!/usr/bin/env bash
# Put the TV's packages back to how they were in a checkpoint.
# Usage: ./restore.sh <checkpoint-dir> [tv-ip:port]
set -uo pipefail

CP="${1:?usage: ./restore.sh <checkpoint-dir> [tv-ip:port]}"
TV="${2:-${CALMOS_TV:-192.168.0.29:5555}}"

adb connect "$TV" >/dev/null
A="adb -s $TV"

clean() { sed 's/^package://' | tr -d '\r' | sort; }
now_installed=$($A shell pm list packages | clean)
now_disabled=$($A shell pm list packages -d | clean)

# 1. Bring back anything that was installed at checkpoint but has since been uninstalled
for p in $(cat "$CP/installed.txt"); do
  grep -qx "$p" <<<"$now_installed" && continue
  echo "reinstall  $p"
  if ! $A shell cmd package install-existing "$p" | grep -q "installed for user"; then
    if [ -d "$CP/apks/$p" ]; then
      $A install-multiple -r "$CP"/apks/"$p"/*.apk
    else
      echo "  !! couldn't restore $p (no saved APK) - reinstall from Play Store"
    fi
  fi
done

# 2. Re-enable anything that was enabled at checkpoint but is disabled now
for p in $(cat "$CP/enabled.txt"); do
  grep -qx "$p" <<<"$now_disabled" || continue
  echo "enable     $p"
  $A shell pm enable "$p" >/dev/null
done

# 3. Re-disable anything that was disabled at checkpoint but is enabled now
for p in $(cat "$CP/disabled.txt"); do
  grep -qx "$p" <<<"$now_disabled" && continue
  echo "disable    $p"
  $A shell pm disable-user --user 0 "$p" >/dev/null
done

# 4. Go home (if two launchers are enabled, the TV will ask which one)
$A shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
echo "Done. Settings dumps in $CP are for reference only - not auto-restored."
