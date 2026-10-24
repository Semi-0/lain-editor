# Frontend extraction

Source repository: https://github.com/Semi-0/datalog-research.git
Source checkpoint: 6ba584231cc392a98c2451bfff9a7bcd0526c1c3
Filtered checkpoint: 6800904 (new hashes are expected).

History includes current and historical frontend, assets, build and portable-core paths. Authors and dates are retained. The source history was not rewritten.

`src` owns browser behavior, `contracts` owns portable workspace/layer/protocol/clearance contracts, `build` owns build tools, `resources` owns browser assets, and `test` owns frontend tests. JVM execution, inspection, layout computation and file access stay in datalog-research. No sibling checkout is on the classpath.

Build: `clojure -M:editor/cljs`. Tests: `clojure -M:editor/cljs-test` then `node target/lain-editor/tests.js`.

The `build` and `test` subprojects declare only their own source roots. Backend build/test aliases consume these explicit Git coordinates; no deprecated classpath paths outside a subproject or sibling-checkout paths are required. Production shared contracts remain unchanged.
