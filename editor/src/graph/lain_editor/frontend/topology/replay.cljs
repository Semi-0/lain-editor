(ns graph.lain-editor.frontend.topology.replay
  "Playback of recorded graph data. Historical inspection never reads the live runtime."
  (:require [graph.lain-editor.frontend.ui.dom :as dom]
            [graph.lain-editor.frontend.workspace.effects :as effects]
            [graph.lain-editor.frontend.values.value-view :as value-view]
            [graph.lain-editor.frontend.topology.graph-updates :as updates]
            [graph.lain-editor.frontend.ui.reactive :as rx]
            [graph.lain-editor.frontend.topology.replay-model :as model]))

(defn- historical-inspect [visible node _signal _chase]
  (if-let [entry (some #(if (= node (:id %)) % nil) (:nodes visible))]
    (js/Promise.resolve {:historical true :selected {:name (:label entry) :kind (:kind entry)
                                    :valueGrid (:valueGrid entry) :contentGrid (:contentGrid entry)
                                    :summary (:value entry)}
                         :value (str (:label entry) "\n" (:value entry))
                         :valueGrid (:valueGrid entry) :nodes (:nodes visible)})
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

(defn- graph-view! [scope body trail state request]
  (let [mounted (atom nil) previous (atom nil) visible (atom nil)
        ancestors (atom []) view-state (atom {}) folded-values (atom #{})
        clear! #(do (if-let [current @mounted] ((:dispose! (:handle current))) nil) (reset! mounted nil))
        selected (rx/derive! scope #(get (:frames %) (:index %)) state)]
    (letfn [(show! [next changed]
              (reset! visible next)
              (if next
                (if (= [(:semanticScope next) (updates/shape next)] (:shape @mounted))
                  (do ((get-in @mounted [:handle :clear-detail!]))
                      ((get-in @mounted [:handle :update!]) next changed))
                  (do (clear!)
                      (reset! mounted
                              {:shape [(:semanticScope next) (updates/shape next)]
                               :handle
                               (value-view/mount!
                                body next
                                (fn [node signal chase]
                                  (historical-inspect @visible node signal chase))
                                open-scope!
                                (assoc request
                                       :view-state view-state
                                       :parent (:semanticScope next)
                                       :recorded true)
                                changed folded-values)})))
                (do (clear!)
                    (dom/replace! body
                                  (dom/element "span" ""
                                               "No observations in this time range"))))
              (dom/replace! trail)
              (when (seq @ancestors)
                (dom/append! trail
                             (dom/button "Back to enclosing scope"
                                         #(let [parent (peek @ancestors)]
                                            (swap! ancestors pop)
                                            (show! parent #{})))
                             (dom/element "span" "" (str " Scope depth " (count @ancestors))))))
            (open-scope! [node]
              (when-let [observation (:semanticObservation @visible)]
                (-> (effects/api!
                     (assoc (dissoc request :refresh)
                            :op "inspect"
                            :parent node
                            :recordedObservation observation))
                    (.then (fn [response]
                             (swap! ancestors conj @visible)
                             (show! (:graph response) #{})))
                    (.catch #(dom/text! trail (.-message %))))))]
      (rx/watch! scope selected
                 (fn [frame]
                   (let [next (:graph frame)
                         changed (updates/changed-ids (:graph @previous) next)]
                     (when (not= frame @previous)
                       (reset! ancestors [])
                       (show! next changed))
                     (reset! previous frame)))))
    (rx/own! scope clear!)))

(defn mount! [body timeline request]
  (let [scope (rx/scope) root (dom/element "div" "replay-view")
        controls (dom/element "div" "replay-controls")
        trail (dom/element "div" "semantic-scope-trail") canvas (dom/element "div" "replay-graph")
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
    (dom/append! controls back play next slider label) (dom/append! root controls trail canvas)
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
    (graph-view! scope canvas trail state request)
    {:dispose! #(rx/dispose! scope)
     :update! #(swap! state model/replace-frames (:frames %))}))
