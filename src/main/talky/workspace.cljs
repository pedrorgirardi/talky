(ns talky.workspace
  (:require
   ["vscode" :as vscode]

   [cljs.reader :as edn]
   [cljs-node-io.fs :as fs]
   [cljs-node-io.core :as io]))


(defn nrepl-port! []
  (let [path (str vscode/workspace.rootPath "/.nrepl-port")]
    (when (fs/file? path)
      (js/parseInt (io/slurp path)))))


(defn socket-repl-port! []
  (let [path (str vscode/workspace.rootPath "/.shadow-cljs/socket-repl.port")]
    (when (fs/file? path)
      (js/parseInt (io/slurp path)))))