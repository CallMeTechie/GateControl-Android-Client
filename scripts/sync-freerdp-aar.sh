#!/usr/bin/env bash
# Downloads the latest freerdp-android.aar built by CI into core/rdp/libs/.
# Use this when you need the AAR locally but do not want to build FreeRDP
# natively on your workstation.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

if ! command -v gh >/dev/null 2>&1; then
  echo "error: gh CLI is required (https://cli.github.com/)" >&2
  exit 1
fi

RUN_ID=$(gh run list \
  --workflow="Build FreeRDP AAR" \
  --status=success \
  --limit=1 \
  --json databaseId -q '.[0].databaseId')

if [ -z "$RUN_ID" ]; then
  echo "error: no successful Build FreeRDP AAR run found" >&2
  exit 1
fi

echo "Downloading AAR from run $RUN_ID..."
mkdir -p core/rdp/libs
gh run download "$RUN_ID" --name freerdp-android-aar --dir /tmp/freerdp-aar
cp /tmp/freerdp-aar/freerdp-android.aar core/rdp/libs/freerdp-android.aar
rm -rf /tmp/freerdp-aar

echo "OK: core/rdp/libs/freerdp-android.aar ($(du -h core/rdp/libs/freerdp-android.aar | cut -f1))"
