(ns graph.lain-editor.frontend.effects
  "HTTP, source commits, workspace replay, and browser file effects."
  (:refer-clojure :exclude [run!])
  (:require [javelin.core :refer-macros [dosync]]
            [graph.lain-editor.frontend.dom :as dom]
            [graph.lain-editor.frontend.desktops :as desktops]
            [graph.lain-editor.frontend.model :as model]
            [graph.lain-editor.frontend.state :as s]
            [graph.lain-editor.workspace :as workspace]
            [graph.lain-editor.layers :as layers]))

(def storage-key "lain-tiled-editor-v1")

(defn api!
  ([command] (api! command nil))
  ([command signal]
   (-> (js/fetch "/api" #js {:method "POST" :signal signal
                             :headers #js {"Content-Type" "application/json"}
                             :body (js/JSON.stringify (clj->js command))})
       (.then #(.json %))
       (.then (fn [body]
                (if (.-ok body)
                  (js->clj (.-result body) :keywordize-keys true)
                  (throw (js/Error. (.-error body)))))))))

(defn operation! [id action]
  (if @s/busy?
    (js/Promise.resolve nil)
    (do
      (dosync (reset! s/busy? true) (reset! s/pending-id id))
      (-> (js/Promise.resolve nil)
          (.then (fn [_] (action)))
          (.catch (fn [error]
                    (reset! s/message (.-message error))
                    (when id (swap! s/errors assoc id (.-message error)))))
          (.finally #(dosync (reset! s/busy? false) (reset! s/pending-id nil)))))))

(defn run! [id op]
  (let [pane (model/pane @s/workspace id)
        extensions (model/extension-sources pane)
        source (case op "run" (:source pane) "extend" (:extensionDraft pane)
                     (throw (js/Error. "Unknown source operation")))]
    (operation!
     id
     #(-> (api! {:op op :pane id :source source :extensions (if (= op "run") extensions []) :generation (get @s/generations id)})
          (.then (fn [result]
                   (dosync
                    (s/change-pane! id
                                    (fn [p]
                                      (case op
                                        "run" (model/committed-source p source extensions)
                                        "extend" (model/extended-source p source)
                                        (throw (js/Error. "Unknown source operation")))))
                    (when-let [mode (:displayMode result)]
                      (s/change-pane! id layers/program-output mode))
                    (swap! s/errors dissoc id)
                    (swap! s/expansions dissoc id)
                    (swap! s/generations assoc id (:generation result))
                    (reset! s/message "Runtime updated · each block has an independent session"))))))))

(defn restore! [input]
  (let [document (workspace/upgrade input)]
  (operation!
   nil
   #(do
      (reset! s/message "Rebuilding saved programs…")
      (-> (api! {:op "restore" :workspace document})
          (.then (fn [result]
                   (dosync
                    (reset! s/results {}) (reset! s/errors {}) (reset! s/expansions {})
                    (reset! s/workspace
                            (reduce (fn [doc [id mode]]
                                      (model/update-pane doc (name id) layers/program-output mode))
                                    document (:displayModes result)))
                    (reset! s/selected-id (desktops/selected-id document))
                    (reset! s/generations (into {} (map (fn [[k v]] [(name k) v])) (:generations result)))
                    (reset! s/message "Workspace restored · programs replayed in isolated sessions")))))))))

(defn add-pane! [id axis]
  (when-not @s/busy?
    (let [fresh (model/new-pane)]
      (dosync
       (if axis
         (swap! s/workspace model/split-pane id axis fresh)
         (swap! s/workspace model/spawn-pane fresh))
       (s/select-pane! (:id fresh)))
      (dom/focus-pane! (:id fresh)))))

(defn close-pane! [id]
  (let [next-document (model/close-pane @s/workspace id)]
    (operation! id
                #(-> (api! {:op "close" :pane id})
                     (.then (fn [_]
                              (dosync
                               (reset! s/workspace next-document)
                               (reset! s/selected-id (desktops/selected-id next-document))
                               (doseq [cell [s/generations s/errors s/results s/expansions s/refreshes]]
                                 (swap! cell dissoc id)))
                              (reset! s/message "Block closed · remaining sessions preserved")
                              (dom/focus-pane! @s/selected-id)))))))

(defn load-file! [id file]
  (when file
    (-> (.text file)
        (.then (fn [source]
                 (s/change-pane! id assoc :source source :filename (.-name file) :title (.-name file))
                 (reset! s/message "File loaded · ⌘Enter / Ctrl+Enter runs the selected block")))
        (.catch #(reset! s/message (.-message %))))))

(defn save! []
  (let [blob (js/Blob. #js [(js/JSON.stringify (clj->js @s/workspace) nil 2)]
                       #js {:type "application/json"})
        url (.createObjectURL js/URL blob)
        link (dom/element "a")]
    (set! (.-href link) url) (set! (.-download link) "lain-workspace.json")
    (.click link)
    (js/setTimeout #(.revokeObjectURL js/URL url) 1000)
    (reset! s/message "Workspace JSON saved")))

(defn persist! [document]
  (try
    (.setItem js/localStorage storage-key (js/JSON.stringify (clj->js document)))
    (catch :default error
      (reset! s/message (str "Local draft storage: " (.-message error) ". Save JSON to keep your work.")))))

(defn perform! [{:keys [action target enabled]}]
  (when enabled
    (case action
      :new-workspace (restore! (model/initial-workspace))
      :new-desktop (dosync
                     (swap! s/workspace desktops/add-desktop (str (random-uuid)) (model/new-pane))
                     (reset! s/selected-id (desktops/selected-id @s/workspace)))
      :switch-desktop (s/switch-desktop! target)
      :run (run! target "run")
      :load (.click (.querySelector (dom/pane-root target) "input[type=file]"))
      :split-row (add-pane! target "row")
      :split-column (add-pane! target "column")
      :spawn (add-pane! target nil)
      :close (close-pane! target)
      (:value :cell :graph) (s/set-display! target :mode (name action))
      (:upstream :downstream :both) (s/set-display! target :direction (name action))
      :layer-settings (set! (.-open (.querySelector (dom/pane-root target) ".layer-panel")) true)
      :fit-graph (.dispatchEvent (dom/pane-root target) (js/Event. "lain-fit-graph"))
      :root-graph (dosync (swap! s/expansions dissoc target) (s/refresh! target))
      :refresh (dosync (swap! s/expansions dissoc target) (s/refresh! target))
      :edit-extension (let [details (.querySelector (dom/pane-root target) ".extension")]
                        (set! (.-open details) true) (.focus (.querySelector details "textarea")))
      :extend (run! target "extend")
      :save (save!)
      :open (.click (.getElementById js/document "open"))
      (throw (js/Error. "Unknown palette command")))))
