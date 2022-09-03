(ns frontend.handler.global-config
  "This ns is a system component that encapsulates global config functionality
  and defines how to start and stop it. Unlike repo config, this manages a directory
for configuration. This app component depends on a repo."
  (:require [frontend.config :as config]
            [frontend.fs :as fs]
            [frontend.handler.common.file :as file-common-handler]
            [frontend.state :as state]
            [cljs.reader :as reader]
            [frontend.db :as db]
            [promesa.core :as p]
            [shadow.resource :as rc]
            [electron.ipc :as ipc]
            ["path" :as path]))

;; Use defonce to avoid broken state on dev reload
;; Also known as home directory a.k.a. '~'
(defonce root-dir
  (atom nil))

(defn global-config-dir
  []
  (path/join @root-dir "config"))

(defn global-config-path
  []
  (path/join @root-dir "config" "config.edn"))

(defn- set-global-config-state!
  [content]
  (let [config (reader/read-string content)]
    (state/set-global-config! config)
    config))

(def default-content (rc/inline "global-config.edn"))

(defn- create-global-config-file-if-not-exists
  [repo-url]
  (let [config-dir (global-config-dir)
        config-path (global-config-path)]
    (p/let [_ (fs/mkdir-if-not-exists config-dir)
            file-exists? (fs/create-if-not-exists repo-url config-dir config-path default-content)]
           (when-not file-exists?
             (file-common-handler/reset-file! repo-url config-path default-content)
             (set-global-config-state! default-content)))))

(defn- get-global-config-content
  [repo-url]
  (db/get-file repo-url (global-config-path)))

(defn restore-global-config!
  "Sets global config state from db"
  [repo-url]
  (let [config-content (get-global-config-content repo-url)]
    (set-global-config-state! config-content)))

(defn watch-for-global-config-dir!
  "Watches global config dir for given repo/db"
  [repo]
  (let [dir (global-config-dir)
        repo-dir (config/get-repo-dir repo)]
    ;; Don't want multiple file watchers, especially when switching graphs
    (fs/unwatch-dir! dir)
    ;; Even a global dir needs to know it's current graph in order to send
    ;; change events to the right window and graph db
    (fs/watch-dir! dir {:current-repo-dir repo-dir})))

(defn start
  "This component has four responsibilities on start:
- Fetch root-dir for later use with config paths
- Manage db and ui state of global config
- Create a global config dir and file if it doesn't exist
- Start a file watcher for global config dir"
  [{:keys [repo]}]
  (p/let [root-dir' (ipc/ipc "getLogseqDotDirRoot")
          _ (reset! root-dir root-dir')
          _ (restore-global-config! repo)
          _ (create-global-config-file-if-not-exists repo)
          _ (watch-for-global-config-dir! repo)]))