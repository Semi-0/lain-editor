# Running the independent editor

The editor owns browser rendering and portable editor contracts. JVM execution,
inspection, layout computation, and filesystem access run in the separately
maintained datalog-research backend. The frontend never reads a sibling checkout.

From this repository:

```sh
clojure -M:editor/cljs
npm ci
npm run dev
```

Open `http://127.0.0.1:4781`. Start the configured JVM backend separately, for
example with `clojure -M -m graph.lain-editor.server 4780` from its own repository.
The backend working directory determines project-relative file operations.

The development server binds only to a loopback host. Configure its listener and
backend explicitly when the defaults are occupied:

```sh
LAIN_EDITOR_PORT=4782 LAIN_EDITOR_BACKEND=http://127.0.0.1:4780 npm run dev
```

`LAIN_EDITOR_HOST` defaults to `127.0.0.1`; `localhost` and `::1` are also accepted.
`LAIN_EDITOR_BACKEND` must be an HTTP(S) origin without a path or credentials.
The browser sends same-origin POST requests to `/api`; the server checks their
Origin before forwarding them with backend Host/Origin headers. It does not enable
CORS, serve arbitrary project files, or simulate successful runtime operations.
An unavailable backend produces an explicit HTTP 502 error.

The server resolves its static paths relative to this repository, independently
of the shell working directory. It serves the index, stylesheet, compiled bundle,
and source map. Rebuild after source changes. SIGINT/SIGTERM closes its listener
and cancels pending backend requests.

## Verification

```sh
clojure -M:editor/cljs-test
node target/lain-editor/tests.js
npm run test:server
```

The Node server tests use temporary assets and a temporary fake backend. Their
responses are test fixtures only and are not used by `npm run dev`.

For browser acceptance, first build the frontend and run both real processes:

```sh
npx playwright install chromium
npm run test:browser
```

The browser script uses a fresh browser context. It restores the shipped legacy
example workspace and `test/fixtures/amb-workspace.json`, copied from the source
checkpoint `6ba584231cc392a98c2451bfff9a7bcd0526c1c3`. AMB acceptance requires the backend
to provide its `examples/lain/amb/web_editor.clj` primitive bindings and
`examples/lain/network_replay/collect-history.lain` recorder library; these are
backend capabilities loaded through existing commands, not frontend file reads.

It also checks detail inspection, fullscreen identity, desktop round trips,
splitting and archived drafts, save cancellation without file writes, source
extension, rendered node clearance, recursive learning values, and recorded replay.

The browser script uses a fresh browser context. It checks source editing and
cursor/focus stability, selection, command-palette dismissal, real runtime
execution, and graph camera persistence across background inspection refreshes.
It also checks that pan/zoom do not submit fresh layout requests for unchanged
topology. Browser exceptions and console errors fail the run.

`LAIN_EDITOR_URL` selects another frontend URL. `LAIN_EDITOR_BROWSER_TIMEOUT`
sets each browser action timeout in milliseconds. Set `LAIN_EDITOR_HEADED=1` for
an interactive browser. Reports and screenshots go to
`target/browser-acceptance`; override with `LAIN_EDITOR_BROWSER_OUTPUT`.
The report states actual passed checks and any failure. A successful server test
or frontend compile does not imply that browser acceptance ran or passed.

The harness probes native fullscreen Escape in a minimal page before testing the
editor. If injected Escape is unsupported by the browser automation environment,
the report marks that check blocked and uses `document.exitFullscreen()` to verify
the application's fullscreen-change lifecycle. It does not claim native Escape
passed in that case.
