(ns graph.lain-editor.frontend.controller.events
  "Declarative interaction interpreter. Effects are explicit at this boundary."
  (:require [clojure.string :as str]
            [graph.lain-editor.frontend.workspace.state :as s]
            [graph.lain-editor.frontend.workspace.model :as model]
            [graph.lain-editor.frontend.workspace.desktops :as desktops]
            [graph.lain-editor.frontend.workspace.effects :as effects]
            [graph.lain-editor.frontend.workspace.remote-files :as remote-files]
            [graph.lain-editor.frontend.development.dataflow :as development]
            [graph.lain-editor.frontend.development.model :as development-model]
            [graph.lain-editor.development.protocol :as protocol]
            [graph.lain-editor.workspace.layers :as layers]
            [graph.lain-editor.frontend.ui.reactive :as rx]))

(defonce handlers (atom {}))
(defonce features (atom {}))
(defonce restore-token (atom nil))

(defn register-handler! [event-namespace handler]
  (swap! handlers assoc event-namespace handler)
  #(swap! handlers
          (fn [registered]
            (if (identical? handler (get registered event-namespace))
              (dissoc registered event-namespace)
              registered))))

(declare dispatch!)

(defn- current-command [{:keys [action target archive] :as command}]
  (let [target (or target @s/selected-id)
        available (into (model/commands @s/workspace target @s/generations @s/busy?)
                        (model/desktop-commands @s/workspace @s/busy?))]
    (or (some #(when (and (= action (:action %))
                         (= archive (:archive %))
                         (= target (or (:target %) target))) %) available)
        (throw (ex-info "Unsupported editor command" {:command command})))))

(defn- perform! [command]
  (effects/perform! (current-command command)))

(defn- palette-key! [key]
  (case key
    ("ArrowDown" "ArrowUp")
    (let [step (if (= "ArrowDown" key) 1 -1)
          total (max 1 (count @s/visible-commands))]
      (swap! s/palette update :active #(mod (+ % step total) total)))
    "Enter"
    (when-let [command (get @s/visible-commands (:active @s/palette))]
      (when (:enabled command)
        (swap! s/palette assoc :open? false)
        (perform! command)))
    "Escape" (swap! s/palette assoc :open? false)
    nil))

(defn- require-feature [id]
  (or (get @features id)
      (throw (ex-info "Development feature is not attached" {:pane id}))))

(defn- development-event! [kind id]
  (let [feature (require-feature id)]
    ((:dispatch! feature)
     (case kind
       :development/enable
       (let [client-id (str "editor-" id)]
         {:event/type kind
          :model (protocol/portable-model "stack-spec-2026-10-20-r9" client-id
                                          (protocol/empty-instance client-id))})
       :development/commit
       {:event/type kind :commit-id (str (random-uuid)) :index 0
        :text (:source (model/pane @s/workspace id))}
       :development/disable {:event/type kind}
       (throw (ex-info "Unsupported development event" {:event kind}))))))

(defn- file-at [files]
  (when files (aget files 0)))

(defn dispatch!
  ([event] (dispatch! event nil))
  ([[kind a b c :as event] native]
   (when-not (and (vector? event) (keyword? kind))
     (throw (ex-info "Invalid declarative event" {:event event})))
   (case kind
     :workspace/select (s/select-pane! a)
     :workspace/edit-source (s/change-pane! a assoc :source b)
     :workspace/edit-extension (s/change-pane! a model/edit-extension b c)
     :workspace/edit-extension-draft (s/change-pane! a assoc :extensionDraft b)
     :workspace/command (perform! a)
     :workspace/set-ratio
     (do
       (when-not (and (vector? a) (number? b) (<= 0.1 b 0.9))
         (throw (ex-info "Invalid split ratio" {:path a :ratio b})))
       (swap! s/workspace assoc-in (conj a :ratio) b))
     :palette/open (reset! s/palette {:open? true :target (or a @s/selected-id) :query "" :active 0})
     :palette/close (swap! s/palette assoc :open? false)
     :palette/query (swap! s/palette assoc :query a :active 0)
     :palette/key (palette-key! a)
     :visualizer/size
     (let [size (js/Number b)]
       (when-not (<= 10 size 90)
         (throw (ex-info "Graphical view size must be between 10 and 90 percent" {:size b})))
       (swap! s/ui assoc-in [:panes-ui a :graph-size] size))
     :panel/toggle
     (do
       (when-not (contains? #{:extension :layers :development} b)
         (throw (ex-info "Unknown editor panel" {:panel b})))
       (when-not (contains? #{true false} c)
         (throw (ex-info "Panel open state must be boolean" {:open? c})))
       (swap! s/ui assoc-in [:panes-ui a :panels b] c))
     :layer/visibility (s/change-pane! a layers/set-visible b c)
     :layer/move (s/change-pane! a layers/move-layer b c)
     :layer/mode
     (do
       (when-not (contains? #{"overlay" "stack"} b)
         (throw (ex-info "Unknown layer composition" {:mode b})))
       (s/change-pane! a assoc :layerMode b))
     :layer/binding
     (when-not (str/blank? b)
       (s/transact! #(do (s/change-pane! a assoc-in [:display :cell] (str/trim b))
                         (swap! s/expansions dissoc a)
                         (s/refresh! a))))
     :layer/binding-key (when (= "Enter" b) (dispatch! [:layer/binding a c]))
     :file/load (effects/load-file! a (file-at b))
     :file/restore
     (when-let [file (file-at a)]
       (let [token (str (random-uuid)) epoch (effects/lifecycle-token)
             current? #(and (= token @restore-token) (effects/current-lifecycle? epoch))]
         (reset! restore-token token)
         (-> (.text file)
             (.then (fn [text]
                      (when (current?)
                        (effects/restore! (js->clj (js/JSON.parse text) :keywordize-keys true)
                                          {:workspace-path nil}))))
             (.catch (fn [error] (when (current?) (reset! s/message (.-message error))))))))
     (:development/enable :development/commit :development/disable)
     (development-event! kind a)
     (if-let [handler (get @handlers (namespace kind))]
       (handler event native)
       (throw (ex-info "Unknown declarative event" {:event event}))))))

(defn- attach-development! [scope]
  (let [active (atom {})]
    (rx/watch!
     scope (rx/derive! scope
                       (fn [app]
                         [(get-in app [:ui :workspace-revision])
                          (mapv :id (:panes (:workspace (s/snapshot app))))])
                       s/source)
     (fn [[revision ids]]
       (let [wanted (set ids)]
         (doseq [[id owned] @active]
           (when (or (not (contains? wanted id)) (not= revision (:revision owned)))
             (rx/dispose! (:scope owned))
             (swap! active dissoc id)
             (swap! features dissoc id)))
         (doseq [id ids]
           (when-not (contains? @active id)
             (let [child (rx/scope)
                   portable (:development (model/pane @s/workspace id))
                   cell (s/development-cell id)
                   _ (reset! cell (development-model/initial portable))
                   feature (development/create! child {:pane id :portable-model portable :state-cell cell})]
               (swap! active assoc id {:scope child :revision revision})
               (swap! features assoc id feature)
               (when portable ((:dispatch! feature) {:event/type :development/bootstrap}))))))))
    (rx/own! scope #(do (doseq [owned (vals @active)] (rx/dispose! (:scope owned)))
                        (reset! active {}) (reset! features {})))))

(defn- attach-storage! [scope]
  (let [saved (.getItem js/localStorage effects/storage-key)
        saved-path (.getItem js/localStorage effects/workspace-path-storage-key)
        workspace-key (random-uuid) path-key (random-uuid)]
    (add-watch s/workspace workspace-key (fn [_ _ _ document] (effects/persist! document)))
    (add-watch s/workspace-path path-key (fn [_ _ _ path] (effects/persist-workspace-path! path)))
    (rx/own! scope #(do (remove-watch s/workspace workspace-key)
                        (remove-watch s/workspace-path path-key)))
    (if saved
      (try
        (effects/restore! (js->clj (js/JSON.parse saved) :keywordize-keys true)
                          {:workspace-path saved-path})
        (catch :default error (reset! s/message (.-message error))))
      (effects/persist! @s/workspace))))

(defn attach! [scope]
  (effects/set-dispatch! dispatch!)
  (attach-development! scope)
  (try
    (attach-storage! scope)
    (catch :default error (reset! s/message (str "Local draft storage: " (.-message error)))))
  (remote-files/attach! scope)
  (rx/own! scope #(do (effects/dispose!) (effects/set-dispatch! nil))))
