#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
export GRADLE_USER_HOME="$ROOT_DIR/.chatgpt/gradle-home"
bash "$ROOT_DIR/gradlew" --no-daemon -p "$ROOT_DIR" clean :app:fatJar -PreleaseVersion="${1:-0.1.0}"
