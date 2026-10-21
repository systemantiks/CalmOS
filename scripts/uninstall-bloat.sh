#!/usr/bin/env bash
# Remove the TV apps CalmOS doesn't need, including the Android versions of the
# streaming apps (those now open on the Apple TV). Live TV, the Play Store, the
# screensaver, Chromecast, voice search and the TV's settings, compliance and
# maintenance apps all stay.
#
# "Uninstall" here is Android's per-user uninstall: the app is removed for you
# but stays in the firmware, so it can always come back:
#   ./uninstall-bloat.sh --undo      reinstall everything on this list
#   (a factory reset also brings them all back)
#
# Usage: ./uninstall-bloat.sh [tv-ip:port]
set -uo pipefail
cd "$(dirname "$0")"

UNDO=0
if [ "${1:-}" = "--undo" ]; then UNDO=1; shift; fi
TV="${1:-${CALMOS_TV:-192.168.0.29:5555}}"
A="adb -s $TV"

REMOVE=(
  # The stock Google TV launcher and its helpers (Home replaces them)
  com.google.android.tvlauncher
  com.google.android.tungsten.setupwraith
  com.google.android.leanbacklauncher.partnercustomizer
  com.google.android.tvrecommendations

  # Ads, demo mode, telemetry and things nobody asked for
  com.cltv.fast                                # free channels with ads
  com.google.android.videos                    # Google TV / Play Movies
  com.google.android.play.games
  com.google.android.youtube.tvmusic
  com.google.android.apps.nbu.smartconnect.tv  # phone setup helper
  com.google.android.feedback
  com.google.android.syncadapters.calendar
  com.android.printspooler
  com.google.android.marvin.talkback
  fusion.android.tv.demo                       # shop demo mode
  com.vestel.tv.eib                            # energy label viewer
  com.vestel.vestelreporter                    # Vestel telemetry
  android.autoinstalls.config.vestel.cosmos    # silently installs partner apps

  # Streaming apps: these open on the Apple TV instead (Home moves your pins across)
  com.netflix.ninja
  com.disney.disneyplus
  com.amazon.amazonvideo.livingroom
  com.google.android.youtube.tv
  com.apple.atve.androidtv.appletv
  com.stremio.one

  # Freeview Play catch-up shortcuts (live TV and the guide keep working)
  uk.co.freeview.amc_catchup
  uk.co.freeview.amc_horror
  uk.co.freeview.uktv
  uk.co.freeview.bbc_sounds
  uk.co.freeview.explore
  uk.co.freeview.onnow
  uk.co.freeview.bbc
  uk.co.freeview.itv
  uk.co.freeview.ch4_vod
  uk.co.freeview.ch5
  uk.co.freeview.stv
)

adb connect "$TV" >/dev/null
$A get-state >/dev/null 2>&1 || { echo "Can't reach the TV at $TV"; exit 1; }

if [ $UNDO = 1 ]; then
  for p in "${REMOVE[@]}"; do
    out=$($A shell cmd package install-existing "$p" 2>&1 | tr -d '\r')
    echo "restore  $p  $out"
  done
  echo "Done. Disabled ones stay disabled; ./debloat.sh --undo re-enables them."
  exit 0
fi

INSTALLED=$($A shell pm list packages | tr -d '\r' | sed 's/^package://')
TODO=()
for p in "${REMOVE[@]}"; do
  grep -qx "$p" <<<"$INSTALLED" && TODO+=("$p")
done
if [ ${#TODO[@]} -eq 0 ]; then
  echo "Nothing to remove: all already gone."
  exit 0
fi

echo "Saving checkpoint (takes a minute)..."; ./checkpoint.sh "$TV" >/dev/null 2>&1 && echo "Checkpoint saved."
for p in "${TODO[@]}"; do
  out=$($A shell pm uninstall -k --user 0 "$p" 2>&1 | tr -d '\r')
  echo "remove   $p  $out"
done

# The screensaver must survive (Ambient Mode slideshows), and Home must still answer the Home button.
$A shell pm enable com.google.android.backdrop >/dev/null 2>&1
$A shell cmd package set-home-activity works.uncommon.home/.HomeActivity >/dev/null 2>&1
echo
echo "Done. ${#TODO[@]} apps removed. Undo any time with ./uninstall-bloat.sh --undo"
echo "Apps that came from the Play Store rather than the firmware (likely Stremio) can't come back with --undo; reinstall those from the store."
