(ns graph.lain-editor.frontend.outputs
  "One inspection subscription feeds independently visible output layers."
  (:require [graph.lain-editor.layers :as layers]
            [graph.lain-editor.frontend.dom :as dom]
            [graph.lain-editor.frontend.value-grid :as value-grid]
            [graph.lain-editor.frontend.effects :as effects]
            [graph.lain-editor.frontend.value-view :as value-view]
            [graph.lain-editor.frontend.replay :as replay]
            [graph.lain-editor.frontend.stack-scene :as stack-scene]
            [graph.lain-editor.frontend.graph-updates :as updates]
            [graph.lain-editor.frontend.reactive :as rx]
            [graph.lain-editor.frontend.state :as s]))

(defn attach! [scope id descriptor request slots]
  (let [entry (rx/derive! scope (fn [current results]
                                 (let [entry (get results id)]
                                   (when (and current (= current (:request entry))) entry))) request s/results)
        graph-state (rx/derive! scope (fn [pane entry current]
                                       {:visible (layers/visible? pane "topology")
                                        :entry entry :request current}) descriptor entry request)
        previous (atom nil)
        view-state (atom {})
        folded-values (atom #{})
        scope-trail (atom [])
        mounted (atom nil)
        clear! (fn [] (when @mounted ((get-in @mounted [:handle :dispose!]))) (reset! mounted nil))]
    (rx/watch! scope entry
               (fn [entry]
                 (let [result (:response entry)]
                   (if (and result (:valueGrid result) (not (:error entry)))
                     (value-grid/render! (get slots "result") "" (:valueGrid result) {} (fn [_] nil))
                     (dom/text! (get slots "result")
                                (cond (:error entry) (:error entry)
                                      result (:value result)
                                      :else "⌘Enter / Ctrl+Enter to run")))
                   (dom/text! (get slots "cell")
                              (if result (js/JSON.stringify (clj->js (:cell result)) nil 2) "")))))
    (rx/watch! scope graph-state
               (fn [{:keys [visible entry request]}]
                 (let [body (get slots "topology") result (:response entry) next-graph (:graph result)]
                   (cond
                     (not visible) (do (clear!) (dom/replace! body))
                      (:error entry) (do (clear!) (dom/replace! body (dom/element "div" "error-panel" (:error entry))))
                      (:replay result)
                      (let [key [(dissoc request :refresh) :replay]]
                        (if (= key (:key @mounted))
                          ((get-in @mounted [:handle :update!]) (:replay result))
                          (do (clear!)
                              (reset! mounted {:key key :handle (replay/mount! body (:replay result) request)}))))
                      (:stackScene result)
                      (let [scene (:stackScene result)
                            key [(dissoc request :refresh) :stack-scene (:root scene)]]
                        (when (not= key (:key @mounted))
                          (clear!)
                          (reset! mounted
                                  {:key key
                                   :handle (stack-scene/mount!
                                            body scene
                                            (fn [node signal chase?]
                                              (effects/api!
                                               (assoc (dissoc request :refresh)
                                                      :op "inspect" :parent nil
                                                      :node node :chase chase?)
                                               signal))
                                            #(swap! s/expansions assoc id %)
                                            (assoc request :view-state view-state)
                                            (constantly false))})))
                      next-graph
                     (let [key [(dissoc request :refresh) (updates/shape next-graph)]
                           view-key (select-keys request [:pane :display :parent])
                           changed (updates/changed-ids (when (= view-key (:view-key @previous)) (:graph @previous)) next-graph)]
                       (reset! previous {:view-key view-key :graph next-graph})
                       (if (= key (:key @mounted))
                         (when (not= next-graph (:graph @mounted))
                           ((get-in @mounted [:handle :update!]) next-graph changed)
                           (swap! mounted assoc :graph next-graph))
                         (let [_ (clear!)
                               canvas (dom/element "div" "semantic-scope-body")
                               _ (dom/replace! body)
                               _ (when (:parent request)
                                   (dom/append! body
                                                (dom/button "Back to enclosing scope"
                                                            #(let [parent (peek @scope-trail)]
                                                               (when (seq @scope-trail) (swap! scope-trail pop))
                                                               (swap! s/expansions assoc id parent)))))
                               _ (dom/append! body canvas)
                               handle (value-view/mount! canvas next-graph
                                                   (fn [node signal chase?]
                                                     (effects/api! (assoc (dissoc request :refresh) :op "inspect" :parent nil :node node :chase chase?) signal))
                                                   #(do (swap! scope-trail conj (:parent request))
                                                        (swap! s/expansions assoc id %))
                                                   (assoc request :view-state view-state) changed folded-values)]
                           (reset! mounted {:key key :graph next-graph :handle handle}))))
                     :else (do (clear!) (dom/replace! body))))))
    (rx/own! scope clear!)))
