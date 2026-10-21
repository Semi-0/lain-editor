(ns graph.lain-editor.frontend.stack-scene-model)

(def default-camera {:x 36 :y 28 :scale 0.72 :depth-spacing 230})

(defn plane-position [{:keys [depth order]} depth-spacing]
  {:x (+ 32 (* order 410))
   :y (+ 28 (* depth depth-spacing))})

(defn- plane-center [position]
  (when position
    {:x (+ 380 (:x position))
     :y (+ 170 (:y position))}))

(defn- plane-entry [position]
  (when position
    {:x (+ 380 (:x position))
     :y (:y position)}))

(defn layout
  ([scene camera] (layout scene camera {}))
  ([scene camera source-anchors]
   (let [positions (into {}
                         (map (fn [plane]
                                [(:identity plane)
                                 (plane-position plane (:depth-spacing camera))]))
                         (:planes scene))]
     {:positions positions
      :edges (vec
              (keep (fn [edge]
                      (when-let [fallback-from (plane-center
                                                (get positions (:from edge)))]
                        (when-let [to (plane-entry (get positions (:to edge)))]
                          (assoc edge
                                 :from-point (get source-anchors
                                                  [(:from edge) (:source-node edge)]
                                                  fallback-from)
                                 :to-point to))))
                    (:edges scene)))})))

(defn zoom [camera factor]
  (update camera :scale #(-> (* % factor) (max 0.25) (min 1.8))))

(defn pan [camera dx dy]
  (-> camera (update :x + dx) (update :y + dy)))
