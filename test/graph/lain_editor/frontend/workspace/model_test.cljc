(ns graph.lain-editor.frontend.workspace.model-test
  (:require [clojure.string :as str]
            #?(:clj [clojure.test :refer [deftest is]] :cljs [cljs.test :refer-macros [deftest is]])
            [graph.lain-editor.frontend.workspace.model :as model]
            [graph.lain-editor.frontend.development.model :as development-model]
            [graph.lain-editor.development.protocol :as development-protocol]
            [graph.lain-editor.workspace.model :as workspace]))

(defn document []
  {:format "lain-tiled-editor" :version 1 :panes [(model/new-pane "a")]
   :layout {:type "pane" :id "a"}})

(deftest detaching-removes-drafts-without-creating-a-closed-block
  (let [doc (workspace/upgrade (document))
        detached (model/detach-pane doc "a" (model/new-pane "replacement"))
        split (model/split-pane doc "a" "row" (model/new-pane "b"))]
    (is (= detached (workspace/validate detached)))
    (is (= ["replacement"] (mapv :id (:panes detached))))
    (is (= (:closedBlocks doc) (:closedBlocks detached)))
    (is (= ["b"] (mapv :id (:panes (model/detach-pane split "a" (model/new-pane "unused"))))))
    (is (false? (model/draft-edits? (assoc (model/new-pane "a") :executedSource ""))))
    (doseq [change [{:source "draft"} {:extensionDraft "draft"}
                    {:extensions ["executed"] :extensionEdits ["edited"]}]]
      (is (model/draft-edits? (merge (model/new-pane "a") {:executedSource ""} change))))))

(deftest development-transition-declares-effects-and-separates-portable-state
  (let [client "test-client"
        portable (development-protocol/portable-model
                  "stack-spec-2026-10-20-r4" client
                  (development-protocol/empty-instance client))
        enabled (development-model/transition
                 (development-model/initial nil)
                 {:event/type :development/enable :model portable})
        started (development-model/transition
                 (:state enabled)
                 {:event/type :development/start-succeeded
                  :generation "generation-1" :instance (:instance portable)})
        commit (development-model/transition
                (:state started)
                {:event/type :development/commit :commit-id "commit-1"
                 :index 0 :text "42"})]
    (is (= :development/start (get-in enabled [:effects 0 :effect/op])))
    (is (= :active (get-in started [:state :view :status])))
    (is (= "generation-1" (get-in started [:state :remote :generation])))
    (is (= {:effect/op :development/commit :generation "generation-1"
            :commit-id "commit-1" :client-id client :index 0
            :expected-version nil :text "42"}
           (first (:effects commit))))
    (is (not (contains? (:model (:state commit)) :generation))
        "server capability tokens are not part of the portable model")))

(deftest development-success-persists-ledger-and-failure-keeps-last-success
  (let [client "test-client"
        portable (development-protocol/portable-model
                  "stack-spec-2026-10-20-r4" client
                  (development-protocol/empty-instance client))
        active (:state (development-model/transition
                        (:state (development-model/transition
                                 (development-model/initial nil)
                                 {:event/type :development/enable :model portable}))
                        {:event/type :development/start-succeeded
                         :generation "generation-1" :instance (:instance portable)}))
        committed-instance (update (:instance portable) :commits conj
                                   {:commit-id "commit-1" :client-id client
                                    :index 0 :expected-version nil :text "42"})
        committed (:state (development-model/transition
                           active {:event/type :development/commit-succeeded
                                   :generation "generation-2"
                                   :instance committed-instance
                                   :receipt {:version 0}}))
        failed (:state (development-model/transition
                        committed {:event/type :development/commit-failed
                                   :message "compile failed"}))]
    (is (= committed-instance (get-in failed [:model :instance])))
    (is (= "generation-2" (get-in failed [:remote :generation])))
    (is (= :active (get-in failed [:view :status])))
    (is (nil? (development-model/expected-version (:model failed) 1)))
    (is (= 0 (development-model/expected-version (:model failed) 0)))))

(deftest development-ledger-round-trips-and-is-archived-with-source
  (let [client "archive-client"
        portable (development-protocol/portable-model
                  "stack-spec-2026-10-20-r4" client
                  (development-protocol/empty-instance client))
        split (-> (document)
                  (model/update-pane "a" assoc :development portable)
                  (model/split-pane "a" "row" (model/new-pane "b")))
        validated (workspace/validate split)
        archived (model/close-pane validated "a" "development-archive")]
    (is (= portable (get-in archived [:closedBlocks 0 :pane :development])))
    (is (= archived (workspace/validate archived)))
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (workspace/validate
                  (assoc-in split [:panes 0 :development :instance :schema]
                            "unknown"))))))

(deftest layout-transitions-preserve-contracts
  (let [split (-> (document)
                  (model/split-pane "a" "row" (model/new-pane "b"))
                  (model/split-pane "a" "column" (model/new-pane "c")))
        closed (model/close-pane split "a")]
    (is (= split (workspace/validate split)))
    (is (= closed (workspace/validate closed)))
    (is (= {:type "pane" :id "c"} (get-in closed [:layout :first])))
    (is (thrown? #?(:clj Exception :cljs js/Error) (model/close-pane (document) "a")))
    (is (thrown? #?(:clj Exception :cljs js/Error) (model/split-pane split "missing" "row" (model/new-pane "d"))))
    (is (thrown? #?(:clj Exception :cljs js/Error) (model/split-pane split "a" "row" (model/new-pane "b"))))))

(deftest closed-blocks-preserve-editable-source-and-reopen-not-run
  (let [original (assoc (model/new-pane "a")
                        :title "Working draft" :filename "draft.lain"
                        :source "draft source" :executedSource "last run"
                        :extensions ["old extension"]
                        :extensionEdits ["edited extension"]
                        :extensionDraft "pending extension"
                        :outputRatio 0.61 :layerMode "stack")
        split (-> (document)
                  (assoc-in [:panes 0] original)
                  (model/split-pane "a" "row" (model/new-pane "b")))
        closed (model/close-pane split "a" "archive-a")
        entry (model/closed-block closed "archive-a")
        reopened (model/reopen-closed-block closed "archive-a" "desktop-1" "fresh-a")
        fresh (model/pane reopened "fresh-a")
        closed-again (model/close-pane reopened "fresh-a" "archive-b")]
    (is (= original (:pane entry)) "the portable pane descriptor is archived exactly")
    (is (= closed (workspace/validate closed)))
    (is (nil? (:executedSource fresh)) "reopen never represents a live execution")
    (is (= "last run" (:previousExecutedSource fresh)))
    (is (= (select-keys original [:title :filename :source :extensions :extensionEdits
                                  :extensionDraft :outputRatio :layerMode :layers :display])
           (select-keys fresh [:title :filename :source :extensions :extensionEdits
                               :extensionDraft :outputRatio :layerMode :layers :display])))
    (is (nil? (model/closed-block reopened "archive-a")))
    (is (= reopened
           (model/reopen-closed-block reopened "archive-a" "desktop-1" "fresh-a"))
        "redelivery with the assigned pane id is idempotent")
    (is (= 1 (:order (model/closed-block closed-again "archive-b")))
        "close order remains monotone after consuming the newest archive")
    (is (= reopened (workspace/validate reopened)))))

(deftest closed-block-boundaries-reject-loss-and-invalid-reopen
  (let [split (model/split-pane (document) "a" "row" (model/new-pane "b"))
        full (assoc split :closedBlocks
                    (mapv (fn [index]
                            {:id (str "archive-" index) :order index
                             :pane (model/new-pane (str "old-" index))})
                          (range workspace/archive-capacity)))]
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"capacity"
                          (model/close-pane full "a" "overflow")))
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (model/reopen-closed-block split "missing" "desktop-1" "fresh")))
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (workspace/validate (assoc split :closedBlocks [{:id "bad" :order 0
                                                                  :pane {:source "incomplete"}}]))))))

(deftest document-preserves-drafts-and-successful-history
  (let [doc (model/update-pane (document) "a" assoc :source "draft" :executedSource "(define result 1)"
                                :extensions ["(define next (+ result 1))"] :extensionDraft "future")]
    (is (= doc (workspace/validate doc)))
    (is (= "draft" (:source (model/pane doc "a")))))
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (workspace/validate (assoc-in (document) [:layout :id] "missing")))))

(deftest commands-have-fixed-targets-and-explicit-availability
  (let [commands (model/commands (document) "a" {} false)]
    (is (= "a" (:target (first (model/matching-commands commands " SIDE split ")))))
    (is (= :split-row (:action (first (model/matching-commands commands " SIDE split ")))))
    (is (false? (:enabled (first (model/matching-commands commands "close")))))
    (is (false? (:enabled (first (model/matching-commands commands "apply")))))
    (is (= [:save :save-as]
           (mapv :action (model/matching-commands commands "save workspace"))))
    (is (= #{:toggle-fullscreen :toggle-visualizer :toggle-fill
             :edit-extension :development-settings :layer-settings}
           (set (map :action
                     (filter #(contains? #{:toggle-fullscreen :toggle-visualizer :toggle-fill
                                           :edit-extension :development-settings :layer-settings}
                                         (:action %))
                             commands)))))
    (is (empty? (model/matching-commands commands "missing")))
    (is (every? (complement :enabled) (model/commands (document) "a" {} true)))))

(deftest applied-extension-edits-preserve-executed-code-until-replayed
  (let [pane (assoc (model/new-pane "a") :source "(def-cells input)"
                    :executedSource "(def-cells input)"
                    :extensions ["(-> 7 input)" "(define doubled (* input 2))"])
        edited (model/edit-extension pane 0 "(-> 9 input)")
        doc (assoc-in (document) [:panes 0] edited)
        committed (model/committed-source edited (:source edited) (model/extension-sources edited))]
    (is (= (:extensions pane) (:extensions edited)))
    (is (= ["(-> 9 input)" "(define doubled (* input 2))"] (model/extension-sources edited)))
    (is (model/extensions-edited? edited))
    (is (= doc (workspace/validate doc)))
    (is (re-find #"Edited" (model/status-text edited nil)))
    (is (= (model/extension-sources edited) (:extensions committed)))
    (is (not (contains? committed :extensionEdits)))
    (is (not (model/extensions-edited? committed)))
    (is (thrown? #?(:clj Exception :cljs js/Error) (model/edit-extension pane 2 "")))
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (workspace/validate (assoc-in doc [:panes 0 :extensionEdits] []))))
    (is (false? (:enabled (first (model/matching-commands
                                  (model/commands (model/update-pane doc "a" assoc :extensionDraft "more")
                                                  "a" {"a" "generation"} false) "apply")))))))

(deftest appending-an-extension-clears-an-undone-edit-without-losing-history
  (let [pane (assoc (model/new-pane "a") :executedSource "" :extensions ["first"])
        dirty (model/edit-extension pane 0 "changed")
        undone (model/edit-extension dirty 0 "first")
        appended (model/extended-source undone "second")]
    (is (thrown? #?(:clj Exception :cljs js/Error) (model/extended-source dirty "second")))
    (is (= ["first" "second"] (model/extension-sources appended)))
    (is (not (contains? appended :extensionEdits)))
    (is (= "" (:extensionDraft appended)))))

(deftest block-file-contains-current-code-and-drafts-in-order
  (let [pane (assoc (model/new-pane "a") :source "; current draft\n(define input 1)"
                    :executedSource "old source" :extensions ["old extension"]
                    :extensionEdits ["(define result (+ input 2))"]
                    :extensionDraft "(display result)")]
    (is (= "; current draft\n(define input 1)\n\n(define result (+ input 2))\n\n(display result)\n"
           (model/block-source pane)))
    (is (= "block-a.lain" (model/block-filename pane)))
    (is (= "sample.lain" (model/block-filename (assoc pane :filename "examples/sample.txt"))))
    (is (= "sample.lain" (model/block-filename (assoc pane :filename "C:\\code\\sample.lain"))))
    (is (= "(define x 1)\n" (model/block-source (assoc (model/new-pane "b") :source "(define x 1)\n"))))
    (is (= "" (model/block-source (model/new-pane "b"))))
    (is (= "a" (:target (first (model/matching-commands (model/commands (document) "a" {} false)
                                                       "save file")))))
    (is (:enabled (first (model/matching-commands (model/commands (document) "a" {} false)
                                                 "save file"))))))

(deftest manual-refresh-is-a-portable-per-block-preference
  (let [doc (document)
        manual (model/update-pane doc "a" assoc :autoRefresh false)
        labels #(set (map :label (model/commands % "a" {} false)))
        executed (model/committed-source (model/pane manual "a") "" [])]
    (is (contains? (labels doc) "Pause automatic refresh"))
    (is (contains? (labels manual) "Resume automatic refresh"))
    (is (= manual (workspace/validate manual)))
    (is (re-find #"Ready.*manual refresh" (model/status-text executed nil)))
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (workspace/validate (model/update-pane doc "a" assoc :autoRefresh "false"))))))

(deftest reconnect-preserves-unsaved-source-and-extension-drafts
  (let [pane (assoc (model/new-pane "a") :source "base draft" :executedSource "old base"
                    :extensions ["old extension"] :extensionEdits ["edited extension"]
                    :extensionDraft "pending extension")
        owner {:sessionId "owner" :runId "run" :generation "fresh"
               :source "committed base" :extensions ["committed extension"]}
        reconciled (model/reconcile-owner pane owner)]
    (is (= "base draft" (:source reconciled)))
    (is (= "committed base" (:executedSource reconciled)))
    (is (= ["edited extension"] (:extensionEdits reconciled)))
    (is (= "pending extension" (:extensionDraft reconciled)))
    (is (= ["committed extension"] (:extensions reconciled)))
    (is (= {:sessionId "owner" :runId "run" :generation "fresh"} (model/reconnect-entry owner)))
    (is (not (contains? reconciled :sessionId)))
    (is (= "committed base" (:source (model/reconcile-owner (assoc pane :source "old base") owner))))))

(deftest extension-removal-preserves-unrelated-draft-edits-and-orders
  (let [pane (assoc (model/new-pane "a") :source "base draft" :executedSource "base"
                    :extensions ["one" "two" "three"] :extensionEdits ["one draft" "two draft" "three draft"]
                    :extensionDraft "pending")
        applied [{:id "first" :source "one"} {:id "second" :source "two"} {:id "third" :source "three"}]
        removed (model/remove-committed-extension pane applied "second")
        commands (model/extension-commands "a" applied {"a" "generation"} false)]
    (is (= ["one" "three"] (:extensions removed)))
    (is (= ["one draft" "three draft"] (:extensionEdits removed)))
    (is (= "base draft" (:source removed)))
    (is (= "pending" (:extensionDraft removed)))
    (is (= pane (model/remove-committed-extension pane applied "missing")))
    (is (= 9 (count commands)))
    (is (= #{:close-extension-editor :reopen-extension-editor :remove-extension} (set (map :action commands))))
    (is (= #{"first" "second" "third"} (set (map :extensionId commands))))
    (is (every? :enabled commands))
    (is (not-any? :enabled (model/extension-commands "a" applied {"a" "generation"} true)))))

(deftest reconnect-recovers-edits-to-an-extension-removed-by-another-view
  (let [pane (assoc (model/new-pane "a") :extensions ["first" "second"]
                    :extensionEdits ["first draft" "second draft"] :extensionDraft "pending")
        prior [{:id "one" :source "first"} {:id "two" :source "second"}]
        owner {:extensions ["second"] :appliedExtensions [{:id "two" :source "second"}]}
        reconciled (model/reconcile-owner pane owner prior)]
    (is (= ["second"] (:extensions reconciled)))
    (is (= ["second draft"] (:extensionEdits reconciled)))
    (is (str/includes? (:extensionDraft reconciled) "first draft"))
    (is (str/includes? (:extensionDraft reconciled) "pending"))
    (is (= 1 (count (:extensionEdits reconciled))))))
