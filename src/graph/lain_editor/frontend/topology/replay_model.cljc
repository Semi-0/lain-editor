(ns graph.lain-editor.frontend.topology.replay-model)

(defn initial [frames] {:frames (vec frames) :index 0 :playing? false})

(defn seek [state index]
  (assoc state :index (max 0 (min (max 0 (dec (count (:frames state)))) index))))

(defn replace-frames [state frames]
  (let [selected (get-in state [:frames (:index state) :sequence])
        index (first (keep-indexed #(if (= selected (:sequence %2)) %1 nil) frames))]
    (seek (assoc state :frames (vec frames)) (or index (:index state)))))

(defn advance [state]
  (if (< (:index state) (dec (count (:frames state))))
    (update state :index inc)
    (assoc state :playing? false)))

(defn delay-ms [state]
  (let [index (:index state) frames (:frames state)]
    (max 1 (- (get-in frames [(inc index) :elapsedMs] 0)
              (get-in frames [index :elapsedMs] 0)))))

(defn playback-key [{:keys [frames index playing?] :as state}]
  (if (and playing? (< index (dec (count frames))))
    [index (get-in frames [index :sequence]) (get-in frames [(inc index) :sequence]) (delay-ms state)]
    nil))

(defn caption-amb-replay [{:keys [nodes edges] :as graph}]
  (let [by-id (into {} (map (juxt :id identity)) nodes)
        inputs (group-by :to edges)
        producers (fn [node] (map #(by-id (:from %)) (inputs (:id node))))
        from? (fn [node kind label]
                (some #(and (= kind (:kind %)) (= label (:label %))) (producers node)))
        choice (some #(when (and (= "chosen" (:label %)) (from? % "propagator" "amb")) %) nodes)
        check (some #(when (and (= "valid" (:label %))
                                (some (fn [op] (and (= "=" (:label op))
                                                    (some #{choice} (producers op)))) (producers %))) %) nodes)
        requirement (some #(when (and (= "require" (:label %)) (some #{check} (producers %))) %) nodes)
        satisfied? (and (= "true" (:value check))
                        (some #(and (some #{requirement} (producers %))
                                    (some (fn [row] (and (= "status" (:key row)) (= ":satisfied" (:value row))))
                                          (:valueGrid %))) nodes))]
    (if (and choice check)
      (assoc graph :result-ids #{(:id choice) (:id check)}
             :nodes (mapv (fn [node]
                            (cond
                              (= (:id choice) (:id node))
                              (assoc node :caption (if satisfied? "Search result (chosen)" "Candidate (chosen)"))
                              (and (= "cell" (:kind node)) (= "RESULT" (:label node)))
                              (let [origins (producers node)]
                                (assoc node :caption (if (= 1 (count origins))
                                                       (str (:label (first origins)) " output")
                                                       "Expression value")))
                              :else node)) nodes))
      graph)))
