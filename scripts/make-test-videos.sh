#!/usr/bin/env bash
# Generates the synthetic videos used by the UI tests (bundled as androidTest assets).
# Every frame changes colour across the whole picture, so tests can tell a playing
# video from a frozen or black one from screenshots alone.
set -euo pipefail
out="${1:-app/src/androidTest/assets/videos}"
mkdir -p "$out"
enc=(-c:v libx264 -preset veryfast -crf 30 -pix_fmt yuv420p -g 30 -movflags +faststart -an)
ffmpeg -y -loglevel error -f lavfi -i "testsrc2=size=1920x1080:rate=30,hue=H=2*PI*t/6:s=1.4" -t 40 "${enc[@]}" "$out/scene-a.mp4"
ffmpeg -y -loglevel error -f lavfi -i "testsrc=size=1920x1080:rate=30,hue=H=2*PI*t/5:s=1.4" -t 40 "${enc[@]}" "$out/scene-b.mp4"
ffmpeg -y -loglevel error -f lavfi -i "smptehdbars=size=1280x720:rate=30,hue=H=2*PI*t/4,rotate=PI*t/12" -t 40 "${enc[@]}" "$out/scene-c.mp4"
ffmpeg -y -loglevel error -f lavfi -i "testsrc2=size=1280x720:rate=60,hue=H=2*PI*t/6:s=1.4" -t 30 \
  -c:v libx265 -preset veryfast -crf 30 -pix_fmt yuv420p -tag:v hvc1 -x265-params keyint=60:log-level=error \
  -movflags +faststart -an "$out/scene-hfr.mp4"
ls -la "$out"
