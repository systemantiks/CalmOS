#!/usr/bin/env bash
# Pure Java: no TV or Android runtime needed.
set -euo pipefail
cd "$(dirname "$0")/.."
THEME_TEST_DIR=$(mktemp -d)
trap 'rm -rf "$THEME_TEST_DIR"' EXIT
"${JAVAC:-javac}" -d "$THEME_TEST_DIR" \
  launcher/src/works/uncommon/home/PetChase.java \
  launcher/tests/works/uncommon/home/PetChaseTest.java
"${JAVA:-java}" -cp "$THEME_TEST_DIR" works.uncommon.home.PetChaseTest
