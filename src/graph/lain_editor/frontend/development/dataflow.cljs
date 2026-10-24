(ns graph.lain-editor.frontend.development.dataflow
  "Serialized feature transitions. Actual applications inject their owned state lens."
  (:require [graph.lain-editor.frontend.development.effects :as effects]
            [graph.lain-editor.frontend.development.model :as model]
            [graph.lain-editor.frontend.ui.reactive :as rx]))

(defn create! [scope {:keys [pane portable-model persist-model! state-cell]}]
  (let [state (or state-cell (rx/input! scope (model/initial portable-model)))
        view-model (rx/derive! scope model/view-model state)
        alive? (atom true) serial (atom 0) cancel-pending (atom nil)
        dispatch-ref (atom nil)
        dispatch! (fn [event]
                    (when @alive?
                      (let [previous-model (:model @state)
                            {next-state :state declared :effects} (model/transition @state event)]
                        (reset! state next-state)
                        (when (and persist-model! (not= previous-model (:model next-state)))
                          (persist-model! (:model next-state)))
                        (doseq [effect declared]
                          (when-let [cancel @cancel-pending] (cancel))
                          (let [request (swap! serial inc)
                                accept! (fn [result]
                                          (when (and @alive? (= request @serial))
                                            (@dispatch-ref result)))
                                cancel (effects/interpret! pane effect accept!)]
                            (reset! cancel-pending (when (fn? cancel) cancel)))))))]
    (reset! dispatch-ref dispatch!)
    (rx/own! scope #(do (reset! alive? false)
                        (swap! serial inc)
                        (when-let [cancel @cancel-pending] (cancel))))
    {:state state :view-model view-model :dispatch! dispatch!}))
