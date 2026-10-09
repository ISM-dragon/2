#!/usr/bin/env bash
# Runs one Gradle invocation as a CI gate.
#
#   Usage: run-gradle-gate.sh <log-name> <gradle arguments...>
#
# * Gradle's full output is streamed to the job log AND saved to
#   "$RUNNER_TEMP/<log-name>.log" (falls back to TMPDIR, then /tmp).
# * The exit status returned is Gradle's own status (taken from PIPESTATUS, not
#   from tee or from a redirection), so a failing Gradle command always fails
#   the step.
# * On failure, the diagnostic lines are republished as GitHub check annotations
#   and a short summary is appended to the job summary.
#
# The repository does not commit a Gradle wrapper; callers provide `gradle`
# (installed by gradle/actions/setup-gradle with the version pinned in the workflow).

set -uo pipefail

if [ "$#" -lt 2 ]; then
  echo "usage: $0 <log-name> <gradle arguments...>" >&2
  exit 64
fi

log_name="$1"
shift

log_dir="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
mkdir -p "$log_dir"
log_file="$log_dir/$log_name.log"

if ! command -v gradle >/dev/null 2>&1; then
  echo "::error::gradle is not on PATH; the Gradle setup step must provide it." >&2
  exit 127
fi

echo "Running: gradle $*"
echo "Full Gradle log: $log_file"

# The script does not use `set -e`, so a failing pipeline cannot abort it before
# the status is captured. PIPESTATUS[0] is Gradle's exit code.
gradle "$@" 2>&1 | tee "$log_file"
status=${PIPESTATUS[0]:-1}

echo "Gradle exit status: $status"

if [ "$status" -ne 0 ]; then
  # Emit each diagnostic line as an error annotation. Escape the characters that
  # GitHub's workflow-command parser treats specially.
  {
    grep -E '(^FAILURE:|^\* What went wrong:|^Execution failed for task|^Caused by:|^[[:space:]]*e: |error:|Unresolved reference|Expecting an element|Could not resolve|Could not find|No tests found|tests completed|There were failing tests|Keystore file .* not found)' \
      "$log_file" | tail -n 30
    tail -n 5 "$log_file"
  } | awk '!seen[$0]++' | while IFS= read -r line; do
    escaped="${line//'%'/'%25'}"
    escaped="${escaped//$'\r'/'%0D'}"
    printf '::error::%s\n' "$escaped"
  done

  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    {
      echo "### Gradle failed (exit $status)"
      echo ""
      echo "Command: \`gradle $*\`"
      echo ""
      echo '```'
      grep -E '^(FAILURE:|\* What went wrong:|Execution failed for task|e: )' "$log_file" | head -n 40
      echo '```'
    } >>"$GITHUB_STEP_SUMMARY"
  fi
fi

exit "$status"
