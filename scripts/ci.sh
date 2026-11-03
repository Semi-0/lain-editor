#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
for tool in clojure node npm python3; do
  if ! command -v "$tool" >/dev/null; then
    printf 'Missing CI prerequisite: %s\n' "$tool" >&2
    exit 1
  fi
done
clojure -M:format-check
clojure -M:lint
clojure -M:quality-test
clojure -M:test
clojure -M:editor/cljs-test
node target/lain-editor/tests.js
npm ci --ignore-scripts --no-audit --no-fund
npm run test:server
node --test scripts/editing.test.mjs
npm run test:gateway
python3 deploy/gx10/render_test.py
clojure -M:editor/cljs
node node_modules/playwright/cli.js install chromium
npm run test:auth-browser
