(ns graph.lain-editor.frontend.topology.inspection
  "Cancelable per-pane sampling. Poll ticks never invalidate an in-flight sample."
  (:require [graph.lain-editor.frontend.workspace.effects :as effects]
            [graph.lain-editor.frontend.ui.reactive :as rx]
            [graph.lain-editor.frontend.workspace.state :as s]))

(defn attach!
  ([scope id request] (attach! scope id request effects/api!))
  ([scope id request api!]
  (let [active (atom nil) alive? (atom true)
        cancel! (fn []
                  (when-let [controller (:controller @active)] (.abort controller))
                  (reset! active nil))]
    (letfn [(start! [value]
              (when (and (effects/connected?) value @alive? (nil? @active))
                (let [token (random-uuid) controller (js/AbortController.)
                      current? #(and @alive? (= token (:token @active)) (= value @request))]
                  (reset! active {:token token :controller controller})
                  (-> (api! (assoc (dissoc value :refresh :autoRefresh) :op "inspect") (.-signal controller))
                      (.then (fn [response]
                               (when (current?)
                                 (swap! s/results assoc id {:request value :response response}))))
                      (.catch (fn [error]
                                (when (and (current?) (not (effects/cancelled? error)))
                                  (swap! s/results assoc id {:request value :error (.-message error)}))))
                      (.finally (fn []
                                  (when (= token (:token @active)) (reset! active nil))))))))]
      (rx/watch! scope request (fn [value] (cancel!) (start! value)))
      (rx/watch! scope s/tick
                 (fn [_]
                   (when (not= false (:autoRefresh @request))
                     (start! @request))))
      (rx/own! scope #(do (reset! alive? false) (cancel!)))))))
