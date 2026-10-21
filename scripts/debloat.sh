#!/usr/bin/env bash
# Switch off the TV's bloat. Everything here is reversible: disabled apps come
# back with --undo, ./restore.sh, or a factory reset. Nothing is deleted.
# Usage: ./debloat.sh [tv-ip:port]          disable the SAFE list
#        ./debloat.sh --extra [tv-ip:port]  also the EXTRA list (see below)
#        ./debloat.sh --undo [tv-ip:port]   re-enable everything on both lists
set -uo pipefail
cd "$(dirname "$0")"

MODE=safe
case "${1:-}" in --extra) MODE=extra; shift;; --undo) MODE=undo; shift;; esac
TV="${1:-${CALMOS_TV:-192.168.0.29:5555}}"
A="adb -s $TV"

# Stock-launcher clutter, ads, telemetry, demo mode and apps nobody asked for.
SAFE=(
  com.vestel.vestelreporter                    # Vestel telemetry
  fusion.android.tv.demo                       # Shop demo mode
  com.vestel.tv.eib                            # Energy label viewer
  android.autoinstalls.config.vestel.cosmos    # Silently installs partner apps
  com.cltv.fast                                # Free ad-supported channels (launcher row)
  com.google.android.videos                    # Google TV / Play Movies (launcher "Shop" row)
  com.google.android.play.games                # Play Games
  com.google.android.youtube.tvmusic           # YouTube Music
  com.google.android.feedback                  # Google feedback
  com.google.android.syncadapters.calendar     # Calendar sync
  com.google.android.apps.nbu.smartconnect.tv  # Phone setup helper
  com.android.printspooler                     # Printing
)

# Never disable: the TV's screensaver (Ambient Mode slideshows). Without it the
# TV goes straight to sleep. Re-enabled here in case an older version of this
# script switched it off.
KEEP=(
  com.google.android.backdrop
)

# Freeview Play catch-up apps, plus features you may still want. Only with --extra.
EXTRA=(
  uk.co.freeview.amc_catchup uk.co.freeview.amc_horror uk.co.freeview.uktv
  uk.co.freeview.bbc_sounds uk.co.freeview.explore uk.co.freeview.onnow
  uk.co.freeview.bbc uk.co.freeview.itv uk.co.freeview.ch4_vod
  uk.co.freeview.ch5 uk.co.freeview.stv
  com.google.android.apps.mediashell           # Chromecast built-in
  com.google.android.katniss                   # Voice search / Assistant (mic button)
)

adb connect "$TV" >/dev/null
$A get-state >/dev/null 2>&1 || { echo "Can't reach the TV at $TV"; exit 1; }

if [ $MODE = undo ]; then
  for p in "${SAFE[@]}" "${EXTRA[@]}"; do echo "enable   $p"; $A shell pm enable "$p" >/dev/null 2>&1; done
  exit 0
fi

for p in "${KEEP[@]}"; do $A shell pm enable "$p" >/dev/null 2>&1; done
echo "Saving checkpoint (takes a minute)..."; ./checkpoint.sh "$TV" >/dev/null 2>&1 && echo "Checkpoint saved."
LIST=("${SAFE[@]}")
[ $MODE = extra ] && LIST+=("${EXTRA[@]}")
for p in "${LIST[@]}"; do
  if $A shell pm list packages | tr -d '\r' | grep -qx "package:$p"; then
    echo "disable  $p"; $A shell pm disable-user --user 0 "$p" >/dev/null
  fi
done
echo "Done. Undo with ./debloat.sh --undo"
