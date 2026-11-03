(ns graph.lain-editor.frontend.view.file-browser
  (:require [clojure.string :as str]))

(defn view [{:keys [id kind path parent directory query entries loading? error truncated] :as state}]
  (when state
    (let [title (if (= kind "workspace") "Open workspace" "Open file")
          visible (filter #(str/includes? (str/lower-case (:name %)) (str/lower-case (or query ""))) entries)]
      [:dialog.source-save.server-file-browser
       {:replicant/key [:server-file-browser id] :aria-label title
        :replicant/on-mount [[:dom/modal-open [:event/target]]]
        :on {:cancel [[:event/prevent-default] [:server-files/close]]}}
       [:h2 title]
       [:p "Server workspace /" path]
       [:form.file-browser-location {:on {:submit [[:event/prevent-default] [:server-files/browse]]}}
        [:label "Directory"
         [:input {:type "text" :aria-label "Server workspace directory" :value directory :disabled loading?
                  :on {:input [[:server-files/directory [:event/target.value]]]}}]]
        [:button {:type "submit" :disabled loading?} "Go"]
        [:button {:type "button" :disabled (or loading? (nil? parent))
                  :on {:click [[:server-files/browse parent]]}} "Up"]]
       [:input {:type "search" :aria-label "Filter server files" :placeholder "Filter files…" :value query
                :on {:input [[:server-files/query [:event/target.value]]]}}]
       (into [:div.server-file-list {:aria-busy (boolean loading?)}]
             (if loading?
               [[:p "Loading…"]]
               (if (seq visible)
                 (map (fn [entry]
                        [:button {:replicant/key (:path entry) :type "button"
                                  :aria-label (str (if (= "directory" (:kind entry)) "Open folder " "Open ") (:name entry))
                                  :on {:click [[:server-files/choose (:path entry) (:kind entry)]]}}
                         (:name entry) (when (= "directory" (:kind entry)) "/")]) visible)
                 [[:p "No matching files"]])))
       (when truncated [:p "Showing the first 1,000 entries. Open a subdirectory to browse more."])
       [:p.error {:role "alert" :hidden (nil? error)} error]
       [:input#server-file-upload {:type "file" :hidden true
                                   :accept (if (= kind "workspace") ".json,application/json" ".lain,.clj,.txt,text/plain")
                                   :on {:change [[:server-files/upload [:event/target.files]]]}}]
       [:div.source-save-actions
        [:button {:type "button" :disabled loading? :on {:click [[:server-files/upload-picker]]}}
         (if (= kind "workspace") "Upload workspace…" "Upload file…")]
        [:button {:type "button" :on {:click [[:server-files/close]]}} "Cancel"]]])))
