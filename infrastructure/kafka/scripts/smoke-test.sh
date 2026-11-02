#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
exec docker compose --project-directory "$SCRIPT_DIR/.." -f "$SCRIPT_DIR/../compose.yaml" \
    --profile tools run --rm --build tools smoke "$@"
