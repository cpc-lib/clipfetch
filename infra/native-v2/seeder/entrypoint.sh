#!/usr/bin/env bash
set -euo pipefail

rm -f /fixtures/ready
python3 /opt/seeder/generate_fixtures.py
python3 /opt/seeder/tracker.py >/dev/stdout 2>&1 &
exec python3 /opt/seeder/seeder.py
