# Lain editor reactive architecture

Status: accepted implementation plan, 2026-10-20.

This evolution keeps Javelin as the browser reactive substrate and keeps Lain
propagators on the JVM as the semantic runtime. Browser interaction state is not
represented as propagator cells: the existing propagator and differential
dataflow implementations are JVM-only, and their information-order semantics do
not match transient UI interaction.

The first feature using the boundary is opt-in premise-versioned development.
Existing editor behavior remains the compatibility path and can migrate one
feature at a time.

## Ownership

```clojure
;; Portable model: source facts required to reconstruct a development session.
{:mode "versioned-premise"
 :specificationRevision "stack-spec-2026-10-20-r9"
 :clientId "pane-owned-logical-client"
 :instance {:schema "compiler-2/versioned-instance"
            :schema-version 1
            :clients [{:client-id "pane-owned-logical-client"
                       :mode "versioned-premise"}]
            :commits []}}

;; Feature state. Only :model crosses the workspace persistence boundary.
{:model portable-model-or-nil
 :view {:status :ordinary-or-starting-or-active-or-committing-or-closing
        :message nil}
 :remote {:generation nil
          :checkpoint nil
          :projection nil}}

;; Pure interaction boundary. Effects are declarations, not executed here.
(transition state event)
;; => {:state next-state
;;     :effects [{:effect/op :development/start ...}]}
```

- `frontend.development.model` owns the representations and pure transition.
- `frontend.development.dataflow` owns one feature-local Javelin input cell and
  derived view model. It serializes event reduction; formulas never act as event
  queues.
- `frontend.development.effects` interprets declared HTTP effects and returns
  success/failure events.
- `frontend.development.view` creates DOM once. Handlers dispatch intents and
  watches render derived data; the view does not call the server or mutate the
  workspace directly. Its pane-owned adapter persists the portable model.
- `development.protocol` validates portable request and model boundaries.
- `development.runtime` owns live JVM sessions and generations. No session,
  network, clock, or capability enters workspace JSON.
- `development.handler` owns the feature's server operation dispatch.
- `experiments.baseline` owns the pinned revision, ordered source manifest,
  fixture expectations, initial outcome membership, and controlled replay.

## Dependency direction

```text
view -> dataflow -> pure model
                    |
                    v
             effect declarations -> effect interpreter -> JSON protocol
                                                          |
                                                          v
server router -> development handler -> development runtime -> compiler runtime

experiment baseline -> compiler runtime + checkpoint summary
```

The server runtime never imports frontend namespaces. Views never inspect live
runtime objects. Ordinary editor runtime does not depend on development runtime.

## Public composition

```clojure
(defn create-development-feature
  [scope {:keys [pane-id portable-model persist-model! api!]}]
  ;; returns owned signals and a dispatch function
  {:state state-cell
   :view-model derived-cell
   :dispatch! dispatch-function})

(defn start-development!
  [registry {:keys [pane specification-revision instance outcomes]}]
  ;; atomically prepares/replays a candidate before replacing an old session
  {:generation generation
   :specificationRevision specification-revision
   :instance portable-instance})

(defn commit-development!
  [registry {:keys [pane generation commit-id client-id index
                    expected-version text]}]
  ;; failed or stale commits leave the registered session and generation intact
  {:generation next-generation
   :receipt receipt
   :instance portable-instance})
```

## Fixed constraints

- Javelin remains the frontend reactive dependency for this evolution.
- Compiler premise, merge, scheduler, and ordinary editor runtime semantics do
  not change.
- Development mode is opt-in; absence of its portable model means ordinary mode.
- Successful mutations return a generation token. Stale tokens fail explicitly.
- Checkpoint reads consume maintained outcomes and do not propagate, acquire, or
  scan the network.
- Closing a pane, replacing a workspace, or stopping the server releases the
  corresponding development session.
- Restart recovery replays source and commit history; it does not serialize JVM
  capabilities or claim restoration of external state.

## Required verification

- Pure transition branches and declared effects.
- Portable model and request validation.
- Deterministic source manifest and revision-pinned baseline replay.
- Commit replay, stale-generation rejection, failed-commit atomicity, and close.
- Workspace compatibility for documents without development state.
- Ordinary editor regression tests, frontend tests, production build, and a
  clean-reconstruction comparison at the later integration gate.
