(ns graph.lain-editor.frontend.workspace.controller-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [graph.lain-editor.frontend.workspace.state :as s]
            [graph.lain-editor.frontend.workspace.model :as model]
            [graph.lain-editor.frontend.workspace.effects :as effects]
            [graph.lain-editor.frontend.controller.dom :as dom]
            [graph.lain-editor.frontend.controller.events :as events]))

(deftest overlay-reserves-visible-layer-bounds-instead-of-scrollable-content
  (let [before @s/source
        initial (model/initial-workspace)
        id (:id (first (:panes initial)))
        heights (atom [120 35])
        code #js {:scrollHeight 900
                  :getBoundingClientRect (fn [] #js {:height (first @heights)})}
        result #js {:scrollHeight 300
                    :getBoundingClientRect (fn [] #js {:height (second @heights)})}
        root #js {:querySelector (fn [selector]
                                  (case selector
                                    ".layer-code" code
                                    ".layer-result" result
                                    nil))}]
    (try
      (reset! s/source (s/initial initial))
      (dom/dispatch! [:dom/pane-updated id root])
      (is (= {:code-height 120 :result-height 35}
             (get-in @s/ui [:panes-ui id :measurements])))
      (reset! heights [75 0])
      (dom/dispatch! [:dom/pane-updated id root])
      (is (= {:code-height 75 :result-height 0}
             (get-in @s/ui [:panes-ui id :measurements]))
          "Resizing and hidden layers update the reserved space")
      (dom/dispatch! [:dom/pane-updated id #js {:querySelector (fn [_] nil)}])
      (is (= {:code-height 0 :result-height 0}
             (get-in @s/ui [:panes-ui id :measurements])))
      (finally (reset! s/source before)))))

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

(deftest graphical-view-size-is-local-and-bounded
  (let [before @s/source initial (model/initial-workspace)
        id (:id (first (:panes initial)))]
    (try
      (reset! s/source (s/initial initial))
      (events/dispatch! [:visualizer/size id "80"])
      (is (= 80 (get-in @s/ui [:panes-ui id :graph-size])))
      (is (= initial @s/workspace))
      (doseq [invalid ["bad" 0 100]]
        (is (thrown? js/Error (events/dispatch! [:visualizer/size id invalid]))))
      (events/dispatch! [:workspace/command {:action :visualizer-settings :target id}])
      (is (= :visualizer (:view @s/palette)))
      (is (true? (:open? @s/palette)))
      (events/dispatch! [:palette/open id])
      (is (nil? (:view @s/palette)))
      (finally (reset! s/source before)))))

(deftest event-interpolation-preserves-palette-cancel-actions
  (let [native #js {:key "Escape"}
        actions [[:event/prevent-default] [:palette/close]
                 [:palette/key [:event/key]]]]
    (is (= [[:event/prevent-default] [:palette/close] [:palette/key "Escape"]]
           (dom/interpolate {:replicant/dom-event native} actions)))))

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

(deftest detach-keeps-local-state-until-success-and-never-closes
  (async done
    (let [before @s/source fetch (.-fetch js/globalThis)
          document (model/initial-workspace) id (:id (first (:panes document)))
          calls (atom []) pending (atom nil)
          response! (fn [body]
                      (@pending #js {:json #(js/Promise.resolve (clj->js body))}))]
      (reset! s/source (s/initial (model/update-pane document id assoc :executedSource "")))
      (reset! s/generations {id "generation"})
      (set! (.-fetch js/globalThis)
            (fn [_ options]
              (swap! calls conj (js->clj (js/JSON.parse (.-body options)) :keywordize-keys true))
              (js/Promise. (fn [resolve _] (reset! pending resolve)))))
      (let [failed (effects/detach-session! id)]
        (-> (js/Promise.resolve nil)
            (.then (fn [_]
                     (is (some? (model/pane @s/workspace id)))
                     (response! {:ok false :error "Session disappeared"})
                     failed))
            (.then (fn [_]
                     (is (some? (model/pane @s/workspace id)))
                     (is (= "generation" (get @s/generations id)))
                     (is (= "Session disappeared" (get @s/errors id)))
                     (let [detaching (effects/detach-session! id)]
                       (-> (js/Promise.resolve nil)
                           (.then (fn [_]
                                    (response! {:ok true :result {}})
                                    detaching))))))
            (.then (fn [_]
                     (is (nil? (model/pane @s/workspace id)))
                     (is (nil? (get @s/generations id)))
                     (is (empty? (:closedBlocks @s/workspace)))
                     (is (= [{:op "detach-session" :pane id :generation "generation"}
                             {:op "detach-session" :pane id :generation "generation"}] @calls))))
            (.catch (fn [error] (is false (str error))))
            (.finally (fn []
                        (set! (.-fetch js/globalThis) fetch)
                        (reset! s/source before)
                        (done))))))))

(deftest cancelling-draft-discard-does-not-detach
  (let [before @s/source confirm (aget js/globalThis "confirm")
        document (model/initial-workspace) id (:id (first (:panes document)))
        warnings (atom [])]
    (try
      (reset! s/source (s/initial (model/update-pane document id assoc
                                                  :source "edited" :executedSource "executed")))
      (reset! s/generations {id "generation"})
      (aset js/globalThis "confirm" (fn [message] (swap! warnings conj message) false))
      (let [unchanged @s/source]
        (is (nil? (effects/detach-session! id)))
        (is (= unchanged @s/source))
        (is (= 1 (count @warnings))))
      (finally
        (aset js/globalThis "confirm" confirm)
        (reset! s/source before)))))
