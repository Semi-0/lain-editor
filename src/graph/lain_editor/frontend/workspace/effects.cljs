(ns graph.lain-editor.frontend.workspace.effects
  "HTTP, source commits, workspace replay, and browser file effects."
  (:refer-clojure :exclude [run!])
  (:require [graph.lain-editor.frontend.ui.dom :as dom]
            [graph.lain-editor.frontend.workspace.desktops :as desktops]
            [graph.lain-editor.frontend.workspace.model :as model]
            [graph.lain-editor.frontend.workspace.source-save :as source-save]
            [graph.lain-editor.frontend.workspace.state :as s]
            [graph.lain-editor.workspace.model :as workspace]
            [graph.lain-editor.workspace.layers :as layers]))

(def storage-key "lain-tiled-editor-v1")
(def workspace-path-storage-key "lain-tiled-editor-workspace-path-v1")

(defonce transport (atom {:epoch 0 :controllers {}}))
(defonce command-dispatch (atom nil))
(defonce operation-token (atom nil))
(defonce file-loads (atom {}))

(defn lifecycle-token [] (:epoch @transport))
(defn current-lifecycle? [token] (= token (lifecycle-token)))

(defn set-dispatch! [dispatch!]
  (reset! command-dispatch dispatch!))

(defn- dispatch! [event]
  (if-let [interpret @command-dispatch]
    (interpret event)
    (throw (ex-info "Editor controller is not attached" {:event event}))))

(defn cancelled? [error]
  (or (= "AbortError" (.-name error)) (:cancelled? (ex-data error))))

(defn dispose! []
  (doseq [controller (vals (:controllers @transport))]
    (when controller (.abort controller)))
  (swap! transport #(-> % (update :epoch inc) (assoc :controllers {})))
  (reset! operation-token nil)
  (reset! file-loads {})
  (s/transact! #(do (reset! s/busy? false) (reset! s/pending-id nil))))

(defn- current-result? [epoch pane generation]
  (and (= epoch (:epoch @transport))
       (or (nil? pane)
           (and (some? (model/pane @s/workspace pane))
                (= generation (get @s/generations pane))))))

(defn api!
  ([command] (api! command nil))
  ([command signal]
   (let [id (str (random-uuid)) epoch (:epoch @transport)
         pane (:pane command) generation (get @s/generations pane)
         controller (when-not signal (js/AbortController.))
         signal (or signal (.-signal controller))]
     (swap! transport assoc-in [:controllers id] controller)
     (-> (js/fetch "/api" #js {:method "POST" :signal signal
                               :headers #js {"Content-Type" "application/json"}
                               :body (js/JSON.stringify (clj->js command))})
         (.then (fn [response]
                  (if (false? (.-ok response))
                    (throw (js/Error. (str "Editor HTTP " (.-status response))))
                    (.json response))))
         (.then (fn [body]
                  (when-not (and (not (.-aborted signal))
                                 (current-result? epoch pane generation))
                    (throw (ex-info "Editor request was superseded" {:cancelled? true})))
                  (if (.-ok body)
                    (js->clj (.-result body) :keywordize-keys true)
                    (throw (js/Error. (.-error body))))))
         (.finally #(swap! transport update :controllers dissoc id))))))

(defn operation! [id action]
  (if @s/busy?
    (js/Promise.resolve nil)
    (let [token (str (random-uuid)) epoch (lifecycle-token)
          current? #(and (= token @operation-token) (current-lifecycle? epoch))]
      (reset! operation-token token)
      (s/transact! (fn [] (reset! s/busy? true) (reset! s/pending-id id)))
      (-> (js/Promise.resolve nil)
          (.then (fn [_] (when (current?) (action))))
          (.catch (fn [error]
                    (when (and (current?) (not (cancelled? error)))
                      (reset! s/message (.-message error))
                      (when (and id (model/pane @s/workspace id))
                        (swap! s/errors assoc id (.-message error))))))
          (.finally (fn []
                      (when (current?)
                        (reset! operation-token nil)
                        (s/transact! #(do (reset! s/busy? false) (reset! s/pending-id nil))))))))))

(defn run! [id op]
  (let [pane (model/pane @s/workspace id)
        extensions (model/extension-sources pane)
        source (case op "run" (:source pane) "extend" (:extensionDraft pane)
                     (throw (js/Error. "Unknown source operation")))]
    (if (and (:development pane) (= op "run"))
      (dispatch! [:development/commit id])
      (operation!
       id
       #(-> (api! {:op op :pane id :source source :extensions (if (= op "run") extensions []) :generation (get @s/generations id)})
            (.then (fn [result]
                     (s/transact! (fn []
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
                      (reset! s/message "Runtime updated · each block has an independent session"))))))))))

(defn spawn-slots! [id]
  (operation!
   id
   #(-> (api! {:op "spawn-slot" :pane id :generation (get @s/generations id)})
        (.then (fn [{:keys [source slots skipped generation displayMode]}]
                 (s/transact! (fn []
                  (when (seq slots)
                    (s/change-pane! id model/extended-source source)
                    (swap! s/expansions dissoc id))
                  (when displayMode (s/change-pane! id layers/program-output displayMode))
                  (swap! s/generations assoc id generation)
                  (swap! s/errors dissoc id)
                  (reset! s/message
                          (str (if (seq slots)
                                 (str "Spawned " (count slots) " callable slots · outputs remain unresolved")
                                 "No undefined callable slots")
                               (when (seq skipped)
                                 (str " · " (count skipped) " scoped/computed slots cannot be filled from this block")))))))))))

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
                      (s/transact! (fn []
                       (reset! s/results {}) (reset! s/errors {}) (reset! s/expansions {})
                       (swap! s/remote assoc :development {} :topology {})
                       (swap! s/ui (fn [ui] (-> ui (assoc :panes-ui {}) (update :workspace-revision (fnil inc 0)))))
                       (reset! s/workspace
                               (reduce (fn [doc [id mode]]
                                         (model/update-pane doc (name id) layers/program-output mode))
                                       document (:displayModes result)))
                       (reset! s/selected-id (desktops/selected-id document))
                       (when (contains? options :workspace-path)
                         (reset! s/workspace-path (:workspace-path options)))
                       (reset! s/generations (into {} (map (fn [[k v]] [(name k) v])) (:generations result)))
                       (reset! s/message "Workspace restored · programs replayed in isolated sessions")))))))))))

(defn add-pane! [id axis]
  (when-not @s/busy?
    (let [fresh (model/new-pane)]
      (s/transact! (fn []
       (if axis
         (swap! s/workspace model/split-pane id axis fresh)
         (swap! s/workspace model/spawn-pane fresh))
       (s/select-pane! (:id fresh))))
      (dom/focus-pane! (:id fresh)))))

(defn close-pane! [id]
  (let [next-document (model/close-pane @s/workspace id)]
    (operation! id
                #(-> (api! {:op "close" :pane id})
                     (.then (fn [_]
                              (s/transact! (fn []
                               (reset! s/workspace next-document)
                               (reset! s/selected-id (desktops/selected-id next-document))
                               (doseq [cell [s/generations s/errors s/results s/expansions s/refreshes]]
                                 (swap! cell dissoc id))))
                              (reset! s/message "Block closed · remaining sessions preserved")
                              (dom/focus-pane! @s/selected-id)))))))

(defn reopen-closed-block! [archive-id]
  (when-not @s/busy?
    (let [fresh-id (str (random-uuid))
          desktop-id (:activeDesktop @s/workspace)
          next-document (model/reopen-closed-block @s/workspace archive-id desktop-id fresh-id)]
      (s/transact! (fn []
       (reset! s/workspace next-document)
       (s/select-pane! fresh-id)))
      (reset! s/message "Closed block reopened · not run")
      (dom/focus-pane! fresh-id))))

(defn load-file! [id file]
  (when file
    (let [token (str (random-uuid)) epoch (lifecycle-token)
          original-source (:source (model/pane @s/workspace id))
          current? #(and (current-lifecycle? epoch) (= token (get @file-loads id))
                         (some? (model/pane @s/workspace id))
                         (= original-source (:source (model/pane @s/workspace id))))]
      (swap! file-loads assoc id token)
      (-> (.text file)
          (.then (fn [source]
                   (when (current?)
                     (s/transact! #(do
                                     (s/change-pane! id model/loaded-source (.-name file) source)
                                     (reset! s/message "File loaded · ⌘Enter / Ctrl+Enter runs the selected block"))))))
          (.catch (fn [error] (when (current?) (reset! s/message (.-message error)))))
          (.finally (fn []
                      (when (= token (get @file-loads id)) (swap! file-loads dissoc id))))))))

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

(defn- toggle-panel! [id panel focus-selector]
  (swap! s/ui update-in [:panes-ui id :panels panel] not)
  (when (and (get-in @s/ui [:panes-ui id :panels panel]) focus-selector)
    (js/setTimeout
     (fn []
       (when-let [root (dom/pane-root id)]
         (when-let [target (.querySelector root focus-selector)] (.focus target))))
     0)))

(defn- toggle-presentation! [id mode]
  (swap! s/ui update-in [:panes-ui id :presentation]
         #(if (= mode %) :normal mode)))

(defn perform! [{:keys [action target archive enabled]}]
  (when enabled
    (case action
      :new-workspace (restore! (model/initial-workspace) {:workspace-path nil})
      :new-desktop (s/transact! (fn []
                     (swap! s/workspace desktops/add-desktop (str (random-uuid)) (model/new-pane))
                     (reset! s/selected-id (desktops/selected-id @s/workspace))))
      :switch-desktop (s/switch-desktop! target)
      :run (run! target "run")
      :load (.click (.querySelector (dom/pane-root target) "input[type=file]"))
      :save-block (save-block! target)
      :split-row (add-pane! target "row")
      :split-column (add-pane! target "column")
      :spawn (add-pane! target nil)
      :spawn-slot (spawn-slots! target)
      :close (close-pane! target)
      :reopen-closed (reopen-closed-block! archive)
      (:value :cell :graph) (s/set-display! target :mode (name action))
      (:upstream :downstream :both) (s/set-display! target :direction (name action))
      :toggle-fullscreen (dispatch! [:dom/fullscreen target])
      :visualizer-settings (reset! s/palette {:open? true :target target :view :visualizer :query "" :active 0})
      :toggle-visualizer (toggle-presentation! target :hidden)
      :toggle-fill (toggle-presentation! target :fill)
      :layer-settings (toggle-panel! target :layers "input[aria-label='Output binding']")
      :fit-graph (dispatch! [:graph/fit target])
      :root-graph (s/transact! (fn [] (swap! s/expansions dissoc target) (s/refresh! target)))
      :refresh (s/transact! (fn [] (swap! s/expansions dissoc target) (s/refresh! target)))
      :edit-extension (toggle-panel! target :extension ".extension textarea")
      :extend (run! target "extend")
      :development-settings (toggle-panel! target :development nil)
      :save (save-workspace!)
      :save-as (save-workspace-as!)
      :open (.click (.getElementById js/document "open"))
      (throw (js/Error. "Unknown palette command")))))
