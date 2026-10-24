(ns graph.lain-editor.frontend.values.value-view
  "Pure value-folding controls; projection never changes observed values."
  (:require [clojure.string :as str]
            [graph.lain-editor.frontend.values.value-folding :as folding]
            [graph.lain-editor.frontend.topology.model :as model]
            [graph.lain-editor.frontend.topology.graph :as graph]))

(defn path-label [path]
  (str/join " / " (map (fn [[label ordinal]]
                         (case label :item (str "item " ordinal) :tail "tail"
                           (str (if (seq label) label "value")
                                (when (pos? ordinal) (str " [" (inc ordinal) "]"))))) path)))

(defn controls [vm id source]
  (let [entries (model/fields vm id source) nodes (vec (distinct (map #(select-keys % [:cell :label]) entries)))
        ui (model/pane-ui vm id) selected (if (some #(= (:selected-cell ui) (:cell %)) nodes)
                                         (:selected-cell ui) (:cell (first nodes)))]
    (when (seq nodes)
      [:details {:class ["options" "value-fold-menu"] :open (:fold-menu-open? ui false)
                 :on {:toggle [[:value/menu id [:event/native]]]}}
       [:summary "Value view"]
       [:div {:class ["popover" "value-fold-popup"]}
        [:div {:class "layer-heading"} "View only · checked fields are unfolded"]
        [:button {:on {:click [[:value/fold-all id]]}} "Fold all values"]
        [:button {:on {:click [[:value/unfold-all id]]}} "Unfold all values"]
        [:select {:aria-label "Cell value to fold or unfold" :value selected
                  :on {:change [[:value/select-cell id [:event/target.value]]]}}
         (map (fn [{:keys [cell label]}] [:option {:replicant/key cell :value cell} label]) nodes)]
        [:div {:class "value-fold-fields"}
         (map (fn [section]
                 (let [fields (filter #(and (= selected (:cell %)) (= section (:section %))) entries)]
                   (when (seq fields)
                     [:div {:replicant/key section}
                      [:div {:class "layer-heading"} (if (= section :valueGrid) "Strongest" "Content")]
                      (map (fn [{:keys [key path label]}]
                              [:label {:replicant/key key :class "value-fold-field"}
                               [:input {:type "checkbox" :checked (not (contains? (:folded ui #{}) key))
                                        :aria-label (str "Unfold " label " " (name section) " " (path-label path))
                                        :on {:change [[:value/unfold id key [:event/target.checked]]]}}]
                               [:span (path-label path)]]) fields)])))
               [:valueGrid :contentGrid])]]])))

(defn view [vm id scene]
  [:div {:class "value-view-composition" :style {:flex "1 1 0" :min-height 0}}
   (controls vm id (:source scene))
   [:div {:class "value-view-canvas" :style {:flex "1 1 0" :min-height 0}} (graph/view id scene)]])
