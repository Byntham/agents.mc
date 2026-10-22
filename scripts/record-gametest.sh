#!/bin/bash
# Runs one client game test and records the game window with ffmpeg.
# usage: record.sh <test name: body|live> [extra env assignments...]
set -u
ONLY="$1"; shift
cd "$(dirname "$0")/.."
export JAVA_HOME="$HOME/.jdks/jdk-25.0.4.1+1"
export PATH="$JAVA_HOME/bin:$PATH"
export AGENTSMC_ONLY="$ONLY"
for kv in "$@"; do export "$kv"; done
FFMPEG="$LOCALAPPDATA/Microsoft/WinGet/Packages/Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe/ffmpeg-9.0.2-full_build/bin/ffmpeg.exe"
LOG="build/$ONLY-test.log"
REC="build/$ONLY-rec.mkv"
rm -f "$LOG" "$REC"

./gradlew runClientGameTest --offline --no-daemon > "$LOG" 2>&1 &
GPID=$!

# Start recording once the test says the window has its final size.
for i in $(seq 1 240); do
  if grep -q "recording can start" "$LOG" 2>/dev/null; then break; fi
  if ! kill -0 $GPID 2>/dev/null; then echo "gradle exited before the test started"; exit 1; fi
  sleep 1
done
sleep 1
date +%s > "build/$ONLY-rec-start.txt"
echo "recording started at $(date +%T)"
"$FFMPEG" -y -f gdigrab -framerate 30 -draw_mouse 0 -i title="Minecraft* 26.3" \
  -c:v libx264 -preset veryfast -crf 20 -pix_fmt yuv420p -t 1500 "$REC" > "build/$ONLY-ffmpeg.log" 2>&1 &
FPID=$!

wait $GPID
GEXIT=$?
echo "gradle exit $GEXIT"
sleep 2
powershell -NoProfile -Command "Get-Process ffmpeg -ErrorAction SilentlyContinue | Stop-Process" >/dev/null 2>&1
wait $FPID 2>/dev/null
sleep 1
ls -la "$REC"
exit $GEXIT
