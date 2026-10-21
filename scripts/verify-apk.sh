#!/usr/bin/env bash
# Read-only release check: reject APKs missing bundled logos or theme sprites.
# Usage: bash scripts/verify-apk.sh dist/home-<version>.apk
set -euo pipefail

if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
  echo "Usage: $0 <existing-apk>" >&2
  exit 1
fi

CONTENTS=$(unzip -Z1 "$1")

# The expanded 2.9 story also needs walking, jumping and sleeping poses.
if unzip -p "$1" classes.dex | strings | grep 'themes/tofu-actions.png' >/dev/null; then
  for pet in moose-actions tofu-actions; do
    if ! grep -Fxq "assets/themes/${pet}.png" <<< "$CONTENTS"; then
      echo "Refusing $(basename "$1"): missing the $pet theme sprite." >&2
      exit 1
    fi
  done
fi

for logo in playstation xbox apple; do
  if ! grep -Fxq "res/drawable/logo_${logo}.xml" <<< "$CONTENTS"; then
    echo "Refusing $(basename "$1"): missing the $logo logo. Rebuild with launcher/build.sh." >&2
    exit 1
  fi
done

# Releases before 2.8 predate the pet themes and remain valid rollback builds.
if unzip -p "$1" classes.dex | strings | grep 'HomePetSprites' >/dev/null; then
  for pet in moose bengal; do
    if ! grep -Fxq "assets/themes/${pet}.png" <<< "$CONTENTS"; then
      echo "Refusing $(basename "$1"): missing the $pet theme sprite." >&2
      exit 1
    fi
  done
fi
