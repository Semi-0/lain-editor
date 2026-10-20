(ns graph.lain-editor.frontend.stack-scene-model)

(def default-camera {:x 36 :y 28 :scale 0.72 :depth-spacing 230})

(defn plane-position [{:keys [depth order]} depth-spacing]
  {:x (+ 32 (* order 410))
   :y (+ 28 (* depth depth-spacing))})

(defn layout [scene camera]
  (let [positions (into {}
                        (map (fn [plane]
                               [(:identity plane)
                                (plane-position plane (:depth-spacing camera))]))
                        (:planes scene))]
    {:positions positions
     :edges (vec
             (keep (fn [edge]
                     (when-let [from (get positions (:from edge))]
                       (when-let [to (get positions (:to edge))]
                         (assoc edge :from-point from :to-point to))))
                   (:edges scene)))}))

(defn zoom [camera factor]
  (update camera :scale #(-> (* % factor) (max 0.25) (min 1.8))))

(defn pan [camera dx dy]
  (-> camera (update :x + dx) (update :y + dy)))
