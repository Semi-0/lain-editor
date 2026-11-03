(ns quality
  "Pinned, changed-code quality checks. Checks never rewrite source files."
  (:refer-clojure :exclude [run!])
  (:require [clj-kondo.core :as kondo]
            [cljfmt.core :as fmt]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(defn git-result [& args]
  (apply shell/sh "git" args))

(defn git [& args]
  (let [{:keys [exit out err]} (apply git-result args)]
    (when-not (zero? exit)
      (throw (ex-info (str "Git command failed: " err) {:arguments args})))
    (str/trim out)))

(defn paths [output]
  (remove str/blank? (str/split output #"\u0000")))

(defn source-file? [path]
  (and (re-find #"\.(clj|cljs|cljc|edn)$" path)
       (not-any? #{"node_modules" "target" ".git" ".cpcache" ".cache"}
                 (str/split path #"/"))))

(defn arguments [args]
  (loop [remaining args options {}]
    (if-let [arg (first remaining)]
      (case arg
        "--base" (if-let [base (second remaining)]
                   (recur (nnext remaining) (assoc options :base base))
                   (throw (ex-info "--base requires a commit" {})))
        "--all" (recur (next remaining) (assoc options :all true))
        "--report-only" (recur (next remaining) (assoc options :report-only true))
        (throw (ex-info (str "Unknown quality option: " arg) {})))
      options)))

(defn base-revision [options]
  (let [requested (or (:base options) (System/getenv "LAIN_CI_BASE_SHA"))
        _ (when (and (= "1" (System/getenv "LAIN_CI_BASE_REQUIRED")) (not requested))
            (throw (ex-info "CI requires LAIN_CI_BASE_SHA from the previous verified release" {})))
        merge-base (when-not requested
                     (let [result (git-result "merge-base" "HEAD" "origin/main")]
                       (when (zero? (:exit result)) (str/trim (:out result)))))
        base (or requested merge-base "HEAD")]
    (git "rev-parse" "--verify" (str base "^{commit}"))))

(defn selected-files [base all?]
  (let [tracked (:out (git-result "ls-files" "-z"))
        changed (:out (git-result "diff" "--name-only" "--diff-filter=ACMRT" "-z" base "--"))
        untracked (:out (git-result "ls-files" "--others" "--exclude-standard" "-z"))]
    (->> (concat (paths (if all? tracked changed)) (paths untracked))
         distinct
         (filter source-file?)
         (filter #(.isFile (io/file %)))
         sort
         vec)))

(defn config [path]
  (if (.isFile (io/file path)) (edn/read-string (slurp path)) {}))

(defn lint-source [filename source]
  (binding [*in* (java.io.StringReader. source)]
    (:findings
     (kondo/run! {:lint ["-"] :filename filename
                  :cache false :repro true
                  :config (config ".clj-kondo/config.edn")}))))

(defn finding-anchor [source finding]
  (let [lines (str/split-lines source)
        row (max 0 (dec (or (:row finding) 1)))
        line (get lines row "")
        start (min (count line) (max 0 (dec (or (:col finding) 1))))
        end (if (= (:row finding) (:end-row finding))
              (min (count line) (max start (dec (or (:end-col finding) (inc (count line))))))
              (count line))
        fragment (subs line start end)]
    (try
      (str/trim (fmt/reformat-string fragment))
      (catch Exception _ (str/replace (str/trim fragment) #"\s+" " ")))))

(defn finding-key [source finding]
  [(:type finding) (:message finding) (finding-anchor source finding)])

(defn new-errors [old-source old-findings source findings]
  (loop [remaining (filter #(= :error (:level %)) findings)
         known (frequencies
                (map #(finding-key old-source %)
                     (filter #(= :error (:level %)) old-findings)))
         introduced []]
    (if-let [finding (first remaining)]
      (let [key (finding-key source finding)]
        (if (pos? (get known key 0))
          (recur (next remaining) (update known key dec) introduced)
          (recur (next remaining) known (conj introduced finding))))
      introduced)))

(defn lint-files [base files]
  (mapv
   (fn [path]
     (let [source (slurp path)
           previous (git-result "show" (str base ":" path))
           old-source (if (zero? (:exit previous)) (:out previous) "")
           old-findings (if (str/blank? old-source) [] (lint-source path old-source))
           findings (lint-source path source)]
       {:path path :findings findings
        :new-errors (new-errors old-source old-findings source findings)}))
   files))

(defn formatting [files fix?]
  (let [options (config ".cljfmt.edn")]
    (mapv
     (fn [path]
       (try
         (let [source (slurp path)
               formatted (fmt/reformat-string source options)
               changed? (not= source formatted)]
           (when (and fix? changed?) (spit path formatted))
           {:path path :changed changed?})
         (catch Exception error
           {:path path :changed false :error (.getMessage error)})))
     files)))

(defn save-report [mode report]
  (let [file (io/file "target" "quality" (str mode ".edn"))]
    (io/make-parents file)
    (spit file (str (pr-str report) "\n"))))

(defn run! [mode options]
  (let [base (base-revision options)
        files (selected-files base (:all options))
        result
        (case mode
          "lint"
          (let [results (lint-files base files)
                introduced (vec (mapcat :new-errors results))
                warnings (count (filter #(= :warning (:level %)) (mapcat :findings results)))]
            {:files results :new-errors introduced :warnings warnings
             :errors (count (filter #(= :error (:level %)) (mapcat :findings results)))
             :failed (count introduced)})
          "format-check"
          (let [results (formatting files false)]
            {:files results :failed (count (filter #(or (:changed %) (:error %)) results))})
          "format-fix"
          (let [results (formatting files true)]
            {:files results :failed (count (filter :error results))})
          (throw (ex-info (str "Unknown quality mode: " mode) {})))
        report (merge {:mode mode :base base :head (git "rev-parse" "HEAD")
                       :checked-files (count files) :report-only (boolean (:report-only options))}
                      result)]
    (save-report mode report)
    (println (str mode ": " (count files) " files; " (:failed report)
                  " failures" (when (= mode "lint") (str "; " (:errors report) " total errors; " (:warnings report) " warnings"))))
    (doseq [finding (:new-errors report)]
      (println (str (:filename finding) ":" (:row finding) ":" (:col finding)
                    ": error: " (:message finding))))
    (when (= mode "format-check")
      (doseq [file (:files report) :when (:changed file)]
        (println (str (:path file) ": formatting differs")))
      (doseq [file (:files report) :when (:error file)]
        (println (str (:path file) ": formatter error: " (:error file)))))
    (if (:report-only options) 0 (if (pos? (:failed report)) 1 0))))

(defn -main [mode & args]
  (try
    (System/exit (run! mode (arguments args)))
    (catch Exception error
      (binding [*out* *err*] (println "Quality check failed:" (.getMessage error)))
      (System/exit 2))))
