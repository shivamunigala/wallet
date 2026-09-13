#!/usr/bin/env bash
# One-command reproduction of every invariant the brief probes.
#
#   ./scripts/burst.sh                                  # against localhost:8080
#   ./scripts/burst.sh https://wallet.onrender.com      # against the deployed service
set -euo pipefail
exec python3 "$(dirname "$0")/burst.py" "${1:-http://localhost:8080}"
