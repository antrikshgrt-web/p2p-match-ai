#!/usr/bin/env bash
# P2P Match AI - compile and start. Requires a JDK 17+ (javac on PATH).
set -e
PORT="${1:-8080}"
cd "$(dirname "$0")"

if ! command -v javac >/dev/null 2>&1; then
  echo "javac not found. Install a JDK 17 or later (a JRE is not enough)."
  exit 1
fi

echo "Compiling..."
rm -rf out && mkdir -p out
find src -name "*.java" > /tmp/p2p-sources.txt
javac -d out @/tmp/p2p-sources.txt
echo "Starting on port $PORT ..."
java -cp out com.p2pmatchai.App "$PORT"
