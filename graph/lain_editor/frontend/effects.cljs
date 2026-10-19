(ns graph.lain-editor.frontend.effects
  "HTTP, source commits, workspace replay, and browser file effects."
  (:refer-clojure :exclude [run!])
  (:require [javelin.core :refer-macros [dosync]]
            [graph.lain-editor.frontend.dom :as dom]
            [graph.lain-editor.frontend.desktops :as desktops]
            [graph.lain-editor.frontend.model :as model]
            [graph.lain-editor.frontend.source-save :as source-save]
            [graph.lain-editor.frontend.state :as s]
            [graph.lain-editor.workspace :as workspace]
            [graph.lain-editor.layers :as layers]))

(def storage-key "lain-tiled-editor-v1")
(def workspace-path-storage-key "lain-tiled-editor-workspace-path-v1")

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

(defn restore!
  ([input] (restore! input {}))
  ([input options]
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
                       (when (contains? options :workspace-path)
                         (reset! s/workspace-path (:workspace-path options)))
                       (reset! s/generations (into {} (map (fn [[k v]] [(name k) v])) (:generations result)))
                       (reset! s/message "Workspace restored · programs replayed in isolated sessions"))))))))))

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

(defn reopen-closed-block! [archive-id]
  (when-not @s/busy?
    (let [fresh-id (str (random-uuid))
          desktop-id (:activeDesktop @s/workspace)
          next-document (model/reopen-closed-block @s/workspace archive-id desktop-id fresh-id)]
      (dosync
       (reset! s/workspace next-document)
       (s/select-pane! fresh-id))
      (reset! s/message "Closed block reopened · not run")
      (dom/focus-pane! fresh-id))))

(defn load-file! [id file]
  (when file
    (-> (.text file)
        (.then (fn [source]
                 (s/change-pane! id model/loaded-source (.-name file) source)
                 (reset! s/message "File loaded · ⌘Enter / Ctrl+Enter runs the selected block")))
        (.catch #(reset! s/message (.-message %))))))

(defn- save-workspace-to! [path replace?]
  (-> (api! {:op "save-workspace" :path path :replace replace? :workspace @s/workspace})
      (.then (fn [result]
               (reset! s/workspace-path (:path result))
               (reset! s/message (str "Workspace saved to " (:path result)))
               result))))

(defn save-workspace-as! []
  (when-not @s/busy?
    (source-save/open!
     (or @s/workspace-path "lain-workspace.json")
     save-workspace-to!
     js/document
     {:aria-label "Save workspace as"
      :title "Save workspace as"
      :description "Choose a project-relative JSON path. This becomes the target for Save workspace."})))

(defn save-workspace! []
  (if-let [path @s/workspace-path]
    (operation! nil #(save-workspace-to! path true))
    (save-workspace-as!)))

(defn persist! [document]
  (try
    (.setItem js/localStorage storage-key (js/JSON.stringify (clj->js document)))
    (catch :default error
      (reset! s/message (str "Local draft storage: " (.-message error) ". Save JSON to keep your work.")))))

(defn persist-workspace-path! [path]
  (try
    (if path
      (.setItem js/localStorage workspace-path-storage-key path)
      (.removeItem js/localStorage workspace-path-storage-key))
    (catch :default error
      (reset! s/message (str "Workspace path storage: " (.-message error))))))

(defn save-block! [id]
  (when-not @s/busy?
    (source-save/open!
     (str "examples/lain/saved/" (model/block-filename (model/pane @s/workspace id)))
     (fn [path replace?]
       (let [pane (model/pane @s/workspace id)]
         (-> (api! {:op "save-block" :pane id :path path :replace replace? :source (model/block-source pane)})
             (.then (fn [result]
                      (reset! s/message (str "Block saved to " (:path result)))
                      result))))))))

(defn perform! [{:keys [action target archive enabled]}]
  (when enabled
    (case action
      :new-workspace (restore! (model/initial-workspace) {:workspace-path nil})
      :new-desktop (dosync
                     (swap! s/workspace desktops/add-desktop (str (random-uuid)) (model/new-pane))
                     (reset! s/selected-id (desktops/selected-id @s/workspace)))
      :switch-desktop (s/switch-desktop! target)
      :run (run! target "run")
      :load (.click (.querySelector (dom/pane-root target) "input[type=file]"))
      :save-block (save-block! target)
      :split-row (add-pane! target "row")
      :split-column (add-pane! target "column")
      :spawn (add-pane! target nil)
      :close (close-pane! target)
      :reopen-closed (reopen-closed-block! archive)
      (:value :cell :graph) (s/set-display! target :mode (name action))
      (:upstream :downstream :both) (s/set-display! target :direction (name action))
      :layer-settings (set! (.-open (.querySelector (dom/pane-root target) ".layer-panel")) true)
      :fit-graph (.dispatchEvent (dom/pane-root target) (js/Event. "lain-fit-graph"))
      :root-graph (dosync (swap! s/expansions dissoc target) (s/refresh! target))
      :refresh (dosync (swap! s/expansions dissoc target) (s/refresh! target))
      :edit-extension (let [details (.querySelector (dom/pane-root target) ".extension")]
                        (set! (.-open details) true) (.focus (.querySelector details "textarea")))
      :extend (run! target "extend")
      :save (save-workspace!)
      :save-as (save-workspace-as!)
      :open (.click (.getElementById js/document "open"))
      (throw (js/Error. "Unknown palette command")))))
