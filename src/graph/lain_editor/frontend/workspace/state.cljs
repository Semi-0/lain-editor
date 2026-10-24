(ns graph.lain-editor.frontend.workspace.state
  "One authoritative source. Compatibility lenses never own duplicate values."
  (:require [graph.lain-editor.frontend.workspace.model :as model]
            [graph.lain-editor.frontend.model.workspace :as codec]
            [graph.lain-editor.frontend.development.model :as development]
            [graph.lain-editor.workspace.layers :as layers]
            [graph.lain-editor.frontend.workspace.desktops :as desktops]
            [graph.lain-editor.frontend.ui.reactive :as rx]))

(defn initial [document]
  (let [{:keys [domain workspace-ui]} (codec/decode document)]
    {:domain domain
     :ui {:workspace workspace-ui :workspace-path nil :busy? false :pending-id nil
          :panes-ui {} :expansions {} :refreshes {} :tick 0 :workspace-revision 0
          :message "Local runtime · independent blocks"
          :palette {:open? false :target nil :query "" :active 0}}
     :remote {:generations {} :errors {} :results {} :development {}}}))

(defonce source (atom (initial (model/initial-workspace))))
(defonce ui (rx/lens source :ui #(assoc %1 :ui %2)))
(defonce remote (rx/lens source :remote #(assoc %1 :remote %2)))
(defonce workspace (rx/lens source codec/document codec/replace-document))

(defn- ui-cell [key]
  (rx/lens source #(get-in % [:ui key]) #(assoc-in %1 [:ui key] %2)))

(defn- remote-cell [key]
  (rx/lens source #(get-in % [:remote key]) #(assoc-in %1 [:remote key] %2)))

(defonce workspace-path (ui-cell :workspace-path))
(defonce selected-id
  (rx/lens source #(desktops/selected-id (codec/document %))
           #(codec/update-document %1 desktops/select-pane %2)))
(defonce busy? (ui-cell :busy?))
(defonce pending-id (ui-cell :pending-id))
(defonce generations (remote-cell :generations))
(defonce errors (remote-cell :errors))
(defonce results (remote-cell :results))
(defonce expansions (ui-cell :expansions))
(defonce refreshes (ui-cell :refreshes))
(defonce tick (ui-cell :tick))
(defonce message (ui-cell :message))
(defonce palette (ui-cell :palette))

(defn transact! [f] (rx/transact! source f))

(defn development-state [app id]
  {:model (:development (model/pane (codec/document app) id))
   :view (get-in app [:ui :panes-ui id :development-view]
                 {:status :ordinary :message nil})
   :remote (get-in app [:remote :development id]
                   {:generation nil :checkpoint nil :projection nil})})

(defn development-cell [id]
  (rx/lens source #(development-state % id)
           (fn [app {:keys [model view remote]}]
             (-> (codec/update-document
                  app model/update-pane id
                  (fn [pane]
                    (if model (assoc pane :development model) (dissoc pane :development))))
                 (assoc-in [:ui :panes-ui id :development-view] view)
                 (assoc-in [:remote :development id] remote)))))

(defn snapshot [app]
  (let [document (codec/document app)
        selected (desktops/selected-id document)
        authoritative-ui (dissoc (:ui app) :tick)
        observed (:remote app)
        commands (if (= :sessions (get-in authoritative-ui [:palette :view]))
                   (mapv #(assoc % :enabled (not (:busy? authoritative-ui)))
                         (get-in authoritative-ui [:palette :commands]))
                   (into (model/commands document (get-in authoritative-ui [:palette :target])
                                        (:generations observed) (:busy? authoritative-ui))
                         (model/desktop-commands document (:busy? authoritative-ui))))
        pane-views (reduce (fn [views pane]
                             (assoc-in views [(:id pane) :development]
                                       (development/view-model (development-state app (:id pane)))))
                           (:panes-ui authoritative-ui) (:panes document))]
    (merge (dissoc authoritative-ui :workspace)
           observed
           {:workspace document :selected-id selected
            :selected-pane (model/pane document selected)
            :bottom-status (model/bottom-status selected (:errors observed) (:message authoritative-ui))
            :commands commands
            :visible-commands (model/matching-commands commands (get-in authoritative-ui [:palette :query]))
            :panes-ui pane-views :ui authoritative-ui :remote observed})))

(defonce bottom-status (rx/read-only source #(get (snapshot %) :bottom-status)))
(defonce selected-pane (rx/read-only source #(get (snapshot %) :selected-pane)))
(defonce shape (rx/read-only source
                          #(let [document (codec/document %)]
                             {:desktop (:activeDesktop document)
                              :tree (model/layout-shape (get-in document (desktops/layout-path document)))})))
(defonce command-target (rx/read-only source #(get-in % [:ui :palette :target])))
(defonce commands (rx/read-only source #(get (snapshot %) :commands)))
(defonce query (rx/read-only source #(get-in % [:ui :palette :query])))
(defonce visible-commands (rx/read-only source #(get (snapshot %) :visible-commands)))

(defn change-pane! [id f & args]
  (apply swap! workspace model/update-pane id f args))

(defn pane-cell [scope id]
  (rx/derive! scope #(model/pane % id) workspace))

(defn request-cell [scope id descriptor]
  (rx/derive!
   scope
   (fn [p generations expansions refreshes]
     (let [generation (get generations id) mode (layers/inspection-mode p)]
       (when (and generation mode)
         {:pane id :generation generation :display (assoc (:display p) :mode mode)
          :parent (when (= "graph" mode) (get expansions id)) :refresh (get refreshes id 0)})))
   descriptor generations expansions refreshes))

(defn refresh! [id]
  (swap! refreshes update id (fnil inc 0)))

(defn set-display! [id field value]
  (transact!
   #(do
      (if (= :mode field)
        (change-pane! id layers/show-output value)
        (change-pane! id assoc-in [:display field] value))
      (swap! expansions dissoc id))))

(defn select-pane! [id]
  (reset! selected-id id))

(defn switch-desktop! [id]
  (swap! workspace desktops/switch id))
