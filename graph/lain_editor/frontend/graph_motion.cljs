(ns graph.lain-editor.frontend.graph-motion
  "Replay solver coordinates independently of the mounted graph's camera."
  (:require [graph.lain-editor.frontend.viewport :as viewport]
            [graph.lain-editor.frontend.reactive :as rx]))

(defn point-map [nodes]
  (viewport/clear-points (into {} (map (fn [node] [(:id node) (viewport/point node)])) nodes)))

(defn interpolate [before after t]
  (viewport/clear-points (into {} (map (fn [[id target]]
                  (let [source (get before id target)]
                    [id (assoc target :x (+ (:x source) (* t (- (:x target) (:x source))))
                                      :y (+ (:y source) (* t (- (:y target) (:y source)))))]))) after)))

(defn plan [frames target]
  {:samples (mapv #(point-map (:nodes %)) frames) :target target})

(defn- replay! [canvas frame-id frames plan finish!]
  (let [started (js/performance.now) samples (:samples plan) count (count samples)]
    (letfn [(step [now]
              (let [elapsed (max 0 (- now started)) settling? (>= elapsed 1100)
                    progress (min 1 (/ elapsed 1100))
                    index (min (max 0 (- count 2)) (js/Math.floor (* progress (dec count))))
                    t (- (* progress (dec count)) index)
                    points (if settling?
                             (interpolate (last samples) (:target plan) (min 1 (/ (- elapsed 1100) 220)))
                             (interpolate (nth samples index) (nth samples (min (dec count) (inc index))) t))]
                (.setAttribute canvas "data-layout-phase" (if settling? "compact" "stress"))
                (.setAttribute canvas "data-layout-iteration" (str (:iteration (nth frames (if settling? (dec count) index)))))
                ((:render! plan) points)
                (if (>= elapsed 1320)
                  (finish!)
                  (reset! frame-id (js/requestAnimationFrame step)))))]
      (reset! frame-id (js/requestAnimationFrame step)))))

(defn mount!
  ([scope canvas points layout-points]
   (mount! scope canvas points layout-points viewport/clear-points))
  ([scope canvas points layout-points constrain]
  (let [frame-id (atom nil)
        cancel! (fn [] (when @frame-id (js/cancelAnimationFrame @frame-id)) (reset! frame-id nil))
        finish! (fn [] (cancel!) (.setAttribute canvas "data-layout-phase" "settled")
                      (reset! points (constrain @layout-points)))]
    (rx/own! scope cancel!)
    {:active? #(some? @frame-id)
     :finish! finish!
     :play! (fn [result animate?]
              (cancel!)
              (reset! layout-points (point-map (:nodes result)))
              (let [frames (:frames result)
                    reduced? (.-matches (js/matchMedia "(prefers-reduced-motion: reduce)"))]
                (.setAttribute canvas "data-layout-samples" (str (count frames)))
                (.setAttribute canvas "data-layout-iteration" (str (:iterationsUsed result 0)))
                (if (and animate? (seq (:nodes result)) (> (count frames) 1) (not reduced?))
                  (let [trajectory (plan frames @layout-points)
                        render! #(reset! points (constrain %))]
                    (render! (first (:samples trajectory)))
                    (replay! canvas frame-id frames (assoc trajectory :render! render!) finish!))
                  (finish!))))})))
