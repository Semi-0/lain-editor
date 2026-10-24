# Declarative frontend architecture

One authoritative snapshot contains `:domain`, `:ui`, and versioned `:remote` observations. The codec translates supported saved workspaces into these owners and reconstructs the legacy JSON format on save. Compatibility lenses contain no mutable document mirror. Domain constructors and accessors own their representation; workspace invariants remain at the existing validation boundary.

```text
Declarative events -> controller -> domain/UI transitions + effect adapters
                              |
Authoritative snapshot -> Missionary projections -> immutable view state
                                                   |
                                          pure Hiccup -> Replicant
```

Missionary owns reactive composition, equality suppression and process cancellation. It does not execute domain reducers, persistence policy, or rendering functions as business logic. Controllers perform synchronous transitions and explicit effects. The application owns its renderer, pane processes, polling, observers, requests, animations, keyboard handlers and native widget adapters. Disposal is idempotent. Generation/request/owner identities reject late results even when an underlying asynchronous operation cannot be stopped.

Views receive only explicit immutable values. The single DOM renderer is in frontend/main.cljs. Views emit declarative event data; the interpreter resolves browser placeholders at the effect boundary. DOM measurement, focus/selection, file input, modal state, fullscreen and pointer capture live in named adapters. Replicant retains keyed pane/text editor/graph identities. Collection children are sequences or are appended into the parent Hiccup vector; actual string rendering and browser tests cover that contract.

Domain source, authoritative UI preferences and derived presentation are distinct. Selection, camera, folds, panels, drag state and user layout settings are UI facts. Visible scene and label projections are pure derivations. Backend snapshots remain read-only observations, never a second mutable runtime. Layout depends on semantic topology and exact node dimensions, not pan/zoom, viewport resize, unchanged polls or label-only changes that keep geometry. Drag anchors disappear on release/cancel. The backend retains Standard Stress and hard clearance; the frontend does not implement another solver.

Recorded replay uses frozen frame/scope data, including separate Strongest and Content sections. No-good learning remains a projection of the existing network-history recorder/reducer. This migration does not alter compiler/session/merge/propagator policy or introduce DataScript.

## Model knowledge

The source model's `:decision/lain-editor-javelin-feature-dataflow` is an active candidate with needs-verification evidence; it is not current governing knowledge. The proposed successor is `:decision/lain-editor-replicant-missionary`, scoped to the existing frontend component. Its meaning is the four-layer architecture above, with controller-owned effects and preserved host/session/immutable-structure boundaries. It is motivated by the existing compositional-extension, explicit-coordination and message-boundary principles, and supported by this implementation and the required checks in verification.md.

Repository ownership changes and new evidence paths need reconciliation in the source model and a repository-local frontend model. No architecture hierarchy is inferred from directories. The old decision and evidence must remain as superseded history if synchronization is authorized. No Kiroshi facts, lifecycle approvals or database backups were mutated during this code migration; model synchronization remains a separate explicit authorization step.
