#!/usr/bin/env bash
# Snapshot the TV's package + settings state over ADB.
# Usage: ./checkpoint.sh [tv-ip:port]
set -euo pipefail

TV="${1:-${CALMOS_TV:-192.168.0.29:5555}}"
OUT="tv-checkpoint-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT/apks"

adb connect "$TV" >/dev/null
A="adb -s $TV"

clean() { sed 's/^package://' | tr -d '\r' | sort; }

echo "Saving device info and package state to $OUT/"
$A shell getprop > "$OUT/getprop.txt"
$A shell pm list packages -f -u | tr -d '\r'      > "$OUT/packages-all-paths.txt"
$A shell pm list packages        | clean          > "$OUT/installed.txt"   # installed for user 0
$A shell pm list packages -e     | clean          > "$OUT/enabled.txt"
$A shell pm list packages -d     | clean          > "$OUT/disabled.txt"
$A shell cmd package resolve-activity --brief \
  -a android.intent.action.MAIN -c android.intent.category.HOME > "$OUT/home.txt"

for ns in system secure global; do
  $A shell settings list "$ns" | tr -d '\r' > "$OUT/settings-$ns.txt"
done

echo "Pulling APKs for apps living in /data/app (Play-installed or updated)..."
for p in $($A shell pm list packages -f | tr -d '\r' | grep '/data/app/' | sed -E 's/.*=//'); do
  mkdir -p "$OUT/apks/$p"
  for path in $($A shell pm path "$p" | clean); do
    $A pull "$path" "$OUT/apks/$p/" >/dev/null
  done
  echo "  $p"
done

echo
echo "Checkpoint saved: $OUT"
echo "$(wc -l < "$OUT/installed.txt" | tr -d ' ') installed, $(wc -l < "$OUT/disabled.txt" | tr -d ' ') disabled, home = $(tail -1 "$OUT/home.txt")"
