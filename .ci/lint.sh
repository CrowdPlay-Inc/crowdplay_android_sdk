#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

version=1.8.0
tool_dir="${SDK_CI_TOOLS_DIR:-${RUNNER_TEMP:-${TMPDIR:-/tmp}}/crowdplay-android-ci}/ktlint-$version"
mkdir -p "$tool_dir"
if [[ ! -f "$tool_dir/ktlint" ]]; then
  curl --fail --location --silent --show-error --retry 3 \
    "https://github.com/ktlint/ktlint/releases/download/$version/ktlint" \
    --output "$tool_dir/ktlint"
fi
echo "a3fd620207d5c40da6ca789b95e7f823c54e854b7fade7f613e91096a3706d75  $tool_dir/ktlint" | shasum -a 256 --check

# A missing baseline makes ktlint generate one. Fail instead of silently doing so.
test -s .ci/ktlint-baseline.xml
test -s CrowdplaySdk.kt
java -jar "$tool_dir/ktlint" --baseline=.ci/ktlint-baseline.xml '**/*.kt' '**/*.kts'
