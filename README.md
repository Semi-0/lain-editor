# Lain Editor

An independent ClojureScript frontend for the Lain runtime, with pure Replicant views and Missionary view-state composition.

## Run

Requires Java/Clojure CLI and Node.js. From this repository:

```sh
clojure -M:editor/cljs
npm ci
npm run dev
```

The frontend opens at **http://127.0.0.1:4781** and proxies `/api` to the separately running Lain backend at `http://127.0.0.1:4780`. Configure `LAIN_EDITOR_BACKEND` and `LAIN_EDITOR_PORT` when needed. Execution, inspection, layout, and project-file access require the backend; the frontend has no sibling-checkout dependency.

In the backend project, start `clojure -M -m graph.lain-editor.server 4780`. See [running](docs/running.md) and the [user guide](docs/user-guide.md).

## Verify

```sh
clojure -M:test
clojure -M:editor/cljs-test
node target/lain-editor/tests.js
npm run test:server
```

For real browser acceptance, build the app, start both processes, install the declared Playwright browser (`npx playwright install chromium`), then run `npm run test:browser`. Browser verification requires the real backend; server transport tests inject a controlled backend.

## Architecture

- `src/graph/lain_editor/frontend/domain`: authoritative program facts, without UI/reactive/DOM dependencies.
- `frontend/model` and `workspace/state`: legacy workspace codec and one source partitioned into domain, UI, and remote observations.
- `frontend/ui/reactive`: owned Missionary projections and cancellation. Derived values expose no independent mutation operation.
- `frontend/view` and feature view functions: pure Hiccup, stable keys, declarative event data.
- `frontend/controller` and effect adapters: interaction interpretation, requests, measurements, focus, fullscreen, and process ownership.
- `contracts`: portable workspace/layer validation, development protocol, and clearance geometry, consumed by a pinned backend dependency.
- `build`: portable ClojureScript build provider with explicit caller-owned outputs.
- `test/deps.edn`: explicit test-source dependency for delegated builds; test namespaces stay owned here.

See [architecture](docs/reactive-architecture.md), [extraction](docs/extraction.md), and [verification](docs/verification.md). Existing source, saved workspace formats, runtime semantics, Standard layout, and history accumulation remain in place.
