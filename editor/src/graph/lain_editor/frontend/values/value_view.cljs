(ns graph.lain-editor.frontend.values.value-view
  "Owns value-folding controls and projects rows before calling the general graph view."
  (:require [clojure.string :as str]
            [graph.lain-editor.frontend.ui.dom :as dom]
            [graph.lain-editor.frontend.topology.graph :as graph]
            [graph.lain-editor.frontend.ui.reactive :as rx]
            [graph.lain-editor.frontend.values.value-folding :as folding]))

(defn- path-label [path]
  (str/join " / " (map (fn [[label ordinal]]
                         (case label
                           :item (str "item " ordinal)
                           :tail "tail"
                           (str (if (seq label) label "value")
                                (when (pos? ordinal) (str " [" (inc ordinal) "]"))))) path)))

(defn- fields [source context]
  (vec (for [node (:nodes source) :when (= "cell" (:kind node))
             section [:valueGrid :contentGrid]
             option (folding/options (get node section))]
         {:cell (:id node) :label (:label node) :section section :path (:path option)
          :key (folding/field-key context (:id node) section (:path option))})))

(defn mount!
  [body source inspect expand request changed folded]
  (let [scope (rx/scope) context [(:pane request) (:generation request)]
        current (atom source) cache (atom {})
        root (dom/element "div" "value-view-composition")
        menu (dom/element "details" "options value-fold-menu")
        popup (dom/element "div" "popover value-fold-popup")
        cells (dom/element "select") field-list (dom/element "div" "value-fold-fields")
        canvas (dom/element "div" "value-view-canvas")
        selected (atom nil) checks (atom []) signature (atom nil)
        projected (atom (folding/project-graph source @folded context true))
        handle (atom nil)
        visible-inspect
        (fn [id signal chase?]
          (let [key [id chase?]
                response (if-let [cached (get @cache key)]
                           (js/Promise.resolve cached)
                           (-> (inspect id signal chase?)
                               (.then (fn [result]
                                        (when-not (.-aborted signal) (swap! cache assoc key result))
                                        result))))]
            (-> response
                (.then #(update % :selected
                                (fn [node]
                                  (folding/project-node (assoc node :id id) @folded context true)))))))]
    (letfn [(update-checks! []
              (doseq [{:keys [check key]} @checks]
                (set! (.-checked check) (not (contains? @folded key)))))
            (render-fields! []
              (reset! checks [])
              (dom/replace! field-list)
              (doseq [section [:valueGrid :contentGrid]
                      :let [entries (filter #(and (= @selected (:cell %)) (= section (:section %)))
                                            (fields @current context))]
                      :when (seq entries)]
                (dom/append! field-list (dom/element "div" "layer-heading"
                                                    (if (= section :valueGrid) "Strongest" "Content")))
                (doseq [{:keys [key path label]} entries]
                  (let [row (dom/element "label" "value-fold-field") check (dom/element "input")
                        text (path-label path)]
                    (set! (.-type check) "checkbox")
                    (.setAttribute check "aria-label"
                                   (str "Unfold " label " " (name section) " " text))
                    (set! (.-onchange check)
                          #(swap! folded folding/set-unfolded key (.. % -target -checked)))
                    (dom/append! row check (dom/element "span" "" text))
                    (dom/append! field-list row)
                    (swap! checks conj {:check check :key key}))))
              (update-checks!))
            (update-menu! []
              (let [entries (fields @current context)
                    next-signature (mapv #(select-keys % [:cell :label :section :path]) entries)
                    nodes (vec (distinct (map #(select-keys % [:cell :label]) entries)))]
                (when (not= @signature next-signature)
                  (reset! signature next-signature)
                  (when-not (some #(= @selected (:cell %)) nodes)
                    (reset! selected (:cell (first nodes))))
                  (dom/replace! cells)
                  (doseq [{:keys [cell label]} nodes]
                    (let [option (dom/element "option" "" label)]
                      (set! (.-value option) cell) (dom/append! cells option)))
                  (dom/value! cells @selected)
                  (render-fields!))
                (set! (.-hidden menu) (empty? nodes))))
            (project! [changed]
              (let [next (folding/project-graph @current @folded context true)]
                (when (not= @projected next)
                  (reset! projected next)
                  ((:update! @handle) next changed))))]
      (.setAttribute cells "aria-label" "Cell value to fold or unfold")
      (.addEventListener root "valuefold"
            (fn [event]
              (.stopPropagation event)
              (let [{:keys [key expanded]} (.-detail event)]
                (swap! folded folding/set-unfolded key (not expanded)))))
      (set! (.-onchange cells) #(do (reset! selected (.. % -target -value)) (render-fields!)))
      (dom/append! popup
                   (dom/element "div" "layer-heading" "View only · checked fields are unfolded")
                   (dom/button "Fold all values" #(swap! folded into (map :key (fields @current context))))
                   (dom/button "Unfold all values" #(swap! folded folding/unfold-all context))
                   cells field-list)
      (dom/append! menu (dom/element "summary" "" "Value view") popup)
      (dom/append! root menu canvas) (dom/replace! body root)
      (reset! handle (graph/mount! canvas @projected visible-inspect expand request changed))
      (update-menu!)
      (let [key (random-uuid)]
        (add-watch folded key
                   (fn [_ _ _ _]
                     (project! #{})
                     (update-checks!)
                     (when-let [refresh (:refresh-detail! @handle)] (refresh))))
        (rx/own! scope #(remove-watch folded key)))
      (rx/own! scope #((:dispose! @handle)))
      {:dispose! #(rx/dispose! scope)
       :clear-detail! (:clear-detail! @handle)
       :update! (fn [source changed]
                  (when (not= source @current)
                    (reset! current source) (reset! cache {})
                    (update-menu!) (project! changed)))})))
