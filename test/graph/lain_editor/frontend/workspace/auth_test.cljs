(ns graph.lain-editor.frontend.workspace.auth-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [graph.lain-editor.frontend.workspace.effects :as effects]
            [graph.lain-editor.frontend.workspace.model :as model]
            [graph.lain-editor.frontend.workspace.state :as s]))

(defn response [status result]
  #js {:status status :ok (= status 200)
       :json #(js/Promise.resolve (clj->js {:ok true :result result}))})

(deftest expiry-cancels-poll-and-mutation-preserves-drafts-and-never-replays
  (async done
         (let [original-fetch (.-fetch js/globalThis) original-state @s/source
               calls (atom []) resolvers (atom [])
               document (model/initial-workspace) id (:id (first (:panes document)))
               draft (model/update-pane document id assoc :source "unsaved source" :extensionDraft "unsaved extension")
               external (js/AbortController.)]
           (effects/dispose!)
           (reset! s/connection {:status :connected})
           (reset! s/workspace draft)
           (swap! s/ui assoc :file-links {id {:path "draft.lain" :source "previous source" :version "v1"}})
           (reset! s/results {id {:response :last-good}})
           (set! (.-fetch js/globalThis)
                 (fn [url options]
                   (swap! calls conj [url options])
                   (js/Promise. (fn [resolve _] (swap! resolvers conj resolve)))))
           (let [late (-> (effects/api! {:op "inspect" :pane id} (.-signal external))
                          (.then (fn [_] (is false "A late response must not be applied")))
                          (.catch #(is (effects/cancelled? %))))
                 run (effects/run! id "run")]
             (-> (js/Promise.resolve nil)
                 (.then (fn [_]
                          (is (= 2 (count @calls)))
                          ((second @resolvers) (response 401 nil))
                          run))
                 (.then (fn [_]
                          (is (= :expired (:status @s/connection)))
                          (is (false? @s/busy?))
                          (is (every? #(.-aborted (.-signal (second %))) @calls)
                              "All requests, including callers with external signals, are cancelled")
                          (is (= draft @s/workspace))
                          (is (= {id {:response :last-good}} @s/results))
                          ((first @resolvers) (response 200 {:stale true}))
                          late))
                 (.then (fn [_]
                          (-> (effects/api! {:op "workspace-poll"})
                              (.catch #(is (effects/cancelled? %))))))
                 (.then (fn [_]
                          (is (= 2 (count @calls)) "Paused polls never reach fetch")
                          (let [resume (effects/resume!)]
                            (is (= "/auth/session" (first (last @calls))))
                            ((last @resolvers) (response 200 {:authenticated true}))
                            resume)))
                 (.then (fn [_]
                          (is (effects/connected?))
                          (is (= 3 (count @calls)) "Resume sends only the session check")
                          (is (= draft @s/workspace))
                          (is (= {id {:response :last-good}} @s/results))
                          (let [poll (-> (effects/api! {:op "workspace-poll"})
                                         (.catch #(is (effects/cancelled? %))))]
                            ((last @resolvers) (response 403 nil))
                            poll)))
                 (.then (fn [_]
                          (is (= :forbidden (:status @s/connection)))
                          (reset! s/connection {:status :connected})
                          (let [poll (-> (effects/api! {:op "workspace-poll"})
                                         (.catch #(is (re-find #"Backend" (.-message %)))))]
                            ((last @resolvers) (response 502 nil))
                            poll)))
                 (.then (fn [_] (is (effects/connected?) "Backend failure does not expire user authentication")))
                 (.catch (fn [error] (is false (str error))))
                 (.finally (fn []
                             (effects/dispose!)
                             (set! (.-fetch js/globalThis) original-fetch)
                             (reset! s/source original-state)
                             (done))))))))
