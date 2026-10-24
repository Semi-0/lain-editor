(ns graph.lain-editor.frontend.development.view
  "Pure premise-versioned development controls."
  (:require [graph.lain-editor.frontend.development.model :as model]))

(def specification-revision "stack-spec-2026-10-20-r9")

(defn view [pane pane-ui]
  (let [id (:id pane)
        feature (get pane-ui :development (model/view-model (model/initial (:development pane))))
        {:keys [enabled? message commit-count can-commit? busy? blocks]} feature]
    [:details.development-panel
     {:replicant/key [id :development]
      :open (boolean (get-in pane-ui [:panels :development]))
      :on {:toggle [[:panel/toggle id :development [:event/target.open]]]}}
     [:summary {:hidden true} "Incremental development"]
     [:div.development-status (or message "Ordinary isolated session")]
     [:pre.development-projection {:hidden (not (seq blocks))} (if (seq blocks) (pr-str blocks) "")]
     [:button {:type "button" :hidden enabled? :disabled busy?
               :on {:click [[:development/enable id]]}} "Enable premise-versioned mode"]
     [:button {:type "button" :hidden (not enabled?) :disabled (or busy? (not can-commit?))
               :on {:click [[:development/commit id]]}}
      (str "Commit current source · " commit-count " versions")]
     [:button {:type "button" :hidden (not enabled?) :disabled busy?
               :on {:click [[:development/disable id]]}} "Return to ordinary mode"]]))
