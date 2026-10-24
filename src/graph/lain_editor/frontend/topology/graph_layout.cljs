(ns graph.lain-editor.frontend.topology.graph-layout
  "Cancelable transport for the pane's persistent Vijual flow and real milestones."
  (:require [graph.lain-editor.frontend.workspace.effects :as effects]
            [graph.lain-editor.frontend.ui.reactive :as rx]
            [graph.lain-editor.frontend.topology.viewport :as viewport]
            [graph.lain-editor.frontend.topology.model :as model]))

(defn attach! [scope request graph present! status]
  (let [active (atom nil) alive? (atom true) current (atom graph)
        settings (or (:layout-settings request) (atom {:budgetMs 1000 :spacing 1}))
        api! (or (:api! request) effects/api!)]
    (rx/own! scope #(do (reset! alive? false) (when @active (.abort @active))))
    (letfn [(send! [moves animate? refine?]
              (when @active (.abort @active))
              (let [controller (js/AbortController.)
                    nodes (mapv #(assoc (select-keys % [:id :label]) :size (viewport/node-size %)) (:nodes @current))]
                (reset! active controller)
                (status "Standard · relaxing…")
                (-> (api! (merge (select-keys request [:pane :generation :parent])
                                         (select-keys @current [:semanticScope])
                                         {:op "layout" :nodes nodes :edges (mapv (juxt :from :to) (:edges @current))
                                          :moves moves :animate animate? :refine refine?
                                          :budgetMs (:budgetMs @settings) :spacing (:spacing @settings)}) (.-signal controller))
                    (.then (fn [result]
                             (when (and @alive? (identical? controller @active))
                               (status (str "Standard · "
                                            (case (:stopReason result)
                                              "converged" "converged (≤0.05 units/step)"
                                              "budget" "budget exhausted · refine to continue"
                                              "clearance" "clearance adjusted · refine to continue"
                                              "positioned" "node positioned"
                                              "unchanged" "unchanged"
                                              "layout ready")
                                            " · " (:iterationsUsed result 0) " iterations"))
                               (present! result animate?))))
                    (.catch (fn [error]
                              (when (and @alive? (identical? controller @active) (not= "AbortError" (.-name error)))
                                (status (str "Layout: " (.-message error)))))))))]
      (send! [] (not (:recorded request)) false)
      {:move! #(send! % false false)
       :refine! (fn [milliseconds] (swap! settings assoc :budgetMs milliseconds) (send! [] true true))
       :spacing! (fn [factor] (swap! settings assoc :spacing factor) (send! [] true true))
       :update! (fn [graph]
                  (let [changed? (not= (model/layout-signature @current) (model/layout-signature graph))]
                    (reset! current graph)
                    (when changed? (send! [] false false))))})))
