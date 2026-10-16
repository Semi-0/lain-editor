(ns graph.lain-editor.frontend.replay
  "Playback of recorded graph data. Historical inspection never reads the live runtime."
  (:require [graph.lain-editor.frontend.dom :as dom]
            [graph.lain-editor.frontend.graph :as graph]
            [graph.lain-editor.frontend.graph-updates :as updates]
            [graph.lain-editor.frontend.reactive :as rx]
            [graph.lain-editor.frontend.replay-model :as model]))

(defn- historical-inspect [frame node _signal _chase]
  (if-let [entry (some #(if (= node (:id %)) % nil) (get-in frame [:graph :nodes]))]
    (js/Promise.resolve {:historical true :selected {:name (:label entry) :kind (:kind entry)
                                    :valueGrid (:valueGrid entry) :contentGrid (:contentGrid entry)
                                    :summary (:value entry)}
                         :value (str (:label entry) "\n" (:value entry))
                         :valueGrid (:valueGrid entry) :nodes (get-in frame [:graph :nodes])})
    (js/Promise.reject (js/Error. "Node is not present in this recorded observation"))))

(defn- playback! [scope state]
  (let [scheduled (atom nil)
        clear! #(do (if-let [timer (:id @scheduled)] (js/clearTimeout timer) nil)
                    (reset! scheduled nil))]
    (rx/watch! scope state
               (fn [current]
                 (if-let [key (model/playback-key current)]
                   (if (= key (:key @scheduled))
                     nil
                     (do (clear!)
                         (reset! scheduled
                                 {:key key :id (js/setTimeout
                                                #(do (clear!) (swap! state model/advance))
                                                (model/delay-ms current))})))
                   (do (clear!)
                       (if (:playing? current)
                         (swap! state assoc :playing? false)
                         nil)))))
    (rx/own! scope clear!)))

(defn- graph-view! [scope body state request]
  (let [mounted (atom nil) previous (atom nil)
        clear! #(do (if-let [current @mounted] ((:dispose! (:handle current))) nil) (reset! mounted nil))
        selected (rx/derive! scope #(get (:frames %) (:index %)) state)]
    (rx/watch! scope selected
               (fn [frame]
                 (let [next (:graph frame) changed (updates/changed-ids @previous next)]
                   (if next
                     (if (= (updates/shape next) (:shape @mounted))
                       (do ((get-in @mounted [:handle :clear-detail!]))
                           ((get-in @mounted [:handle :update!]) next changed))
                       (do (clear!)
                           (reset! mounted {:shape (updates/shape next)
                                            :handle (graph/mount! body next
                                                      (fn [node signal chase] (historical-inspect @selected node signal chase)) (fn [_] nil)
                                                      (assoc request :parent "replay" :recorded true) changed)})))
                     (do (clear!) (dom/replace! body (dom/element "span" "" "No observations in this time range"))))
                   (reset! previous next))))
    (rx/own! scope clear!)))

(defn mount! [body timeline request]
  (let [scope (rx/scope) root (dom/element "div" "replay-view")
        controls (dom/element "div" "replay-controls") canvas (dom/element "div" "replay-graph")
        state (rx/input! scope (model/initial (:frames timeline)))
        move! #(swap! state (fn [s] (assoc (model/seek s %) :playing? false)))
        back (dom/button "◁" #(move! (dec (:index @state))))
        play (dom/button "▶" #(swap! state (fn [s] (if (:playing? s) (assoc s :playing? false)
                                                     (assoc (if (= (:index s) (dec (count (:frames s))))
                                                              (model/seek s 0) s) :playing? true)))))
        next (dom/button "▷" #(move! (inc (:index @state))))
        slider (dom/element "input") label (dom/element "span" "replay-time")]
    (.setAttribute back "aria-label" "Previous observation")
    (.setAttribute play "aria-label" "Play recorded propagation")
    (.setAttribute next "aria-label" "Next observation")
    (.setAttribute slider "type" "range") (.setAttribute slider "min" "0")
    (.setAttribute slider "step" "1") (.setAttribute slider "aria-label" "Recorded observation")
    (set! (.-oninput slider) #(move! (js/parseInt (.. % -target -value) 10)))
    (dom/append! controls back play next slider label) (dom/append! root controls canvas)
    (dom/replace! body root)
    (rx/watch! scope state
               (fn [{:keys [frames index playing?]}]
                 (set! (.-disabled back) (zero? index))
                 (set! (.-disabled next) (>= index (dec (count frames))))
                 (set! (.-disabled play) (< (count frames) 2))
                 (set! (.-disabled slider) (empty? frames))
                 (.setAttribute slider "max" (str (max 0 (dec (count frames)))))
                 (dom/value! slider (str index)) (dom/text! play (if playing? "Ⅱ" "▶"))
                 (dom/text! label (str (get-in frames [index :elapsedMs] 0) " ms · "
                                       (if (seq frames) (inc index) 0) "/" (count frames)))
                 (.setAttribute root "data-replay-index" (str index))))
    (playback! scope state)
    (graph-view! scope canvas state request)
    {:dispose! #(rx/dispose! scope)
     :update! #(swap! state model/replace-frames (:frames %))}))
