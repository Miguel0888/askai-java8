#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
export GRADLE_USER_HOME="$ROOT_DIR/.chatgpt/gradle-home"
if command -v gradle >/dev/null 2>&1; then
  gradle --no-daemon -p "$ROOT_DIR" clean :askai-app:fatJar -PreleaseVersion="${1:-0.1.0}"
else
  echo "Gradle is required. GitHub Actions installs Gradle 7.6.4 automatically." >&2
  exit 1
fi
