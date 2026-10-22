#!/bin/bash
# Trims a recording to start just before the test's first step and writes an mp4.
# usage: finish.sh <name> <out.mp4> [seconds before the first step to keep]
set -u
NAME="$1"; OUT="$2"; LEAD="${3:-2}"
cd "$(dirname "$0")/.."
FF="$LOCALAPPDATA/Microsoft/WinGet/Packages/Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe/ffmpeg-9.0.2-full_build/bin"
REC="build/$NAME-rec.mkv"
LOG="build/$NAME-test.log"
START=$(cat "build/$NAME-rec-start.txt")
# The first line the test logs after the world is up, as a wall-clock time today.
FIRST=$(grep -m1 -E "\(agentsmc-test\) (spawn|chat):" "$LOG" | sed -E 's/^\[([0-9:]+)\].*/\1/')
FIRST_EPOCH=$(date -d "today $FIRST" +%s)
SKIP=$((FIRST_EPOCH - START - LEAD))
if [ "$SKIP" -lt 0 ]; then SKIP=0; fi
echo "$NAME: recording started $(date -d @"$START" +%T), first step at $FIRST, skipping ${SKIP}s"
"$FF/ffmpeg.exe" -v error -y -ss "$SKIP" -i "$REC" -c:v libx264 -preset medium -crf 20 -pix_fmt yuv420p -movflags +faststart "$OUT"
"$FF/ffprobe.exe" -v error -show_entries format=duration -of default=nw=1:nk=1 "$OUT" | sed "s|^|$OUT: |"
