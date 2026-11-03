(ns graph.lain-editor.frontend.workspace.effects
  "HTTP, source commits, workspace replay, and browser file effects."
  (:refer-clojure :exclude [run!])
  (:require [clojure.string :as str]
            [graph.lain-editor.frontend.ui.dom :as dom]
            [graph.lain-editor.frontend.workspace.desktops :as desktops]
            [graph.lain-editor.frontend.workspace.model :as model]
            [graph.lain-editor.frontend.workspace.source-save :as source-save]
            [graph.lain-editor.frontend.workspace.state :as s]
            [graph.lain-editor.workspace.model :as workspace]
            [graph.lain-editor.workspace.layers :as layers]))

(def storage-key "lain-tiled-editor-v1")
(def reconnect-storage-key "lain-tiled-editor-reconnect-v1")

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
  (source-save/close!)
  (reset! operation-token nil)
  (reset! file-loads {})
  (swap! s/ui dissoc :file-browser)
  (s/transact! #(do (reset! s/busy? false) (reset! s/pending-id nil))))

(defn- current-result? [epoch pane generation]
  (and (= epoch (:epoch @transport))
       (or (nil? pane)
           (and (some? (model/pane @s/workspace pane))
                (= generation (get @s/generations pane))))))

(defn connected? [] (= :connected (:status @s/connection)))

(defn- cancelled-error []
  (ex-info "Remote connection paused or request superseded" {:cancelled? true}))

(defn- pause! [status]
  ;; Invalidate the epoch before promise callbacks can apply any late result.
  (dispose!)
  (reset! s/connection {:status status}))

(defn- request! [command external-signal session-check?]
  (if (and (not session-check?) (not (connected?)))
    (js/Promise.reject (cancelled-error))
    (let [id (str (random-uuid)) epoch (lifecycle-token)
          pane (when-not (= "reopen-session" (:op command)) (:pane command))
          generation (get @s/generations pane)
          controller (js/AbortController.) signal (.-signal controller)
          abort! #(.abort controller)
          current? #(and (not (.-aborted signal)) (current-result? epoch pane generation))]
      (when external-signal
        (.addEventListener external-signal "abort" abort! #js {:once true})
        (when (.-aborted external-signal) (abort!)))
      (swap! transport assoc-in [:controllers id] controller)
      (-> (js/fetch (if session-check? "/auth/session" "/api")
                    (if session-check?
                      #js {:method "GET" :signal signal :cache "no-store" :redirect "error"}
                      #js {:method "POST" :signal signal :redirect "error"
                           :headers #js {"Content-Type" "application/json"}
                           :body (js/JSON.stringify (clj->js command))}))
          (.then (fn [response]
                   (when-not (current?) (throw (cancelled-error)))
                   (case (.-status response)
                     401 (do (pause! :expired) (throw (cancelled-error)))
                     403 (do (pause! :forbidden) (throw (cancelled-error)))
                     502 (throw (js/Error. "Backend unavailable or authentication configuration failure (502)"))
                     (if (and (false? (.-ok response)) (not= 400 (.-status response)))
                       (throw (js/Error. (str "Editor HTTP " (.-status response))))
                       (.json response)))))
          (.then (fn [body]
                   (when-not (current?) (throw (cancelled-error)))
                   (if (.-ok body)
                     (js->clj (.-result body) :keywordize-keys true)
                     (throw (js/Error. (.-error body))))))
          (.finally (fn []
                      (when external-signal
                        (.removeEventListener external-signal "abort" abort!))
                      (swap! transport update :controllers dissoc id)))))))

(defn api!
  ([command] (api! command nil))
  ([command signal] (request! command signal false)))

(defn resume! []
  (let [epoch (lifecycle-token)]
    (-> (request! nil nil true)
        (.then (fn [session]
                 (when (and (current-lifecycle? epoch) (:authenticated session))
                   (s/transact!
                    #(do (reset! s/connection {:status :connected})
                         (reset! s/message "Connection resumed · interrupted operations were not repeated")
                         (swap! s/tick inc))))))
        (.catch (fn [error]
                  (when-not (cancelled? error)
                    (swap! s/connection assoc :message (.-message error))))))))

(defn operation! [id action]
  (if (or @s/busy? (not (connected?)))
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

(defn- remember-owner! [id result]
  (swap! s/generations assoc id (:generation result))
  (swap! s/session-ids assoc id (:sessionId result))
  (swap! s/run-ids assoc id (:runId result))
  (swap! s/applied-extensions assoc id (:appliedExtensions result))
  (swap! s/reconnect assoc id (assoc (model/reconnect-entry result) :appliedExtensions (:appliedExtensions result))))

(defn- reconcile-before! [id]
  (if-let [session-id (get @s/session-ids id)]
    (let [generation (get @s/generations id)]
      (-> (api! {:op "session-status" :sessionId session-id})
          (.then (fn [owner]
                   (when-not (= generation (:generation owner))
                     (s/transact! #(do (s/change-pane! id model/reconcile-owner owner (get @s/applied-extensions id))
                                       (remember-owner! id owner)
                                       (s/refresh! id)))
                     (throw (js/Error. "Session changed · current metadata refreshed; review before retrying")))))))
    (js/Promise.resolve nil)))

(defn- mutate! [id command]
  (if (get @s/session-ids id)
    (-> (reconcile-before! id) (.then (fn [_] (api! command))))
    (api! command)))

(defn run!
  ([id op] (run! id op true))
  ([id op compiler-session?]
   (let [pane (model/pane @s/workspace id)
         extensions (model/extension-sources pane)
         source (case op "run" (:source pane) "extend" (:extensionDraft pane)
                      (throw (js/Error. "Unknown source operation")))]
     (if (and (:development pane) (= op "run"))
       (dispatch! [:development/commit id])
       (operation!
        id
        #(-> (mutate! id {:op op :pane id :source source :compilerSession compiler-session?
                          :extensions (if (= op "run") extensions []) :generation (get @s/generations id)})
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
                                     (remember-owner! id result)
                                     (reset! s/message "Runtime updated · session continues when views close")))))))))))

(defn spawn-slots! [id]
  (operation!
   id
   #(-> (reconcile-before! id)
        (.then (fn [_] (api! {:op "spawn-slot" :pane id :generation (get @s/generations id)})))
        (.then (fn [{:keys [source slots skipped generation displayMode] :as result}]
                 (s/transact! (fn []
                                (when (seq slots)
                                  (s/change-pane! id model/extended-source source)
                                  (remember-owner! id result)
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

(declare persist-reconnect!)

(defn restore!
  ([input] (restore! input {}))
  ([input options]
   (let [document (workspace/upgrade input)]
     (operation!
      nil
      #(-> (api! {:op "restore" :workspace document :reconnect (or (:reconnect options) {})})
           (.then (fn [result]
                    (s/transact!
                     (fn []
                       (doseq [cell [s/results s/errors s/expansions s/session-ids s/run-ids
                                     s/applied-extensions s/reconnect s/generations]] (reset! cell {}))
                       (swap! s/remote assoc :development {} :topology {})
                       (swap! s/ui (fn [ui] (-> ui (assoc :panes-ui {}) (update :workspace-revision (fnil inc 0)))))
                       (reset! s/workspace
                               (reduce (fn [doc [id owner]]
                                         (let [id (name id)
                                               references (:reconnect options)
                                               prior (get references (keyword id) (get references id))]
                                           (remember-owner! id owner)
                                           (cond-> (model/update-pane doc id model/reconcile-owner owner (:appliedExtensions prior))
                                             (:displayMode owner) (model/update-pane id layers/program-output (:displayMode owner)))))
                                       document (:sessions result)))
                       (reset! s/selected-id (desktops/selected-id document))
                       (when (contains? options :workspace-path) (reset! s/workspace-path (:workspace-path options)))
                       ;; Even an empty reconciliation must overwrite a saved
                       ;; reference: the initial remote map may already be empty.
                       (persist-reconnect! @s/reconnect)
                       (reset! s/message "Workspace restored · available sessions reconnected; missing sessions require Run"))))))))))

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
                                             (doseq [cell [s/generations s/session-ids s/run-ids s/applied-extensions s/reconnect s/errors s/results s/expansions s/refreshes]]
                                               (swap! cell dissoc id))))
                              (reset! s/message "Block closed · remaining sessions preserved")
                              (dom/focus-pane! @s/selected-id)))))))

(defn detach-session! [id]
  (let [pane (model/pane @s/workspace id)]
    (when (and pane (get @s/generations id)
               (or (not (model/draft-edits? pane))
                   (js/confirm "Detach running session and discard draft edits? Only executed source and extensions are retained.")))
      (operation!
       id
       #(let [generation (get @s/generations id)]
          (-> (api! {:op "detach-session" :pane id :generation generation})
              (.then (fn [_]
                       (s/transact!
                        (fn []
                          (swap! s/workspace model/detach-pane id (model/new-pane))
                          (doseq [cell [s/generations s/session-ids s/run-ids s/applied-extensions s/reconnect s/errors s/results s/expansions s/refreshes]]
                            (swap! cell dissoc id))
                          (swap! s/remote update :development dissoc id)
                          (reset! s/message "Session detached · runtime continues running")))
                       (dom/focus-pane! @s/selected-id)))))))))

(defn pick-session! [action]
  (operation!
   nil
   #(-> (api! {:op "list-sessions"})
        (.then (fn [{:keys [sessions]}]
                 (reset! s/palette {:open? true :view :sessions
                                    :heading (if (= :reopen-running action)
                                               "Reopen running session" "Stop session")
                                    :commands (model/session-commands sessions action)
                                    :query "" :active 0}))))))

(defn reopen-session! [session-id]
  (let [id (str (random-uuid))]
    (operation!
     nil
     #(do
        (when (>= (count (:panes @s/workspace)) 64)
          (throw (js/Error. "Workspace has 64 panes")))
        (-> (api! {:op "reopen-session" :sessionId session-id :pane id})
            (.then (fn [result]
                     (s/transact!
                      (fn []
                        (swap! s/workspace model/spawn-pane (model/reopened-pane id result))
                        (remember-owner! id result)
                        (s/select-pane! id)
                        (s/refresh! id)
                        (reset! s/message "Session reopened · live runtime preserved")))
                     (dom/focus-pane! id))))))))

(defn stop-session! [session-id]
  (operation!
   nil
   #(-> (api! {:op "stop-session" :sessionId session-id})
        (.then (fn [_]
                 (s/transact!
                  (fn [] (doseq [[id owner-id] @s/session-ids :when (= owner-id session-id)]
                           (doseq [cell [s/generations s/session-ids s/run-ids s/applied-extensions s/reconnect s/results]]
                             (swap! cell dissoc id)))))
                 (reset! s/message "Session stopped"))))))

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

(declare upload-to-server!)

(defn load-file! [_id file]
  (upload-to-server! "source" file))

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
      :description "Save inside the server workspace. This becomes the target for Save workspace."})))

(defn save-workspace! []
  (if-let [path @s/workspace-path]
    (operation! nil #(save-workspace-to! path true))
    (save-workspace-as!)))

(defn- download! [filename content mime]
  (let [blob (js/Blob. #js [content] #js {:type mime})
        url (.createObjectURL js/URL blob)
        link (.createElement js/document "a")]
    (set! (.-href link) url)
    (set! (.-download link) filename)
    (.click link)
    (js/setTimeout #(.revokeObjectURL js/URL url) 1000)))

(defn export-workspace! []
  (download! (last (str/split (or @s/workspace-path "lain-workspace.json") #"[/\\]"))
             (js/JSON.stringify (clj->js @s/workspace) nil 2) "application/json"))

(defn download-file! [id]
  (let [pane (model/pane @s/workspace id)]
    (download! (model/block-filename pane) (model/block-source pane) "text/plain;charset=utf-8")))

(defn persist! [document]
  (try
    (.setItem js/localStorage storage-key (js/JSON.stringify (clj->js document)))
    (catch :default error
      (reset! s/message (str "Local draft storage: " (.-message error) ". Save JSON to keep your work.")))))

(defn persist-reconnect! [references]
  (try
    (.setItem js/localStorage reconnect-storage-key (js/JSON.stringify (clj->js references)))
    (catch :default error (reset! s/message (str "Reconnect storage: " (.-message error))))))

(defn read-reconnect []
  (try
    (if-let [saved (.getItem js/localStorage reconnect-storage-key)]
      (js->clj (js/JSON.parse saved) :keywordize-keys true) {})
    (catch :default _ {})))

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
     (let [pane (model/pane @s/workspace id)]
       (if (str/ends-with? (:filename pane) ".lain") (:filename pane) (model/block-filename pane)))
     (fn [path replace?]
       (let [pane (model/pane @s/workspace id)]
         (-> (api! {:op "save-block" :pane id :path path :replace replace? :source (model/block-source pane)})
             (.then (fn [result]
                      (s/change-pane! id assoc :filename (:path result))
                      (reset! s/message (str "File saved in server workspace: " (:path result)))
                      result))))))))

(defn close-server-browser! []
  (swap! s/ui dissoc :file-browser))

(defn- browser-current? [id epoch]
  (and (current-lifecycle? epoch) (= id (get-in @s/ui [:file-browser :id]))))

(defn browse-server! [path]
  (let [{:keys [id kind]} (:file-browser @s/ui) epoch (lifecycle-token) request-id (str (random-uuid))
        current? #(and (browser-current? id epoch) (= request-id (get-in @s/ui [:file-browser :request-id])))]
    (when id
      (swap! s/ui update :file-browser assoc :loading? true :error nil :request-id request-id)
      (-> (api! {:op "browse-files" :kind kind :path path})
          (.then (fn [result]
                   (when (current?)
                     (swap! s/ui update :file-browser merge result {:loading? false :directory (:path result) :query ""}))))
          (.catch (fn [error]
                    (when (and (current?) (not (cancelled? error)))
                      (swap! s/ui update :file-browser assoc :loading? false :error (.-message error)))))))))

(defn open-server-browser! [kind]
  (when (connected?)
    (swap! s/ui assoc :file-browser {:id (str (random-uuid)) :kind kind :path "" :directory ""
                                     :query "" :entries [] :loading? true})
    (browse-server! "")))

(defn- open-source-document! [{:keys [path source]}]
  (when (>= (count (:panes @s/workspace)) 64) (throw (js/Error. "Workspace has 64 panes")))
  (let [id (str (random-uuid))
        pane (model/loaded-source (model/new-pane id) path source)]
    (s/transact! #(do (swap! s/workspace model/spawn-pane pane)
                      (s/select-pane! id)
                      (reset! s/message (str "Opened " path " from server workspace · not run"))))
    (dom/focus-pane! id)))

(defn open-server-path! [path]
  (let [{:keys [id kind]} (:file-browser @s/ui) epoch (lifecycle-token)]
    (swap! s/ui update :file-browser assoc :loading? true :error nil)
    (-> (api! {:op (if (= kind "workspace") "open-workspace" "open-file") :path path})
        (.then (fn [result]
                 (when (browser-current? id epoch)
                   (if (= kind "workspace")
                     (-> (restore! (:workspace result) {:workspace-path (:path result)})
                         (.then (fn [_] (close-server-browser!))))
                     (do (close-server-browser!) (open-source-document! result))))))
        (.catch (fn [error]
                  (when (and (browser-current? id epoch) (not (cancelled? error)))
                    (swap! s/ui update :file-browser assoc :loading? false :error (.-message error))))))))

(defn upload-to-server! [kind file]
  (when file
    (let [epoch (lifecycle-token)
          browser (:file-browser @s/ui)
          token (str (random-uuid))
          directory (or (:path browser) "")
          basename (last (str/split (.-name file) #"[/\\]"))
          filename (str (str/replace basename #"\.[^.]+$" "") (if (= kind "workspace") ".json" ".lain"))
          path (str (when-not (str/blank? directory) (str directory "/")) filename)
          current? #(and (current-lifecycle? epoch) (= token (:upload @file-loads))
                         (or (nil? browser) (= (:id browser) (get-in @s/ui [:file-browser :id]))))]
      (swap! file-loads assoc :upload token)
      (if (> (.-size file) (* (if (= kind "workspace") 16 1) 1024 1024))
        (reset! s/message "File exceeds the editor size limit")
        (-> (.text file)
            (.then
             (fn [text]
               (when (current?)
                 (let [document (when (= kind "workspace")
                                  (workspace/upgrade (js->clj (js/JSON.parse text) :keywordize-keys true)))
                       save! (fn [destination replace?]
                               (-> (api! (if document
                                           {:op "save-workspace" :path destination :replace replace? :workspace document}
                                           {:op "save-block" :path destination :replace replace? :source text}))
                                   (.then (fn [saved]
                                            (if document
                                              (restore! document {:workspace-path (:path saved)})
                                              (open-source-document! {:path (:path saved) :source text}))))))]
                   (close-server-browser!)
                   (source-save/open! path save! nil
                                      {:aria-label "Save uploaded file" :title "Save uploaded file to server workspace"
                                       :description "Save a server copy before opening. Existing files require explicit replacement."})))))
            (.catch (fn [error]
                      (when (current?)
                        (if (:file-browser @s/ui)
                          (swap! s/ui assoc-in [:file-browser :error] (.-message error))
                          (reset! s/message (.-message error)))))))))))

(defn server-files-event! [[action a b]]
  (case action
    :server-files/close (close-server-browser!)
    :server-files/directory (swap! s/ui assoc-in [:file-browser :directory] a)
    :server-files/query (swap! s/ui assoc-in [:file-browser :query] a)
    :server-files/browse (browse-server! (or a (get-in @s/ui [:file-browser :directory])))
    :server-files/choose (if (= b "directory") (browse-server! a) (open-server-path! a))
    :server-files/upload (upload-to-server! (get-in @s/ui [:file-browser :kind]) (when a (.item a 0)))
    :server-files/upload-picker (.click (.getElementById js/document "server-file-upload"))
    (throw (ex-info "Unknown server file action" {:action action}))))

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

(defn remove-extension! [id extension-id]
  (when (js/confirm "Remove this committed extension from runtime? The run and remaining effects will restart. Unrelated draft edits will stay unrun.")
    (operation!
     id
     #(-> (reconcile-before! id)
          (.then (fn [_] (api! {:op "remove-extension" :pane id :generation (get @s/generations id)
                                :extensionId extension-id})))
          (.then (fn [result]
                   (s/transact!
                    (fn []
                      (s/change-pane! id model/remove-committed-extension (get @s/applied-extensions id) extension-id)
                      (s/change-pane! id model/reconcile-owner result)
                      (remember-owner! id result)
                      (swap! s/expansions dissoc id)
                      (s/refresh! id)
                      (reset! s/message "Extension removed · replacement run active; drafts preserved")))))))))

(defn perform! [{:keys [action target archive session extensionId enabled]}]
  (when enabled
    (case action
      :new-workspace (restore! (model/initial-workspace) {:workspace-path nil})
      :new-desktop (s/transact! (fn []
                                  (swap! s/workspace desktops/add-desktop (str (random-uuid)) (model/new-pane))
                                  (reset! s/selected-id (desktops/selected-id @s/workspace))))
      :switch-desktop (s/switch-desktop! target)
      :run (run! target "run")
      :run-legacy (run! target "run" false)
      :close-extension-editor (swap! s/ui update-in [:panes-ui target :hidden-extension-editors] (fnil conj #{}) extensionId)
      :reopen-extension-editor (swap! s/ui update-in [:panes-ui target :hidden-extension-editors] disj extensionId)
      :remove-extension (remove-extension! target extensionId)
      :load (open-server-browser! "source")
      :download-file (download-file! target)
      :download-workspace (export-workspace!)
      :save-block (save-block! target)
      :split-row (add-pane! target "row")
      :split-column (add-pane! target "column")
      :spawn (add-pane! target nil)
      :spawn-slot (spawn-slots! target)
      :close (close-pane! target)
      :detach-session (detach-session! target)
      :reopen-session (pick-session! :reopen-running)
      :stop-session (pick-session! :stop-running)
      :reopen-running (reopen-session! session)
      :stop-running (stop-session! session)
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
      :toggle-auto-refresh
      (s/change-pane! target update :autoRefresh #(= false %))
      :edit-extension (toggle-panel! target :extension ".extension textarea")
      :extend (run! target "extend")
      :development-settings (toggle-panel! target :development nil)
      :save (save-workspace!)
      :save-as (save-workspace-as!)
      :open (open-server-browser! "workspace")
      (throw (js/Error. "Unknown palette command")))))
