# Verification

Required checks for this migration: independent optimized build; JVM/shared and architecture tests; CLJS/Node tests; dev-server transport tests; real-browser editor/graph/lifecycle interactions; backend integration through explicit dependency adapters; fresh-checkout reproduction. Results are recorded here after execution. Generated reports and screenshots live under ignored target/browser-acceptance.

The source checkpoint is 6ba584231cc392a98c2451bfff9a7bcd0526c1c3. Before extraction, 83 frontend tests/751 assertions passed, the optimized build passed, development runtime/layout/source-file focused suites passed, and HTTP tests passed with loopback access. The existing child-topology assertion still expected only shift/square while actual topology also includes copy/->; that backend expectation was preserved.

The independent legacy baseline is 69a3bc9b4fd0d45695ea7440ae3de34dac9e3f12. Its optimized build and 83 frontend tests/751 assertions passed without the original repository on the classpath.

## Declarative migration results

Observed on 2026-10-24:

- Optimized ClojureScript build: passed.
- JVM/shared and architecture checks: 32 tests / 232 assertions, zero failures/errors.
- CLJS/Node suite: 104 tests / 836 assertions, zero failures/errors, including complete-run enforcement and failed-process exit codes.
- Loopback dev-server transport tests: 5 passed.
- Real-browser acceptance: 18 required checks passed, Chromium 156.0.8078.4, 00:47:50.518–00:48:26.626 UTC, against the isolated checkpoint backend.

Browser checks cover cursor/focus, commands, real execution, visible graph geometry, exact zoom/pan changes and persistence with one layout request across unchanged polls, node details, fullscreen DOM identity/camera, desktop round trips, split autofocus, archive/reopen with a fresh identity, save cancellation without writes, extension binding selection, legacy workspace restore, five-node clearance (minimum observed gap 29 world units), recursive AMB learning (eight structural branches), and eight-frame recorded replay. Scrubbing and frozen Strongest/Content inspection issued zero live-node reads. No page or console errors were observed.

Native Escape exit in this headless Chromium driver is separately **blocked**: injected Escape also fails on a minimal standalone fullscreen page. Entering fullscreen and API exit through fullscreenchange preserve the actual mounted SVG and camera. The report records this limitation rather than claiming Escape passed.

Shared production workspace, layer, development-protocol and node-clearance source blobs are identical to the source checkpoint. Kiroshi knowledge sync and lifecycle approval were not performed.

Fresh-checkout reproduction passed: locked npm install, 32 JVM/shared/architecture tests (232 assertions), 104 CLJS tests (836 assertions), and all five Node transport tests.

A separate clean backend candidate with copied frontend/shared source removed successfully built both the application and frontend tests through the public build provider. Its 37 focused JVM tests passed 252 assertions (development runtime, graph layout, source-file HTTP effects, and slot spawning). Prepublication validation used explicit temporary local coordinates; committed integration uses pinned Git coordinates. A dedicated test subproject replaced deprecated classpath entries outside the build subproject. The delegated CLJS/Node run again passed 104 tests/836 assertions.

The complete backend matrix was not claimed green: the known child-topology assertion remains unchanged, and unrelated working-tree runtime/benchmark work was excluded. No clojure-lsp or clj-kondo result is claimed.
