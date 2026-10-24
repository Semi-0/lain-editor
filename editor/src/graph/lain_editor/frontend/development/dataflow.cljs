(ns graph.lain-editor.frontend.development.dataflow
  "Feature-local Javelin dataflow and serialized interaction reduction."
  (:require [graph.lain-editor.frontend.development.effects :as effects]
            [graph.lain-editor.frontend.development.model :as model]
            [graph.lain-editor.frontend.ui.reactive :as rx]))

(defn create! [scope {:keys [pane portable-model persist-model!]}]
  (let [state (rx/input! scope (model/initial portable-model))
        view-model (rx/derive! scope model/view-model state)
        dispatch-ref (atom nil)
        dispatch! (fn [event]
                    (let [{next-state :state declared :effects}
                          (model/transition @state event)
                          previous-model (:model @state)]
                      (reset! state next-state)
                      (when (not= previous-model (:model next-state))
                        (persist-model! (:model next-state)))
                      (doseq [effect declared]
                        (effects/interpret! pane effect @dispatch-ref))))]
    (reset! dispatch-ref dispatch!)
    {:state state :view-model view-model :dispatch! dispatch!}))
