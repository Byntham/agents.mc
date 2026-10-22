#!/bin/bash
# Runs one client game test and records the game's rendered frames to build/<name>-rec.mkv.
# The recording is done inside the game (FrameRecorder), so the window can be covered.
# usage: record-gametest.sh <test name: body|terrain|live|pov> [extra env assignments...]
set -u
ONLY="$1"; shift
cd "$(dirname "$0")/.."
export JAVA_HOME="$HOME/.jdks/jdk-25.0.4.1+1"
export PATH="$JAVA_HOME/bin:$PATH"
export AGENTSMC_ONLY="$ONLY"
for kv in "$@"; do export "$kv"; done
export AGENTSMC_FFMPEG="$LOCALAPPDATA/Microsoft/WinGet/Packages/Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe/ffmpeg-9.0.2-full_build/bin/ffmpeg.exe"
export AGENTSMC_RECORD="$PWD/build/$ONLY-rec.mkv"
LOG="build/$ONLY-test.log"
rm -f "$LOG" "$AGENTSMC_RECORD"

./gradlew runClientGameTest --offline --no-daemon > "$LOG" 2>&1
GEXIT=$?
echo "gradle exit $GEXIT"
grep -E "Agents MC: recording" "$LOG" | cut -c1-200
ls -la "$AGENTSMC_RECORD"
exit $GEXIT
