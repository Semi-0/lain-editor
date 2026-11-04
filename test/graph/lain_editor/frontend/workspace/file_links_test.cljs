(ns graph.lain-editor.frontend.workspace.file-links-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [graph.lain-editor.frontend.workspace.effects :as effects]
            [graph.lain-editor.frontend.workspace.source-save :as save]
            [graph.lain-editor.frontend.workspace.file-links :as links]
            [graph.lain-editor.frontend.workspace.model :as model]
            [graph.lain-editor.frontend.workspace.state :as state]))

(deftest external-change-preserves-dirty-editor-and-either-resolution-preserves-both
  (doseq [choice [:editor :file]]
    (let [before @state/source
          document (model/initial-workspace)
          id (:id (first (:panes document)))
          baseline {:path "main.lain" :version "v1" :source "original" :exists true}
          external {:path "main.lain" :version "v2" :source "external version" :exists true}]
      (try
        (reset! state/source (state/initial (model/update-pane document id assoc :source "local unsaved draft")))
        (links/register! id baseline)
        (links/reconcile! id baseline external)
        (is (= "local unsaved draft" (:source (model/pane @state/workspace id))))
        (is (= external (get-in @state/ui [:file-links id :external])))
        (links/resolve! id choice)
        (is (= #{"external version" "local unsaved draft"} (set (map :source (:panes @state/workspace)))))
        (is (nil? (get-in @state/ui [:file-links id :external])))
        (is (empty? @state/generations))
        (is (empty? @state/session-ids))
        (is (= 1 (count (:file-links @state/ui))) "The preserved copy must remain unlinked")
        (finally (reset! state/source before))))))

(deftest clean-file-view-refreshes-without-replaying-runtime-and-ignores-stale-polls
  (let [before @state/source
        document (model/initial-workspace)
        id (:id (first (:panes document)))
        baseline {:path "main.lain" :version "v1" :source "original" :exists true}
        external {:path "main.lain" :version "v2" :source "updated" :exists true}]
    (try
      (reset! state/source (state/initial (model/update-pane document id assoc :source "original" :extensions ["retained extension"])))
      (reset! state/generations {id "generation"})
      (reset! state/session-ids {id "owner"})
      (links/register! id baseline)
      (links/reconcile! id baseline external)
      (is (= "updated" (:source (model/pane @state/workspace id))))
      (is (= ["retained extension"] (:extensions (model/pane @state/workspace id))))
      (is (= {id "generation"} @state/generations))
      (is (= {id "owner"} @state/session-ids))
      (links/reconcile! id baseline baseline)
      (is (= "updated" (:source (model/pane @state/workspace id))))
      (finally (reset! state/source before)))))

(deftest run-saves-main-source-before-executing-and-conflicts-block-execution
  (async done
         (let [before @state/source original-fetch (.-fetch js/globalThis)
               document (model/initial-workspace) id (:id (first (:panes document)))
               calls (atom []) fail-save? (atom false)
               setup! (fn []
                        (effects/dispose!)
                        (reset! state/source (state/initial (model/update-pane document id assoc :source "(define result 42)")))
                        (links/register! id {:path "main.lain" :version "v1" :source "previous" :exists true}))]
           (setup!)
           (set! (.-fetch js/globalThis)
                 (fn [_ options]
                   (let [command (js->clj (js/JSON.parse (.-body options)) :keywordize-keys true)
                         _ (swap! calls conj command)
                         conflict? (and @fail-save? (= "files/write" (:op command)))
                         result (case (:op command)
                                  "files/write" {:path "main.lain" :version "v2" :source (:source command) :exists true}
                                  "run" {:sessionId "owner" :runId "run" :generation "g1" :extensions [] :appliedExtensions []})]
                     (js/Promise.resolve
                      #js {:status (if conflict? 400 200) :ok (not conflict?)
                           :json #(js/Promise.resolve (clj->js (if conflict? {:ok false :error "file conflict"} {:ok true :result result})))}))))
           (-> (effects/run! id "run")
               (.then (fn [_]
                        (is (= ["files/write" "run"] (mapv :op @calls)))
                        (is (= {:path "main.lain" :version "v2"} (:savedFile (second @calls))))
                        (is (= "(define result 42)" (:source (first @calls))))
                        (reset! calls [])
                        (reset! fail-save? true)
                        (setup!)
                        (effects/run! id "run")))
               (.then (fn [_]
                        (is (= ["files/write"] (mapv :op @calls)))
                        (is (empty? @state/session-ids))
                        (is (= "(define result 42)" (:source (model/pane @state/workspace id))))
                        (is (re-find #"file conflict" @state/message))))
               (.catch (fn [error] (is false (.-message error))))
               (.finally (fn []
                           (effects/dispose!)
                           (set! (.-fetch js/globalThis) original-fetch)
                           (reset! state/source before)
                           (done)))))))

(deftest cancelling-save-before-run-never-executes
  (async done
         (let [before @state/source original-fetch (.-fetch js/globalThis)
               document (model/initial-workspace) id (:id (first (:panes document)))
               calls (atom 0)]
           (effects/dispose!)
           (reset! state/source (state/initial document))
           (set! (.-fetch js/globalThis) (fn [& _] (swap! calls inc)))
           (let [running (effects/run! id "run")]
             (save/dispatch! [:save-dialog/cancel])
             (-> running
                 (.then (fn [_]
                          (is (zero? @calls))
                          (is (empty? @state/session-ids))
                          (is (nil? (:save-dialog @state/ui)))))
                 (.catch (fn [error] (is false (.-message error))))
                 (.finally (fn []
                             (effects/dispose!)
                             (set! (.-fetch js/globalThis) original-fetch)
                             (reset! state/source before)
                             (done))))))))
