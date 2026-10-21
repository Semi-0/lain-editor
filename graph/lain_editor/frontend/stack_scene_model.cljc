(ns graph.lain-editor.frontend.stack-scene-model)

(def default-camera {:x 36 :y 28 :scale 0.72 :depth-spacing 230})

(defn- edge-kind [edge]
  (let [kind (:kind edge)]
    (if (keyword? kind) (name kind) (str kind))))

(defn scene-summary
  "Presentation facts derived only from the immutable stack scene."
  [scene]
  (let [planes (:planes scene)
        edges (:edges scene)]
    {:planes (count planes)
     :expansions (count (filter #(= "expansion" (edge-kind %)) edges))
     :references (count (filter #(= "reference" (edge-kind %)) edges))
     :max-depth (reduce max 0 (map #(or (:depth %) 0) planes))}))

(defn summary-text [{:keys [planes expansions references max-depth]}]
  (str planes " semantic " (if (= 1 planes) "plane" "planes")
       " · " expansions " " (if (= 1 expansions) "expansion" "expansions")
       (when (pos? references)
         (str " · " references " " (if (= 1 references) "reference" "references")))
       " · depth " max-depth))

(defn plane-title [{:keys [identity depth]}]
  {:depth-label (str "D" (or depth 0))
   :identity-label (str identity)
   :aria-label (str "Stack plane depth " (or depth 0) ", " identity)})

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
