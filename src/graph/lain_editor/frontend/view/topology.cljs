(ns graph.lain-editor.frontend.view.topology
  "Pure output-layer entrypoints. Every value comes from an explicit snapshot."
  (:require [graph.lain-editor.frontend.topology.model :as model]
            [graph.lain-editor.frontend.topology.replay :as replay]
            [graph.lain-editor.frontend.topology.stack-scene :as stack-scene]
            [graph.lain-editor.frontend.values.value-view :as value-view]
            [graph.lain-editor.frontend.values.value-grid :as grid]
            [graph.lain-editor.frontend.values.value-folding :as folding]))

(defn- decorate-scene [vm id scene]
  (let [detail (get-in vm [:remote :topology id :details (:key scene)])
        valid? (= [(:frame scene) (:observation scene)] (:owner detail))]
    (assoc scene :detail (when valid?
                          (update (assoc detail :runtime-open? (get-in scene [:ui :runtime-details (:node detail)] false)) :result
                                  (fn [result]
                                    (when result
                                      (update result :selected
                                              #(folding/project-node (assoc % :id (:node detail))
                                                                     (:folded (model/pane-ui vm id) #{})
                                                                     (model/context vm id) true))))))
           :changed-node-ids (get-in vm [:remote :topology id :changed-node-ids] #{})
           :update-counts (get-in vm [:remote :topology id :update-counts (:key scene)] {}))))

(defn view [vm id]
  (let [entry (model/entry vm id) output (:response entry)
        scenes (mapv #(decorate-scene vm id %) (model/scenes vm id))
        request (:request entry)]
    (cond
      (:error entry) [:div {:class "error-panel"} (:error entry)]
      (:replay output) (replay/view vm id (first scenes))
      (:stackScene output) (stack-scene/view vm id (:stackScene output) scenes)
      (seq scenes)
      [:div {:class "semantic-scope-body" :style {:display "flex" :flex-direction "column" :min-height 0}}
       (when (:parent request)
         [:button {:on {:click [[:graph/back id]]}} "Back to enclosing scope"])
       (value-view/view vm id (first scenes))]
      :else nil)))

(defn result-view [vm id]
  (let [entry (model/entry vm id) output (:response entry)]
    [:div {:class "value-view"}
     (cond
       (:error entry) (:error entry)
       (:valueGrid output) (grid/view "" (:valueGrid output) {} [id nil])
       output (:value output)
       :else nil)]))

(defn cell-view [vm id]
  [:pre {:class "cell-view"}
   (when-let [output (model/response vm id)] (js/JSON.stringify (clj->js (:cell output)) nil 2))])
