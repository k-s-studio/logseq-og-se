(ns electron.git
  "Git integration. A graph is bound to an existing git repo through a local
  binding file inside the graph; its history lives on the single ref
  refs/logseq-og-se/<name> of that repo and never touches the repo's HEAD,
  index, branches or working tree.

  Every graph command runs against a private git dir (the shadow dir) whose
  `commondir` points at the repo, so refs and objects are shared, and whose
  HEAD is rewritten to the graph's ref before each command. Git is the one on
  PATH."
  (:require ["child_process" :as child-process]
            ["crypto" :as crypto]
            ["fs-extra" :as fs]
            ["path" :as node-path]
            [cljs.reader :as reader]
            [clojure.string :as string]
            [electron.logger :as logger]
            [electron.state :as state]
            [electron.utils :as utils]
            [electron.window :as win]
            [promesa.core :as p]))

(def log-error (partial logger/error "[Git]"))

(def ref-namespace "refs/logseq-og-se/")

(def binding-rpath
  "Graph-relative path of the binding file. It starts with a dot so Logseq's
  graph parser ignores it, and it is kept out of the ref."
  "logseq/.git-binding.edn")

(def ^:private not-bound-error
  "This graph is not bound to a git repo. Bind one in Settings > Version control.")

(def ^:private git-not-found-error
  "Git was not found. Install Git and make sure `git` is on PATH.")

;; ---------------------------------------------------------------------------
;; Running git

(defn- exec
  "Runs the system git. Resolves a JS object {stdout, stderr, exitCode} for any
  exit code (the same contract dugite had) and rejects only when git cannot be
  started or its output cannot be collected."
  [args cwd]
  (p/create
   (fn [resolve reject]
     (child-process/execFile
      "git" (clj->js args)
      #js {:cwd cwd :maxBuffer js/Infinity :windowsHide true :encoding "utf8"}
      (fn [^js err stdout stderr]
        (cond
          (nil? err)
          (resolve #js {:stdout stdout :stderr stderr :exitCode 0})

          (number? (.-code err))
          (resolve #js {:stdout stdout :stderr stderr :exitCode (.-code err)})

          (= "ENOENT" (.-code err))
          (reject git-not-found-error)

          :else
          (reject (str err))))))))

(defn- exec!
  "Like `exec` but resolves stdout and rejects stderr on a non-zero exit."
  [args cwd]
  (p/let [^js result (exec args cwd)]
    (if (zero? (.-exitCode result))
      (.-stdout result)
      (p/rejected (string/trim (.-stderr result))))))

;; ---------------------------------------------------------------------------
;; Binding file

(defn- binding-file
  [graph-path]
  (node-path/join graph-path binding-rpath))

(defn read-binding
  "Returns {:repo :ref} from the graph's binding file, or nil. :repo is relative
  to the graph when the graph sits inside the repo, absolute otherwise."
  [graph-path]
  (try
    (let [f (binding-file graph-path)]
      (when (fs/existsSync f)
        (let [m (reader/read-string (str (fs/readFileSync f)))]
          (when (and (map? m)
                     (string? (:repo m)) (not (string/blank? (:repo m)))
                     (string? (:ref m)) (not (string/blank? (:ref m))))
            (select-keys m [:repo :ref])))))
    (catch :default e
      (log-error "Invalid binding file" e)
      nil)))

(defn- write-binding!
  [graph-path m]
  (let [f (binding-file graph-path)]
    (fs/ensureDirSync (node-path/dirname f))
    (fs/writeFileSync f (str ";; Local git binding of this graph. It is not committed to the ref.\n"
                             (pr-str m) "\n"))))

;; ---------------------------------------------------------------------------
;; Context: shadow git dir per (repo, ref)

(defn- common-dir
  "Absolute git common dir of the repo at `path`."
  [path]
  (p/let [out (exec! ["rev-parse" "--path-format=absolute" "--git-common-dir"] path)]
    (node-path/resolve (string/trim out))))

(defn- write-if-changed!
  [f content]
  (when-not (and (fs/existsSync f) (= content (str (fs/readFileSync f))))
    (fs/writeFileSync f content)))

(defn- ensure-shadow-dir!
  [common ref-name]
  (let [key (-> (crypto/createHash "md5")
                (.update (str (string/lower-case common) "\n" ref-name))
                (.digest "hex"))
        dir (node-path/join (utils/get-ls-dotdir-root) "git-shadow" key)]
    (fs/ensureDirSync dir)
    ;; HEAD is reset on every call, so whatever a command did to it, the next
    ;; one is back on the graph's ref.
    (write-if-changed! (node-path/join dir "HEAD") (str "ref: " ref-namespace ref-name "\n"))
    (write-if-changed! (node-path/join dir "commondir") (str common "\n"))
    dir))

(defn- resolve-context
  "Resolves the graph's git context, or nil when the graph is not bound."
  [graph-path]
  (when-let [{:keys [repo ref]} (and graph-path (fs/existsSync graph-path) (read-binding graph-path))]
    (let [repo-path (node-path/resolve graph-path repo)]
      (if-not (fs/existsSync repo-path)
        (p/rejected (str "The bound git repo does not exist: " repo-path))
        (p/let [common (common-dir repo-path)]
          {:graph graph-path
           :ref ref
           :git-dir (ensure-shadow-dir! common ref)})))))

(defn- context-args
  [{:keys [git-dir graph]} args]
  (concat [(str "--git-dir=" git-dir) (str "--work-tree=" graph)
           "-c" "core.quotepath=false" "-c" "core.safecrlf=false"]
          args))

(defn- run-in-context!
  [ctx args]
  (exec! (context-args ctx args) (:graph ctx)))

;; ---------------------------------------------------------------------------
;; Public commands

(defn run-git!
  "Runs `args` against the graph's ref. Resolves stdout, rejects stderr."
  [graph-path args]
  (if-let [ctx (resolve-context graph-path)]
    (p/let [ctx ctx] (run-in-context! ctx args))
    (p/rejected not-bound-error)))

(defn run-git2!
  "Runs `args` against the graph's ref. Resolves {stdout, stderr, exitCode}
  for the plugin API; an unbound graph or a missing git is reported the same
  way, with exitCode 128."
  [graph-path args]
  (let [failed (fn [msg] #js {:stdout "" :stderr (str msg) :exitCode 128})]
    (if-let [ctx (resolve-context graph-path)]
      (-> (p/let [ctx ctx]
            (exec (context-args ctx (js->clj args)) graph-path))
          (p/catch failed))
      (p/resolved (failed not-bound-error)))))

(defn- add-all!
  [ctx]
  (p/do!
   (-> (run-in-context! ctx ["add" "-A" "--ignore-errors" "."])
       (p/catch (fn [error]
                  (let [error (string/lower-case (str error))]
                    (if (or (string/includes? error "permission denied")
                            (string/includes? error "index.lock': file exists"))
                      (log-error error)
                      (p/rejected error))))))
   ;; In case the repo's exclude entry was removed by hand.
   (run-in-context! ctx ["rm" "-q" "--cached" "--ignore-unmatch" "--" binding-rpath])))

(defn- commit-graph!
  [graph-path message]
  (when-let [ctx (resolve-context graph-path)]
    (p/let [ctx ctx
            _ (add-all! ctx)
            status (run-in-context! ctx ["status" "--porcelain"])]
      (when-not (string/blank? status)
        (run-in-context! ctx ["commit" "-q" "-m" message])))))

(defn- notify-graph!
  [graph-path kind payload]
  (utils/send-to-renderer (or (first (win/get-graph-all-windows graph-path))
                              (utils/get-focused-window))
                          kind payload))

(defn add-all-and-commit-single-graph!
  [graph-path message]
  (let [message (if (string/blank? message)
                  "Auto saved by Logseq"
                  message)]
    (-> (commit-graph! graph-path message)
        (p/catch (fn [error]
                   (let [error (str error)]
                     ;; Prefer a window showing this graph: background auto
                     ;; commits usually run with no window focused, and the
                     ;; identity form writes to the repo of its window's graph.
                     (when-not (string/blank? error)
                       (if (string/includes? error "Author identity unknown")
                         (notify-graph! graph-path "setGitUsernameAndEmail" {:type "git"})
                         (notify-graph! graph-path "notification" {:type "error"
                                                                 :payload (str error "\nIf you don't want to see those errors or don't need git, you can disable the \"Git auto commit\" feature on Settings > Version control.")})))))))))

(defn add-all-and-commit!
  ([]
   (add-all-and-commit! nil))
  ([message]
   (doseq [path (state/get-all-graph-paths)] (add-all-and-commit-single-graph! path message))))

(defn short-status!
  [graph-path]
  (if-let [ctx (resolve-context graph-path)]
    (p/let [ctx ctx
            _ (add-all! ctx)]
      (run-in-context! ctx ["status" "--porcelain"]))
    (p/rejected not-bound-error)))

(defonce quotes-regex #"\"[^\"]+\"")
(defn wrapped-by-quotes?
  [v]
  (and (string? v) (>= (count v) 2) (= "\"" (first v) (last v))))

(defn unquote-string
  [v]
  (string/trim (subs v 1 (dec (count v)))))

(defn- split-args
  [s]
  (let [quotes (re-seq quotes-regex s)
        non-quotes (string/split s quotes-regex)
        col (if (seq quotes)
              (concat (interleave non-quotes quotes)
                      (drop (count quotes) non-quotes))
              non-quotes)]
    (->> col
         (map (fn [s]
                (if (wrapped-by-quotes? s)
                  [(unquote-string s)]
                  (string/split s #"\s"))))
         (flatten)
         (remove string/blank?))))

(defn raw!
  [graph-path args]
  (let [args (if (string? args)
               (split-args args)
               args)
        error-handler (fn [error]
                        (when-not (string/blank? (str error))
                          (let [error (str (first args) " error: " error)]
                            (utils/send-to-renderer "notification" {:type "error"
                                                                    :payload error}))
                          (p/rejected error)))]
    (->
     (if-let [ctx (resolve-context graph-path)]
       (p/let [ctx ctx
               _ (when (= (first args) "commit")
                   (add-all! ctx))]
         (run-in-context! ctx args))
       (p/rejected not-bound-error))
     (p/catch error-handler))))

;; ---------------------------------------------------------------------------
;; Binding

(defn sanitize-ref-name
  "Turns a folder name into a single-level ref name component."
  [s]
  (let [s (-> (str s)
              (string/replace #"[\x00-\x20\x7f~^:?*\[\\/]+" "-")
              (string/replace "@{" "-")
              (string/replace #"\.{2,}" "-")
              (string/replace #"-{2,}" "-")
              (string/replace #"^[.\-]+" "")
              (string/replace #"(\.lock|\.|-)+$" ""))]
    (if (or (string/blank? s) (= s "@")) "graph" s)))

(defn get-binding
  "Binding info for the settings page."
  [graph-path]
  (let [b (read-binding graph-path)]
    (cond-> {:graph graph-path
             :suggested-ref (sanitize-ref-name (node-path/basename graph-path))}
      b (assoc :repo (:repo b)
               :repo-path (node-path/resolve graph-path (:repo b))
               :ref (:ref b)))))

(defn- path-key
  [p]
  (let [p (node-path/resolve p)]
    (if utils/win32? (string/lower-case p) p)))

(defn- inside?
  "Graph-relative-to-root path when `child` is strictly inside `root`."
  [root child]
  (let [rel (node-path/relative root child)]
    (when (and (not (string/blank? rel))
               (not (string/starts-with? rel ".."))
               (not (node-path/isAbsolute rel)))
      rel)))

(defn- exclude-pattern
  "An anchored gitignore pattern matching exactly the directory `rel`."
  [rel]
  (str "/"
       (-> (string/replace rel "\\" "/")
           (string/replace #"([*?\[\\!#])" "\\$1")
           (string/replace #" $" "\\ "))
       "/"))

(defn- add-excludes!
  [common patterns]
  (let [f (node-path/join common "info" "exclude")
        existing (if (fs/existsSync f) (str (fs/readFileSync f)) "")
        lines (set (map string/trim (string/split-lines existing)))
        missing (remove lines patterns)]
    (when (seq missing)
      (fs/ensureDirSync (node-path/dirname f))
      (fs/appendFileSync f (str (when-not (or (string/blank? existing)
                                              (string/ends-with? existing "\n"))
                                  "\n")
                                "# Logseq-OG-SE graph\n"
                                (string/join "\n" missing) "\n")))))

(defn- validate-ref-name
  [common ref-name]
  (cond
    (string/blank? ref-name)
    (p/resolved "Ref name is required.")

    (string/includes? ref-name "/")
    (p/resolved "Ref name must be a single level, without \"/\".")

    :else
    (p/let [^js fmt (exec ["check-ref-format" (str ref-namespace ref-name)] common)]
      (if-not (zero? (.-exitCode fmt))
        "Not a valid git ref name."
        (p/let [out (exec! ["for-each-ref" "--format=%(refname)" ref-namespace] common)
                wanted (string/lower-case (str ref-namespace ref-name))
                taken? (some (fn [r]
                               (let [r (string/lower-case r)]
                                 (or (= r wanted) (string/starts-with? r (str wanted "/")))))
                             (string/split-lines out))]
          (when taken?
            (str (str ref-namespace ref-name) " already exists in this repo. Choose another name.")))))))

(defn bind!
  "Binds the graph to the repo at `repo` (any path inside it) with the ref
  name `ref`. Resolves {:ok true :warnings [...]} or {:errors {:repo :ref}}."
  [graph-path {:keys [repo ref]}]
  (let [repo (some-> repo string/trim)
        ref-name (some-> ref string/trim)]
    (cond
      (or (string/blank? graph-path) (not (fs/existsSync graph-path)))
      (p/resolved {:errors {:repo "The graph directory does not exist."}})

      (string/blank? repo)
      (p/resolved {:errors {:repo "Git repo path is required."}})

      (not (fs/existsSync (node-path/resolve graph-path repo)))
      (p/resolved {:errors {:repo "The path does not exist."}})

      :else
      (->
       (p/let [repo-input (node-path/resolve graph-path repo)
               ^js common-r (exec ["rev-parse" "--path-format=absolute" "--git-common-dir"] repo-input)]
         (if-not (zero? (.-exitCode common-r))
           {:errors {:repo "Not inside a git repo."}}
           (p/let [common (node-path/resolve (string/trim (.-stdout common-r)))
                   ^js top-r (exec ["rev-parse" "--show-toplevel"] repo-input)
                   toplevel (when (zero? (.-exitCode top-r))
                              (node-path/resolve (string/trim (.-stdout top-r))))
                   root (or toplevel common)
                   graph-rel (when toplevel (inside? toplevel graph-path))
                   repo-error (cond
                                (and toplevel (= (path-key toplevel) (path-key graph-path)))
                                "The graph directory cannot be the repo root."

                                (or (inside? graph-path root) (inside? graph-path common))
                                "The repo cannot be inside the graph directory.")
                   ref-error (validate-ref-name common ref-name)]
             (if (or repo-error ref-error)
               {:errors (cond-> {}
                          repo-error (assoc :repo repo-error)
                          ref-error (assoc :ref ref-error))}
               (p/let [_ (add-excludes! common (cond-> []
                                                 graph-rel (conj (exclude-pattern graph-rel))
                                                 true (conj binding-rpath)))
                       tracked (when graph-rel
                                 (-> (exec! ["ls-files" "--" (string/replace graph-rel "\\" "/")] toplevel)
                                     (p/catch (constantly nil))))
                       stored-repo (string/replace (if graph-rel
                                                     (node-path/relative graph-path toplevel)
                                                     root)
                                                   "\\" "/")]
                 (write-binding! graph-path {:repo stored-repo :ref ref-name})
                 {:ok true
                  :warnings (cond-> []
                              (not (string/blank? tracked))
                              (conj (str "The graph directory is already tracked by this repo's branches, "
                                         "so the exclude entry does not apply to those files. "
                                         "To stop tracking them, run: git rm -r --cached \""
                                         (string/replace graph-rel "\\" "/") "\"")))})))))
       (p/catch (fn [e] {:errors {:repo (str e)}}))))))

(defn unbind!
  [graph-path]
  (let [f (binding-file graph-path)]
    (when (fs/existsSync f)
      (fs/unlinkSync f))
    true))

;; ---------------------------------------------------------------------------
;; Auto commit

(defonce auto-commit-interval (atom nil))
(defn- auto-commit-tick-fn
  []
  (when (state/git-auto-commit-enabled?)
    (add-all-and-commit!)))

(defn configure-auto-commit!
  "Configure auto commit interval, reentrantable"
  []
  (when @auto-commit-interval
    (swap! auto-commit-interval js/clearInterval))
  (when (state/git-auto-commit-enabled?)
    (let [seconds (state/get-git-commit-seconds)
          millis (if (int? seconds)
                   (* seconds 1000)
                   6000)]
      (logger/info ::set-auto-commit-interval seconds)
      (js/setTimeout add-all-and-commit! 100)
      (reset! auto-commit-interval (js/setInterval auto-commit-tick-fn millis)))))

(defn before-graph-close-hook!
  []
  (when (and (state/git-auto-commit-enabled?)
             (state/git-commit-on-close-enabled?))
    (add-all-and-commit!)))
