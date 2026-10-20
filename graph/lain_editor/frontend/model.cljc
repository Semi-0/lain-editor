(ns graph.lain-editor.frontend.model
  "Pure editor document transitions, shared by JVM and browser tests."
  (:require [clojure.string :as str]
            [graph.lain-editor.workspace :as workspace]
            [graph.lain-editor.layers :as layers]
            [graph.lain-editor.frontend.desktops :as desktops]))

(defn new-pane
  ([] (new-pane (str (random-uuid))))
  ([id] {:id id :title "" :filename "" :source "" :executedSource nil
         :extensions [] :extensionDraft "" :outputRatio 0.45
         :layerMode "overlay"
         :layers (layers/for-pane {:display {:mode "value"}})
         :display {:cell "result" :mode "value" :direction "upstream"}}))

(defn initial-workspace []
  (let [pane (new-pane)]
    (workspace/upgrade {:format "lain-tiled-editor" :version 1 :panes [pane]
                        :layout {:type "pane" :id (:id pane)}})))

(defn pane [document id]
  (some #(when (= id (:id %)) %) (:panes document)))

(defn update-pane [document id f & args]
  (workspace/ensure! (pane document id) "Unknown pane")
  (update document :panes
          #(mapv (fn [p] (if (= id (:id p)) (apply f p args) p)) %)))

(defn loaded-source [pane filename source]
  (assoc pane :source source :filename filename :title filename))

(defn replace-leaf [tree id replacement]
  (case (:type tree)
    "pane" (if (= id (:id tree)) replacement tree)
    "split" (-> tree
                (update :first replace-leaf id replacement)
                (update :second replace-leaf id replacement))
    (throw (ex-info "Unknown layout node" {:node tree}))))

(defn split-pane [document id axis fresh]
  (workspace/ensure! (contains? #{"row" "column"} axis) "Unknown split axis")
  (workspace/ensure! (some #{id} (desktops/pane-ids document)) "Unknown pane in this desktop")
  (workspace/ensure! (not (pane document (:id fresh))) "Duplicate pane id")
  (-> document
      (update :panes conj fresh)
      (update-in (desktops/layout-path document) replace-leaf id
              {:type "split" :axis axis :ratio 0.5
               :first {:type "pane" :id id} :second {:type "pane" :id (:id fresh)}})))

(defn spawn-pane [document fresh]
  (workspace/ensure! (not (pane document (:id fresh))) "Duplicate pane id")
  (-> document
      (update :panes conj fresh)
      (assoc-in (desktops/layout-path document)
                {:type "split" :axis "row" :ratio 0.7
                 :first (get-in document (desktops/layout-path document))
                 :second {:type "pane" :id (:id fresh)}})))

(defn- remove-leaf [tree id]
  (case (:type tree)
    "pane" (if (= id (:id tree)) nil tree)
    "split" (let [a (remove-leaf (:first tree) id) b (remove-leaf (:second tree) id)]
              (cond
                (nil? a) b
                (nil? b) a
                :else (assoc tree :first a :second b)))
    (throw (ex-info "Unknown layout node" {:node tree}))))

(defn- next-close-order [document]
  (get document :nextClosedBlockOrder
       (inc (reduce max -1 (map :order (get document :closedBlocks []))))))

(defn archive-entry
  "Portable source-only record. Runtime capabilities are intentionally excluded."
  [document id archive-id]
  (let [p (pane document id)]
    (workspace/ensure! p "Unknown pane")
    {:id archive-id :order (next-close-order document)
     :pane (select-keys p [:id :title :filename :source :executedSource
                           :extensions :extensionEdits :extensionDraft
                           :outputRatio :layerMode :layers :display :development])}))

(defn close-pane
  ([document id] (close-pane document id (str (random-uuid))))
  ([document id archive-id]
   (workspace/ensure! (> (count (desktops/pane-ids document)) 1) "Keep at least one pane per desktop")
   (workspace/ensure! (some #{id} (desktops/pane-ids document)) "Unknown pane in this desktop")
   (workspace/ensure! (< (count (get document :closedBlocks [])) workspace/archive-capacity)
                      "Closed block archive has reached its 64-entry capacity")
   (workspace/ensure! (not-any? #(= archive-id (:id %)) (get document :closedBlocks []))
                      "Duplicate closed block id")
   (let [entry (archive-entry document id archive-id)]
     (-> document
         (update :closedBlocks (fnil conj []) entry)
         (assoc :nextClosedBlockOrder (inc (:order entry)))
         (update :panes #(filterv (fn [p] (not= id (:id p))) %))
         (update-in (desktops/layout-path document) remove-leaf id)
         (#(desktops/select-pane % (first (desktops/pane-ids %))))))))

(defn closed-block [document id]
  (some #(when (= id (:id %)) %) (get document :closedBlocks [])))

(defn closed-block-label [{:keys [order pane]}]
  (let [name (or (not-empty (:title pane)) (not-empty (:filename pane)) "Untitled block")]
    (str "Reopen closed block: " name " (#" (inc order) ")")))

(defn reopen-closed-block
  "Consume an archive into the active desktop using a fresh pane identity.
  A repeated acknowledged delivery is idempotent when that fresh pane exists."
  [document archive-id desktop-id fresh-id]
  (if (pane document fresh-id)
    document
    (let [entry (closed-block document archive-id)]
      (workspace/ensure! entry "Unknown closed block")
      (workspace/ensure! (= desktop-id (if (= 1 (:version document))
                                         "desktop-1"
                                         (:activeDesktop document)))
                         "Active desktop changed before reopen; submit again")
      (workspace/ensure! (< (count (:panes document)) 64) "Workspace has 64 panes")
      (let [archived (:pane entry)
            fresh (-> archived
                      (assoc :id fresh-id :executedSource nil)
                      (cond-> (some? (:executedSource archived))
                        (assoc :previousExecutedSource (:executedSource archived))))]
        (-> document
            (spawn-pane fresh)
            (update :closedBlocks
                    #(filterv (fn [candidate] (not= archive-id (:id candidate))) %)))))))

(defn layout-shape [tree]
  (case (:type tree)
    "pane" (select-keys tree [:type :id])
    "split" {:type "split" :axis (:axis tree)
             :first (layout-shape (:first tree)) :second (layout-shape (:second tree))}
    (throw (ex-info "Unknown layout node" {:node tree}))))

(defn extension-sources [pane]
  (get pane :extensionEdits (:extensions pane)))

(defn block-source
  "Current editable code in execution order, including the pending extension."
  [pane]
  (let [sources (concat [(:source pane)] (extension-sources pane)
                        (when-not (str/blank? (:extensionDraft pane)) [(:extensionDraft pane)]))
        source (str/join "\n\n" sources)]
    (if (or (empty? source) (str/ends-with? source "\n")) source (str source "\n"))))

(defn block-filename [pane]
  (let [filename (last (str/split (:filename pane) #"[/\\]"))]
    (if (str/blank? filename)
      (str "block-" (:id pane) ".lain")
      (str (str/replace filename #"\.[^.]+$" "") ".lain"))))

(defn extensions-edited? [pane]
  (not= (:extensions pane) (extension-sources pane)))

(defn edit-extension [pane index source]
  (workspace/ensure! (< -1 index (count (:extensions pane))) "Unknown extension")
  (assoc pane :extensionEdits (assoc (extension-sources pane) index source)))

(defn extended-source [pane source]
  (workspace/ensure! (not (extensions-edited? pane)) "Run edited extensions before adding another")
  (-> pane
      (update :extensions conj source)
      (assoc :extensionDraft "")
      (dissoc :extensionEdits)))

(defn committed-source [pane source extensions]
  (-> pane
      (assoc :executedSource source :extensions extensions)
      (dissoc :extensionEdits)))

(defn status-text [p error]
  (cond
    error error
    (nil? (:executedSource p)) "Not run"
    (or (not= (:source p) (:executedSource p)) (extensions-edited? p)) "Edited · ⌘Enter / Ctrl+Enter to run"
    :else (str "Running · " (count (:extensions p)) " extensions")))

(def command-specs
  [[:run "Run block"] [:load "Load Lain file…"] [:save-block "Save block to file…"]
   [:split-row "Split side by side"] [:split-column "Split stacked"]
   [:spawn "Spawn new block"] [:close "Close block"]
   [:value "Show value"] [:cell "Show cell details"] [:graph "Show topology"]
   [:upstream "Trace upstream"] [:downstream "Trace downstream"] [:both "Trace both directions"]
   [:layer-settings "Edit display layers"] [:fit-graph "Compact topology"] [:root-graph "Return to root topology"]
   [:refresh "Refresh output"] [:edit-extension "Edit extension code"] [:extend "Apply extension"]
   [:new-workspace "New workspace"] [:save "Save workspace"]
   [:save-as "Save workspace as…"] [:open "Open workspace JSON…"]])

(defn commands [document id generations busy?]
  (let [p (pane document id)]
    (into
     (mapv (fn [[action label]]
             {:action action :label label :target id
              :enabled (and (not busy?) (some? p)
                            (case action
                              :close (and (> (count (desktops/pane-ids document)) 1)
                                          (< (count (get document :closedBlocks []))
                                             workspace/archive-capacity))
                              :extend (and (contains? generations id)
                                           (not (extensions-edited? p))
                                           (not (str/blank? (:extensionDraft p))))
                              true))})
           command-specs)
     (map (fn [entry]
            {:action :reopen-closed :archive (:id entry) :target id
             :label (closed-block-label entry)
             :enabled (and (not busy?) (< (count (:panes document)) 64))})
          (reverse (get document :closedBlocks []))))))

(defn desktop-commands [document busy?]
  (into [{:action :new-desktop :label "New desktop" :enabled (and (not busy?) (< (count (:panes document)) 64))}]
        (map-indexed (fn [index desktop]
                       {:action :switch-desktop :target (:id desktop)
                        :label (str "Switch to desktop: " (inc index)) :enabled (not busy?)}))
        (workspace/desktop-layouts document)))

(defn matching-commands [commands query]
  (let [words (remove str/blank? (str/split (str/lower-case (str/trim query)) #"\s+"))]
    (filterv (fn [command]
               (every? #(str/includes? (str/lower-case (:label command)) %) words)) commands)))
