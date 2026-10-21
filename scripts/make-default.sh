#!/usr/bin/env bash
# Make Home (works.uncommon.home) what the remote's Home button opens, and switch
# off the stock Google TV launcher and the pieces that bring it back.
# Usage: ./make-default.sh [tv-ip:port] [apk]
# Undo:  ./make-default.sh --undo [tv-ip:port]
set -uo pipefail
cd "$(dirname "$0")"

UNDO=0
if [ "${1:-}" = "--undo" ]; then UNDO=1; shift; fi
TV="${1:-${CALMOS_TV:-192.168.0.29:5555}}"
# Newest by version number in the file name (home-1.8.apk beats home-1.7.apk)
APK="${2:-$(ls ../dist/home-*.apk 2>/dev/null | sort -V | tail -1)}"
A="adb -s $TV"
HOME_ACT="works.uncommon.home/.HomeActivity"

# Catch incomplete release packages before installing or changing the TV.
if [ "$UNDO" = 0 ] && [ -n "$APK" ] && [ -f "$APK" ]; then
  bash ./verify-apk.sh "$APK" || exit 1
fi

# The stock launcher, its fallback that re-enables it, Vestel's launcher
# customiser, and the recommendations service that feeds its rows.
STOCK=(
  com.google.android.tvlauncher
  com.google.android.tungsten.setupwraith
  com.google.android.leanbacklauncher.partnercustomizer
  com.google.android.tvrecommendations
)

adb connect "$TV" >/dev/null
if ! $A get-state >/dev/null 2>&1; then
  echo "Can't reach the TV at $TV (is it on, and did you accept the debugging prompt?)"; exit 1
fi

if [ $UNDO = 1 ]; then
  for p in "${STOCK[@]}"; do
    echo "restore  $p"
    $A shell cmd package install-existing "$p" >/dev/null 2>&1   # if uninstall-bloat.sh removed it
    $A shell pm enable "$p" >/dev/null
  done
  $A shell cmd package set-home-activity com.google.android.tvlauncher/.MainActivity >/dev/null 2>&1
  $A shell input keyevent KEYCODE_HOME
  echo "Stock launcher restored."
  exit 0
fi

# 1. Make sure the newest Home is installed
installed_version() {
  $A shell dumpsys package works.uncommon.home | tr -d '\r' | grep -m1 -o 'versionName=[^ ]*' | cut -d= -f2
}
if [ -n "$APK" ] && [ -f "$APK" ]; then
  echo "Installing $(basename "$APK") (TV has: $(installed_version || echo none))"
  OUT=$($A install -r "$APK" 2>&1 | tail -1)
  echo "  $OUT"
  case "$OUT" in
    Success*) ;;
    *INSTALL_FAILED_VERSION_DOWNGRADE*)
      echo "The TV has a newer build than $(basename "$APK"). Use a newer APK, or uninstall first:"
      echo "  adb -s $TV uninstall works.uncommon.home   (clears pinned apps, names and theme)"
      exit 1;;
    *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
      echo "The TV's copy was signed with a different key. Uninstall it first:"
      echo "  adb -s $TV uninstall works.uncommon.home   (clears pinned apps, names and theme)"
      exit 1;;
    *) echo "Install failed; stopping before touching the stock launcher."; exit 1;;
  esac
fi
if ! $A shell pm path works.uncommon.home | grep -q package:; then
  echo "Home isn't installed; stopping before touching the stock launcher."; exit 1
fi

# 2. What still needs doing? (A disabled app stays disabled across reboots and
#    updates, so after the first run this is normally nothing.)
DISABLED=$($A shell pm list packages -d | tr -d '\r' | sed 's/^package://')
INSTALLED=$($A shell pm list packages | tr -d '\r' | sed 's/^package://')
CURRENT=$($A shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | tail -1)
TODO=()
for p in "${STOCK[@]}"; do
  if grep -qx "$p" <<<"$DISABLED"; then echo "already off  $p"
  elif ! grep -qx "$p" <<<"$INSTALLED"; then echo "removed      $p"
  else TODO+=("$p"); fi
done

if [ ${#TODO[@]} -eq 0 ] && [[ "$CURRENT" == works.uncommon.home/* ]]; then
  echo "Stock launcher is still off and Home is the default: nothing to change."
else
  # 3. Checkpoint (only when something is about to change), then switch over
  echo "Saving checkpoint (takes a minute)..."; ./checkpoint.sh "$TV" >/dev/null 2>&1 && echo "Checkpoint saved."
  $A shell cmd package set-home-activity "$HOME_ACT" >/dev/null 2>&1
  for p in "${TODO[@]}"; do echo "disable      $p"; $A shell pm disable-user --user 0 "$p" >/dev/null; done
  $A shell cmd package set-home-activity "$HOME_ACT" >/dev/null 2>&1
fi

# 3b. The screensaver (Ambient Mode slideshows) must stay on, or the TV just sleeps.
SAVER=$($A shell settings get secure screensaver_components | tr -d '\r' | cut -d, -f1)
SAVER_PKG=${SAVER%%/*}
if [ -n "$SAVER_PKG" ] && [ "$SAVER_PKG" != null ] && grep -qx "$SAVER_PKG" <<<"$DISABLED"; then
  echo "re-enable    $SAVER_PKG (the screensaver; without it the TV sleeps instead of showing slideshows)"
  $A shell pm enable "$SAVER_PKG" >/dev/null
fi

# 4. Press Home and check what answers
$A shell input keyevent KEYCODE_HOME
sleep 1
NOW=$($A shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | tail -1)
echo
echo "Home button now opens: $NOW"
case "$NOW" in
  works.uncommon.home/*) echo "Done.";;
  *) echo "Not ours yet: on the TV, press Home and pick 'Home' -> 'Always' if it asks.";;
esac
