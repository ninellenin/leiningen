(ns leiningen.core.classpath
  "Calculate project classpaths by resolving dependencies via Aether."
  (:require [cemerick.pomegranate.aether :as aether]
            [cemerick.pomegranate :as pomegranate]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [leiningen.core.user :as user]
            [leiningen.core.utils :as utils])
  (:import (java.util.jar JarFile)
           (org.sonatype.aether.resolution DependencyResolutionException)))

;; Basically just for re-throwing a more comprehensible error.
(defn- read-dependency-project [root dep]
  (let [project-file (io/file root "checkouts" dep "project.clj")]
    (if (.exists project-file)
      (let [project (.getAbsolutePath project-file)]
        ;; TODO: core.project and core.classpath currently rely upon each other *uk*
        (require 'leiningen.core.project)
        (try ((resolve 'leiningen.core.project/read) project [])
             (catch Exception e
               (throw (Exception. (format "Problem loading %s" project) e)))))
      (println
       "WARN ignoring checkouts directory" dep
       "as it does not contain a project.clj file."))))

(defn- checkout-dep-paths [project dep-project]
  ;; can't mapcat here since :checkout-deps-shares points to vectors and strings
  (flatten (map #(% dep-project) (:checkout-deps-shares project))))

(defn- checkout-deps-paths
  "Checkout dependencies are used to place source for a dependency
  project directly on the classpath rather than having to install the
  dependency and restart the dependent project."
  [project]
  (apply concat (for [dep (.list (io/file (:root project) "checkouts"))
                      :let [dep-project (read-dependency-project
                                         (:root project) dep)]
                      :when dep-project]
                  (checkout-dep-paths project dep-project))))

(defn extract-native-deps [files native-path native-prefixes]
  (doseq [file files
          :let [native-prefix (get native-prefixes file "native/")
                jar (JarFile. file)]
          entry (enumeration-seq (.entries jar))
          :when (.startsWith (.getName entry) native-prefix)]
    (let [f (io/file native-path (subs (.getName entry) (count native-prefix)))]
      (if (.isDirectory entry)
        (.mkdirs f)
        (do (.mkdirs (.getParentFile f))
            (io/copy (.getInputStream jar entry) f))))))

(defn when-stale
  "Call f with args when keys in project.clj have changed since the last
  run. Stores value of project keys in stale directory inside :target-path.
  Because multiple callers may check the same keys, you must also provide a
  token to keep your stale value separate. Returns true if the code was executed
  and nil otherwise."
  [token keys project f & args]
  (let [file (io/file (:target-path project) "stale"
                      (str (name token) "." (str/join "+" (map name keys))))
        current-value (pr-str (map (juxt identity project) keys))
        old-value (and (.exists file) (slurp file))]
    (when (and (:name project) (:target-path project)
               (not= current-value old-value))
      (apply f args)
      (.mkdirs (.getParentFile file))
      (spit file (doall current-value))
      true)))

(defn add-repo-auth
  "Repository credentials (a map containing some of
  #{:username :password :passphrase :private-key-file}) are discovered
  from:

  1. Looking up the repository URL in the ~/.lein/credentials.clj.gpg map
  2. Scanning that map for regular expression keys that match the
     repository URL.

  So, a credentials map that contains an entry:

    {#\"http://maven.company.com/.*\" {:username \"abc\" :password \"xyz\"}}

  would be applied to all repositories with URLs matching the regex key
  that didn't have an explicit entry."
  [[id repo]]
  [id (-> repo user/profile-auth user/resolve-credentials)])

(defn get-non-proxy-hosts []
  (let [system-no-proxy (System/getenv "no_proxy")
        lein-no-proxy (System/getenv "http_no_proxy")]
    (if (and (empty? lein-no-proxy) (not-empty system-no-proxy))
      (->> (str/split system-no-proxy #",")
           (map #(str "*" %))
           (str/join "|"))
      (System/getenv "http_no_proxy"))))

(defn get-proxy-settings
  "Returns a map of the JVM proxy settings"
  ([] (get-proxy-settings "http_proxy"))
  ([key]
     (if-let [proxy (System/getenv key)]
       (let [url (utils/build-url proxy)
             user-info (.getUserInfo url)
             [username password] (and user-info (.split user-info ":"))]
         {:host (.getHost url)
          :port (.getPort url)
          :username username
          :password password
          :non-proxy-hosts (get-non-proxy-hosts)}))))

(defn- update-policies [update checksum [repo-name opts]]
  [repo-name (merge {:update (or update :daily)
                     :checksum (or checksum :fail)} opts)])

(defn- print-failures [e]
  (doseq [result (.getArtifactResults (.getResult e))
          :when (not (.isResolved result))
          exception (.getExceptions result)]
    (println (.getMessage exception)))
  (doseq [ex (.getCollectExceptions (.getResult e))
          ex2 (.getExceptions (.getResult ex))]
    (println (.getMessage ex2))))

(defn- root-cause [e]
  (last (take-while identity (iterate (memfn getCause) e))))

(def ^:private get-dependencies
  (memoize
   (fn [dependencies-key {:keys [repositories local-repo offline? update
                                 checksum mirrors]
                          :as project}
        & {:keys [add-classpath? repository-session-fn]}]
     {:pre [(every? vector? (get project dependencies-key))]}
     (try
       ((if add-classpath?
          pomegranate/add-dependencies
          aether/resolve-dependencies)
        :repository-session-fn repository-session-fn
        :local-repo local-repo
        :offline? offline?
        :repositories (->> repositories
                           (map add-repo-auth)
                           (map (partial update-policies update checksum)))
        :coordinates (get project dependencies-key)
        :mirrors (->> mirrors
                      (map add-repo-auth)
                      (map (partial update-policies update checksum)))
        :transfer-listener
        (bound-fn [e]
          (let [{:keys [type resource error]} e]
            (let [{:keys [repository name size trace]} resource]
              (let [aether-repos (if trace (.getRepositories (.getData trace)))]
                (case type
                  :started
                  (if-let [repo (first (filter
                                        #(or (= (.getUrl %) repository)
                                             ;; sometimes the "base" url
                                             ;; doesn't have a slash on it
                                             (= (str (.getUrl %) "/") repository))
                                        aether-repos))]
                    (println "Retrieving"
                             name
                             "from"
                             (.getId repo))
                    ;;else case happens for metadata files
                    )
                  nil)))))
        :proxy (get-proxy-settings))
       (catch DependencyResolutionException e
         (binding [*out* *err*]
           ;; Cannot recur from catch/finally so have to put this in its own defn
           (print-failures e)
           (println "This could be due to a typo in :dependencies or network issues.")
           #_(when-not (some #(= "https://clojars.org/repo/" (:url (second %))) repositories)
               (println "It's possible the specified jar is in the old Clojars Classic repo.")
               (println "If so see https://github.com/ato/clojars-web/wiki/Releases.")))
         (throw (ex-info "Could not resolve dependencies" {:suppress-msg true
                                                           :exit-code 1} e)))
       (catch Exception e
         (if (and (instance? java.net.UnknownHostException (root-cause e))
                  (not offline?))
           (get-dependencies dependencies-key (assoc project :offline? true))
           (throw e)))))))



(defn- get-original-dependency
  "Return a match to dep (a single dependency vector) in
  dependencies (a dependencies vector, such as :dependencies in
  project.clj). Matching is done on the basis of the group/artifact id
  and version."
  [dep dependencies]
  (some (fn [v] ; not certain if this is the best matching fn
          (when (= (subvec dep 0 2) (subvec v 0 2 )) v))
        dependencies))

(defn get-native-prefix
  "Return the :native-prefix of a dependency vector, or nil."
  [[id version & {:as opts}]]
  (get opts :native-prefix))

(defn- get-native-prefixes
  "Given a dependencies vector (such as :dependencies in project.clj)
  and a dependencies tree, as returned by get-dependencies, return a
  mapping from the Files those dependencies entail to
  the :native-prefix, if any, referenced in the dependencies vector."
  [dependencies dependencies-tree]
  (let [override-deps (->> (map #(get-original-dependency
                                  % dependencies)
                                (keys dependencies-tree))
                           (map get-native-prefix))]
    (->> (aether/dependency-files dependencies-tree)
         (#(map vector % override-deps))
         (filter second)
         (filter #(re-find #"\.(jar|zip)$" (.getName (first %))))
         (into {}))))

(defn resolve-dependencies
  "Delegate dependencies to pomegranate. This will ensure they are
  downloaded into ~/.m2/repository and that native components of
  dependencies have been extracted to :native-path. If :add-classpath?
  is logically true, will add the resolved dependencies to Leiningen's
  classpath.

  Returns a seq of the dependencies' files."
  [dependencies-key {:keys [repositories native-path] :as project} & rest]
  (let [dependencies-tree
        (apply get-dependencies dependencies-key project rest)
        jars (->> dependencies-tree
                  (aether/dependency-files)
                  (filter #(re-find #"\.(jar|zip)$" (.getName %))))
        native-prefixes (get-native-prefixes (get project dependencies-key)
                                             dependencies-tree)]
    (when-not (= :plugins dependencies-key)
      (or (when-stale :extract-native [dependencies-key] project
                      extract-native-deps jars native-path native-prefixes)
          ;; Always extract native deps from SNAPSHOT jars.
          (extract-native-deps (filter #(re-find #"SNAPSHOT" (.getName %)) jars)
                               native-path
                               native-prefixes)))
    jars))

(defn dependency-hierarchy
  "Returns a graph of the project's dependencies."
  [dependencies-key project & options]
  (aether/dependency-hierarchy
   (get project dependencies-key)
   (apply get-dependencies dependencies-key project options)))

(defn- normalize-path [root path]
  (let [f (io/file path)]
    (.getAbsolutePath (if (.isAbsolute f) f (io/file root path)))))

(defn ext-dependency?
  "Should the given dependency be loaded in the extensions classloader?"
  [dep]
  (second
   (some #(if (= :ext (first %)) dep)
         (partition 2 dep))))

(defn ext-classpath
  "Classpath of the extensions dependencies in project as a list of strings."
  [project]
  (seq
   (->> (filter ext-dependency? (:dependencies project))
        (assoc project :dependencies)
        (resolve-dependencies :dependencies)
        (map (memfn getAbsolutePath)))))

(defn get-classpath
  "Return the classpath for project as a list of strings."
  [project]
  (for [path (concat (:test-paths project)
                     (:source-paths project)
                     (:resource-paths project)
                     [(:compile-path project)]
                     (checkout-deps-paths project)
                     (for [dep (resolve-dependencies :dependencies project)]
                       (.getAbsolutePath dep)))
        :when path]
    (normalize-path (:root project) path)))
