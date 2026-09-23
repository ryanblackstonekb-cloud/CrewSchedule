#!/usr/bin/env sh
set -e
GRADLE_VERSION=9.6.0
CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper/dists/gradle-$GRADLE_VERSION-bin"
if command -v gradle >/dev/null 2>&1; then exec gradle "$@"; fi
exec "$HOME/.gradle/gradle-bootstrap-$GRADLE_VERSION/bin/gradle" "$@"
