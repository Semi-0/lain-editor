(ns graph.lain-editor.frontend.topology.replay
  "Pure recorded observation playback controls."
  (:require [graph.lain-editor.frontend.topology.model :as model]
            [graph.lain-editor.frontend.values.value-view :as value-view]))

(defn view [vm id scene]
  (let [{:keys [frames index playing?]} (model/frame-state vm id)
        ancestors (get-in (model/pane-ui vm id) [:replay :ancestors])]
    [:div {:class "replay-view" :data-replay-index (str index)}
     [:div {:class "replay-controls"}
      [:button {:aria-label "Previous observation" :disabled (zero? index)
                :on {:click [[:replay/seek id (dec index)]]}} "◁"]
      [:button {:aria-label "Play recorded propagation" :disabled (< (count frames) 2)
                :on {:click [[:replay/toggle id]]}} (if playing? "Ⅱ" "▶")]
      [:button {:aria-label "Next observation" :disabled (>= index (dec (count frames)))
                :on {:click [[:replay/seek id (inc index)]]}} "▷"]
      [:input {:type "range" :min "0" :max (str (max 0 (dec (count frames)))) :step "1"
               :aria-label "Recorded observation" :disabled (empty? frames) :value (str index)
               :on {:input [[:replay/seek id [:event/target.value]]]}}]
      [:span {:class "replay-time"} (str (get-in frames [index :elapsedMs] 0) " ms · "
                                        (if (seq frames) (inc index) 0) "/" (count frames))]]
     [:div {:class "semantic-scope-trail"}
      (when (seq ancestors)
        [:div [:button {:on {:click [[:replay/leave-scope id]]}} "Back to enclosing scope"]
         [:span (str " Scope depth " (count ancestors))]])]
     [:div {:class "replay-graph"}
      (if scene (value-view/view vm id scene) [:span "No observations in this time range"])]]))
