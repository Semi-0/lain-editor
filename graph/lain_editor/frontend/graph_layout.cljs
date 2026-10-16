(ns graph.lain-editor.frontend.graph-layout
  "Cancelable transport for the pane's persistent Vijual flow and real milestones."
  (:require [graph.lain-editor.frontend.effects :as effects]
            [graph.lain-editor.frontend.reactive :as rx]
            [graph.lain-editor.frontend.viewport :as viewport]))

(defn attach! [scope request graph present! status]
  (let [active (atom nil) alive? (atom true) current (atom graph)]
    (rx/own! scope #(do (reset! alive? false) (when @active (.abort @active))))
    (letfn [(send! [moves animate?]
              (when @active (.abort @active))
              (let [controller (js/AbortController.)
                    nodes (mapv #(assoc (select-keys % [:id :label]) :size (viewport/node-size %)) (:nodes @current))]
                (reset! active controller)
                (-> (effects/api! (merge (select-keys request [:pane :generation :parent])
                                         {:op "layout" :nodes nodes :edges (mapv (juxt :from :to) (:edges @current))
                                          :moves moves :animate animate?}) (.-signal controller))
                    (.then (fn [result]
                             (when (and @alive? (identical? controller @active))
                               (status (str "Vijual · revision " (:revision result)))
                               (present! result animate?))))
                    (.catch (fn [error]
                              (when (and @alive? (identical? controller @active) (not= "AbortError" (.-name error)))
                                (status (str "Layout: " (.-message error)))))))))]
      (status "Vijual · laying out…") (send! [] (not (:recorded request)))
      {:move! #(send! % false)
       :update! (fn [graph] (reset! current graph) (send! [] false))})))
