(ns graph.lain-editor.frontend.topology.model
  "Pure projection of backend observations and authoritative graph interaction state."
  (:require [graph.lain-editor.frontend.workspace.model :as workspace]
            [graph.lain-editor.frontend.values.value-folding :as folding]
            [graph.lain-editor.frontend.topology.viewport :as viewport]
            [graph.lain-editor.frontend.topology.graph-motion :as motion]
            [graph.lain-editor.frontend.topology.replay-model :as replay]
            [graph.lain-editor.frontend.topology.stack-scene-model :as stack]))

(def default-camera {:x 380 :y 165 :width 760 :height 330 :scale 1})
(def default-settings {:budgetMs 1000 :spacing 1})

(defn pane-ui [vm id] (get-in vm [:panes-ui id :topology] {}))
(defn entry [vm id]
  (let [entry (get-in vm [:results id])]
    (when (= (get-in vm [:generations id]) (get-in entry [:request :generation])) entry)))
(defn response [vm id] (:response (entry vm id)))
(defn frame-state [vm id]
  (let [frames (get-in (response vm id) [:replay :frames])
        state (merge (replay/initial frames) (:replay (pane-ui vm id)))]
    (if-let [selected (when (contains? state :sequence)
                        (some (fn [[index frame]] (when (= (:sequence state) (:sequence frame)) index))
                              (map-indexed vector frames)))]
      (replay/seek state selected)
      (replay/seek state (:index state)))))
(defn context [vm id] [id (get-in vm [:generations id])])
(defn graph-key [vm id graph] (conj (context vm id) (:semanticScope graph)))

(defn layout-signature [graph]
  {:scope (:semanticScope graph)
   :nodes (into {} (map (fn [node] [(:id node) [(:kind node) (viewport/node-size node)]])) (:nodes graph))
   :edges (set (map #(select-keys % [:from :to :bidirectional]) (:edges graph)))})

(defn source-scenes [vm id]
  (let [output (response vm id) ui (pane-ui vm id)
        frames (frame-state vm id) frame (get (:frames frames) (:index frames))
        recorded? (some? (:replay output))
        nested (get-in vm [:remote :topology id :recorded-scope])
        nested-valid? (= [(:generation (:request (entry vm id))) (:sequence frame)] (:owner nested))
        graph (if recorded? (if nested-valid? (:graph nested) (:graph frame)) (:graph output))
        scene (fn [graph]
                {:key (graph-key vm id graph) :source graph :recorded? recorded?
                 :frame (:sequence frame) :observation (:semanticObservation graph)
                 :request (cond-> (dissoc (:request (entry vm id)) :refresh)
                            recorded? (assoc :recorded true :parent (:semanticScope graph)))})]
    (if-let [stack-scene (:stackScene output)]
      (mapv (fn [plane] (assoc (scene (:graph plane)) :plane plane)) (:planes stack-scene))
      (if graph [(scene graph)] []))))

(defn scene [vm id source-scene]
  (let [key (:key source-scene) ui (get-in (pane-ui vm id) [:scenes key] {})
        graph (folding/project-graph (:source source-scene) (:folded (pane-ui vm id) #{}) (context vm id) true)
        signature (layout-signature graph)
        layout (get-in vm [:remote :topology id :layouts key])
        solved? (= signature (:signature layout))
        target (merge (viewport/initial-points (:nodes graph))
                      (when (:result layout) (motion/point-map (get-in layout [:result :nodes]))))
        samples (when solved? (mapv #(motion/point-map (:nodes %)) (get-in layout [:result :frames])))
        progress (:animation-progress ui 1)
        animated (if (and (< progress 1) (> (count samples) 1))
                   (let [p (* (min 1 (/ progress (/ 1100 1320))) (dec (count samples)))
                         index (min (- (count samples) 2) (js/Math.floor p))]
                     (if (> progress (/ 1100 1320))
                       (motion/interpolate (last samples) target (/ (- progress (/ 1100 1320)) (/ 220 1320)))
                       (motion/interpolate (nth samples index) (nth samples (inc index)) (- p index))))
                   target)
        source-positions (merge animated (:position-overrides ui))
        positions (reduce (fn [points node]
                            (let [[width height] (viewport/node-size node)]
                              (assoc points (:id node)
                                     (assoc (get source-positions (:id node))
                                            :width width :height height))))
                          {} (:nodes graph))
        drag (:drag ui)
        points (if (and (:node drag) (:position drag))
                 (viewport/clear-points (update positions (:node drag) merge (select-keys (:position drag) [:x :y])) (:node drag))
                 (viewport/clear-points positions))
        camera (or (:camera ui) (viewport/fit-view points default-camera))]
    (assoc source-scene :graph graph :signature signature :points points :camera camera
           :settings (merge default-settings (:settings ui)) :layout layout :ui ui)))

(defn scenes [vm id] (mapv #(scene vm id %) (source-scenes vm id)))
(defn find-scene [vm id key] (some #(when (= key (:key %)) %) (scenes vm id)))
(defn historical-inspection [graph id]
  (when-let [node (some #(when (= id (:id %)) %) (:nodes graph))]
    {:historical true :selected (assoc node :name (:label node) :summary (:value node))
     :value (str (:label node) "\n" (:value node)) :valueGrid (:valueGrid node) :nodes (:nodes graph)}))
(defn fields [vm id graph]
  (vec (for [node (:nodes graph) :when (= "cell" (:kind node))
             section [:valueGrid :contentGrid] option (folding/options (get node section))]
         {:cell (:id node) :label (:label node) :section section :path (:path option)
          :key (folding/field-key (context vm id) (:id node) section (:path option))})))
(defn stack-camera [vm id] (merge stack/default-camera (:stack-camera (pane-ui vm id))))
