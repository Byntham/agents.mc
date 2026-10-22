#!/bin/bash
# Re-encodes a recording as a web-friendly mp4.
# usage: finish-recording.sh <name> <out.mp4>
set -u
NAME="$1"; OUT="$2"
cd "$(dirname "$0")/.."
FF="$LOCALAPPDATA/Microsoft/WinGet/Packages/Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe/ffmpeg-9.0.2-full_build/bin"
"$FF/ffmpeg.exe" -v error -y -i "build/$NAME-rec.mkv" -c:v libx264 -preset medium -crf 20 -pix_fmt yuv420p -movflags +faststart "$OUT"
"$FF/ffprobe.exe" -v error -show_entries format=duration -of default=nw=1:nk=1 "$OUT" | sed "s|^|$OUT: |"
