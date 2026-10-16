(ns graph.lain-editor.frontend.replay-model)

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
