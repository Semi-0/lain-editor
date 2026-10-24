(ns graph.lain-editor.frontend.workspace.status-error-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [graph.lain-editor.frontend.workspace.effects :as effects]
            [graph.lain-editor.frontend.workspace.model :as model]
            [graph.lain-editor.frontend.workspace.state :as s]))

(deftest extension-errors-survive-other-messages-and-clear-on-recovery
  (async done
    (let [fetch (.-fetch js/globalThis)
          cells [s/workspace s/selected-id s/busy? s/pending-id s/generations
                 s/errors s/results s/expansions s/message s/tick]
          before (mapv deref cells)
          document (model/initial-workspace)
          id (:id (first (:panes document)))
          document (-> document
                       (model/update-pane id assoc :source "(define result 7)" :executedSource "(define result 7)"
                                          :extensions ["(define extra 1)"] :extensionEdits ["(define broken"])
                       (model/spawn-pane (assoc (model/new-pane "neighbor") :source "(define result 8)"
                                               :executedSource "(define result 8)")))
          result! (fn [error]
                    (js/Promise.resolve
                     #js {:json (fn [] (js/Promise.resolve
                                        (clj->js (if error {:ok false :error error}
                                                     {:ok true :result {:generation "new-generation"}}))))}))]
      (s/transact! (fn []
       (reset! s/workspace document) (reset! s/selected-id id)
       (reset! s/busy? false) (reset! s/pending-id nil)
       (reset! s/generations {id "good-generation" "neighbor" "neighbor-generation"})
       (reset! s/errors {}) (reset! s/results {id {:response :last-good}})
       (reset! s/expansions {}) (reset! s/message "Runtime updated")))
      (set! (.-fetch js/globalThis)
            (fn [_ options]
              (let [command (js->clj (js/JSON.parse (.-body options)) :keywordize-keys true)
                    sources (into [(:source command)] (:extensions command))]
                (cond
                  (some #{"(define broken"} sources) (result! "EOF while reading")
                  (some #{"(if true 1)"} sources) (result! "if expects condition, then expression, and else expression")
                  :else (result! nil)))))
      (-> (effects/run! id "run")
          (.then (fn [_]
                   (is (= {:text "EOF while reading" :error? true} @s/bottom-status))
                   (is (= "good-generation" (get @s/generations id)))
                   (is (= {:response :last-good} (get @s/results id)))
                   (is (= ["(define broken"] (:extensionEdits (model/pane @s/workspace id))))
                   (swap! s/tick inc)
                   (reset! s/selected-id "neighbor")
                   (effects/run! "neighbor" "run")))
          (.then (fn [_]
                   (is (false? (:error? @s/bottom-status)))
                   (is (re-find #"Runtime updated" (:text @s/bottom-status)))
                   (reset! s/selected-id id)
                   (is (= "EOF while reading" (:text @s/bottom-status))
                       "Returning to a failed extension restores its error in the bottom bar")
                   (reset! s/message "Block saved")
                   (is (= "EOF while reading" (:text @s/bottom-status)))
                   (s/change-pane! id assoc :extensionEdits ["(if true 1)"])
                   (effects/run! id "run")))
          (.then (fn [_]
                   (is (re-find #"if expects condition" (:text @s/bottom-status)))
                   (is (= "good-generation" (get @s/generations id)))
                   (s/change-pane! id dissoc :extensionEdits)
                   (s/change-pane! id assoc :extensionDraft "(define broken")
                   (effects/run! id "extend")))
          (.then (fn [_]
                   (is (= "EOF while reading" (:text @s/bottom-status)))
                   (is (= "(define broken" (:extensionDraft (model/pane @s/workspace id))))
                   (s/change-pane! id assoc :extensionDraft "(if true 1)")
                   (effects/run! id "extend")))
          (.then (fn [_]
                   (is (re-find #"if expects condition" (:text @s/bottom-status)))
                   (is (= "good-generation" (get @s/generations id)))
                   (s/change-pane! id assoc :extensionDraft "")
                   (effects/run! id "run")))
          (.then (fn [_]
                   (is (nil? (get @s/errors id)))
                   (is (false? (:error? @s/bottom-status)))
                   (is (re-find #"Runtime updated" (:text @s/bottom-status)))
                   (is (= "new-generation" (get @s/generations id)))
                   (is (= ["(define extra 1)"] (:extensions (model/pane @s/workspace id))))))
          (.catch (fn [error] (is false (str "Unexpected failure: " error))))
          (.finally (fn []
                      (set! (.-fetch js/globalThis) fetch)
                      (s/transact! (fn [] (doseq [[cell value] (map vector cells before)] (reset! cell value))))
                      (done)))))))
