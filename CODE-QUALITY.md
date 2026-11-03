# Clojure code-quality gates

Both CI scripts run formatting check, clj-kondo lint and the quality-runner tests before expensive compilation/tests. Tools are pinned to clj-kondo2026.08.04 and cljfmt0.16.6 through repository aliases; no global install is needed.

Commands:
- clojure -M:lint
- clojure -M:format-check
- clojure -M:format-fix
- clojure -M:quality-test

Default scope is added/modified .clj/.cljs/.cljc/.edn files, including local untracked files. Generated outputs, dependency/vendor paths and caches are excluded. Lain .lain programs use the Lain compiler tests instead.

Local checks compare with merge-base HEAD origin/main, or HEAD when no remote baseline is available. Use --base COMMIT explicitly when needed. CI receives each repository's previous verified SHA through LAIN_CI_BASE_SHA; a missing CI baseline fails clearly. Do not change the baseline to hide new errors.

Formatting checks never rewrite source. The explicit fix command formats selected files; parser errors leave those files untouched. Format fixes should be reviewed separately from semantic changes.

Lint compares baseline and candidate diagnostics with the same pinned tool/config. Existing errors are reported; only newly introduced errors fail the changed-code gate. Warnings are visible but non-blocking. Diagnostic matching includes type/message and the source span, avoiding line-number-only comparisons after formatting. Duplicate diagnostics are counted. Analyzer deduplication means this is a diagnostic baseline, not proof that every additional use of an already-unresolved symbol is independently reported.

Use --all --report-only for a whole-repository debt report. Full reports do not authorize sweeping code rewrites. Report files are under target/quality. Never suppress all unresolved-symbol/arity checks to obtain a passing gate; use narrow documented analyzer configuration for genuine macros.

On the first deployment with no verified release, explicitly establish and review a baseline before enabling the changed-code CI lane. On GX10 the deployment driver supplies the existing active pair automatically.

A clean lint/format result is not behavioral acceptance. Tests, session/effect cleanup, browser gates and source review remain required.
