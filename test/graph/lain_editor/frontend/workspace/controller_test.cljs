(ns graph.lain-editor.frontend.workspace.controller-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [graph.lain-editor.frontend.workspace.state :as s]
            [graph.lain-editor.frontend.workspace.model :as model]
            [graph.lain-editor.frontend.workspace.effects :as effects]
            [graph.lain-editor.frontend.controller.events :as events]))

(deftest ui-and-domain-events-have-one-authoritative-source
  (let [before @s/source
        initial (model/initial-workspace)
        id (:id (first (:panes initial)))]
    (try
      (reset! s/source (s/initial initial))
      (events/dispatch! [:workspace/edit-source id "(+ 1 2)"])
      (events/dispatch! [:panel/toggle id :layers true])
      (is (= "(+ 1 2)" (:source (model/pane (:workspace (s/snapshot @s/source)) id))))
      (is (true? (get-in @s/source [:ui :panes-ui id :panels :layers])))
      (is (not (contains? @s/source :workspace)))
      (is (not (contains? (:ui @s/source) :selected-id)))
      (is (= id (:selected-id (s/snapshot @s/source))))
      (is (thrown? js/Error (events/dispatch! [:unregistered/event])))
      (finally (reset! s/source before)))))

(deftest late-http-responses-cannot-restore-closed-or-newer-generations
  (async done
    (let [before @s/source fetch (.-fetch js/globalThis)
          document (model/initial-workspace)
          id (:id (first (:panes document)))
          pending (atom [])
          fake-fetch (fn [_ _]
                       (js/Promise. (fn [resolve _] (swap! pending conj resolve))))
          succeed! (fn [resolve]
                     (resolve #js {:json (fn [] (js/Promise.resolve
                                                #js {:ok true :result #js {:value "late"}}))}))]
      (reset! s/source (s/initial (model/split-pane document id "row" (model/new-pane "other"))))
      (reset! s/generations {id "old"})
      (set! (.-fetch js/globalThis) fake-fetch)
      (let [closed (effects/api! {:op "inspect" :pane id :generation "old"})]
        (swap! s/workspace model/close-pane id "archive")
        (succeed! (first @pending))
        (-> closed
            (.then (fn [_] (is false "A closed pane must reject its late response")))
            (.catch (fn [error] (is (effects/cancelled? error))))
            (.then (fn [_]
                     (reset! s/workspace document)
                     (reset! s/generations {id "old"})
                     (let [stale (effects/api! {:op "inspect" :pane id :generation "old"})]
                       (swap! s/generations assoc id "new")
                       (succeed! (second @pending))
                       (-> stale
                           (.then (fn [_] (is false "A newer generation must reject its predecessor")))
                           (.catch (fn [error] (is (effects/cancelled? error))))))))
            (.then (fn [_]
                     (let [disposed (effects/api! {:op "workspace-poll"})]
                       (effects/dispose!)
                       (succeed! (nth @pending 2))
                       (-> disposed
                           (.then (fn [_] (is false "Disposal must reject already-completing requests")))
                           (.catch (fn [error] (is (effects/cancelled? error))))))))
            (.catch (fn [error] (is false (str "Unexpected controller failure: " error))))
            (.finally (fn []
                        (set! (.-fetch js/globalThis) fetch)
                        (reset! s/source before)
                        (done))))))))

(deftest disposal-stops-queued-effects-and-stale-file-loads
  (async done
    (let [before @s/source document (model/initial-workspace)
          id (:id (first (:panes document)))
          resolve-file (atom nil)
          file #js {:name "older.lain"
                    :text (fn [] (js/Promise. (fn [resolve _] (reset! resolve-file resolve))))}
          called (atom 0)]
      (reset! s/source (s/initial document))
      (let [loading (effects/load-file! id file)]
        (s/change-pane! id assoc :source "newer user edit")
        (@resolve-file "older file content")
        (-> loading
            (.then (fn [_]
                     (is (= "newer user edit" (:source (model/pane @s/workspace id))))
                     (let [queued (effects/operation! id #(swap! called inc))]
                       (effects/dispose!)
                       queued)))
            (.then (fn [_]
                     (is (zero? @called))
                     (is (false? @s/busy?))
                     (is (nil? @s/pending-id))))
            (.catch (fn [error] (is false (str "Unexpected lifecycle failure: " error))))
            (.finally (fn [] (reset! s/source before) (done))))))))

(deftest disposing-an-old-handler-preserves-its-replacement
  (let [calls (atom [])
        old-handler (fn [event _] (swap! calls conj [:old event]))
        new-handler (fn [event _] (swap! calls conj [:new event]))
        dispose-old (events/register-handler! "replacement-test" old-handler)
        dispose-new (events/register-handler! "replacement-test" new-handler)]
    (try
      (dispose-old)
      (events/dispatch! [:replacement-test/run])
      (is (= [[:new [:replacement-test/run]]] @calls))
      (dispose-new)
      (is (thrown? js/Error (events/dispatch! [:replacement-test/run])))
      (finally (dispose-old) (dispose-new)))))
