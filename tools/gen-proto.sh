#!/usr/bin/env bash
# Git Bash entry point; the PowerShell implementation also works without Bash.
# Usage: bash tools/gen-proto.sh [-Check] [-ServerRoot <path>] [-Protoc <path>]
set -euo pipefail
script="$(cd "$(dirname "$0")" && pwd)/gen-proto.ps1"
if command -v cygpath >/dev/null 2>&1; then script="$(cygpath -w "$script")"; fi
for shell in pwsh.exe pwsh powershell.exe; do
  if command -v "$shell" >/dev/null 2>&1; then
    exec "$shell" -NoProfile -File "$script" "$@"
  fi
done
echo 'PowerShell is required. On Windows run tools/gen-proto.ps1 directly.' >&2
exit 1
