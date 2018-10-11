(ns talky.core
  (:require
   ["vscode" :as vscode]
   ["net" :as net]
   ["child_process" :as child-process]

   [talky.gui :as gui]
   [talky.document :as document]
   [talky.workspace :as workspace]

   [cljs.reader :as reader]
   [cljs.nodejs :as nodejs]
   [kitchen-async.promise :as p]
   [clojure.string :as str]))

(defn- register-command [*sys cmd]
  (let [cmd-name (-> cmd meta :cmd)
        callback (fn []
                   (js/console.log (str "[Talky] RUN COMMAND '" cmd-name "'"))

                   (try
                     (cmd *sys)
                     (catch js/Error e
                       (js/console.error (str "[Talky] FAILED TO RUN COMMAND '" cmd-name "'") e))))]

    (vscode/commands.registerCommand cmd-name callback)))

(defn- register-text-editor-command [*sys cmd]
  (let [cmd-name (-> cmd meta :cmd)
        callback (fn [editor edit args]
                   (js/console.log (str "[Talky] RUN EDITOR COMMAND '" cmd-name "'"))

                   (try
                     (cmd *sys editor edit args)
                     (catch js/Error e
                       (js/console.error (str "[Talky] FAILED TO RUN EDITOR COMMAND '" cmd-name "'") e))))]

    (vscode/commands.registerTextEditorCommand cmd-name callback)))

(defn- register-disposable [^js context ^js disposable]
  (-> (.-subscriptions context)
      (.push disposable)))

(defn talk! [*sys message]
  (js/console.log "[Talky] MESSAGE " message)

  (swap! *sys update :talky/history (fnil conj []) message)

  (.write ^js (get-in @*sys [:talky/connection :talky/socket]) (str message "\n")))

(defn- ^{:cmd "talky.connect"} connect [*sys]
  (.then (gui/show-input-box {:ignoreFocusOut true
                              :prompt "Host"
                              :value "localhost"})
         (fn [host]
           (when host
             (.then (gui/show-input-box {:ignoreFocusOut true
                                         :prompt "Port"
                                         :value (str (or (get-in @*sys [:talky/repl :talky.repl/port])
                                                         (workspace/socket-repl-port!)
                                                         5555))})
                    (fn [port]
                      (when port
                        (let [socket (doto (net/connect #js {:host host
                                                             :port (js/parseInt port)})
                                       (.once "connect" (fn []
                                                          (gui/show-information-message
                                                           (str "Talky is connected and ready to talk."))

                                                          (swap! *sys update :talky/connection assoc :talky/connected? true)))
                                       (.once "close" (fn [error?]
                                                        (gui/show-information-message
                                                         (if error?
                                                           "Talky was disconnected due an error. Sorry."
                                                           "Talky is disconnected. Talk later."))

                                                        (swap! *sys assoc :talky/connection {:talky/connected? false})))
                                       (.on "data" (fn [^js buffer]
                                                     (let [output-channel (get @*sys :talky/output-channel)
                                                           o (reader/read-string (.toString buffer "utf8"))]

                                                       (.appendLine output-channel (if (and (map? o) (:tag o))
                                                                                     (condp = (:tag o)
                                                                                       :out (str "out -> " (:val o))
                                                                                       :ret (str (:ns o) " -> " (:val o)))
                                                                                     (.toString buffer "utf8")))

                                                       (.show output-channel true)))))]
                          (swap! *sys assoc :talky/connection {:talky/socket socket})))))))))

(defn ^{:cmd "talky.disconnect"} disconnect [*sys]
  (swap! *sys update :talky/connection (fn [{:keys [talky/socket talky/connected?] :as connection}]
                                         (if (and socket connected?)
                                           (.end socket)
                                           connection))))

(defn ^{:cmd "talky.sendSelectionToREPL"} send-selection-to-repl [*sys editor edit args]
  (let [document      (.-document editor)
        selection     (.-selection editor)
        range         (vscode/Range. (.-start selection) (.-end selection))
        selected-text (.getText document range)]
    (when (get-in @*sys [:talky/connection :talky/connected?])
      (talk! *sys selected-text))))

(defn ^{:cmd "talky.switchNamespaceToCurrentFile"} switch-namespace-to-current-file [*sys editor edit args]
  (when (get-in @*sys [:talky/connection :talky/connected?])
    (talk! *sys (str "(in-ns '" (document/ns-name (.-document editor)) ")"))))

(def *sys
  (atom {}))

(defn activate [^js context]
  (let [output-channel (vscode/window.createOutputChannel "Talky")]

    (->> (register-command *sys #'connect)
         (register-disposable context))

    (->> (register-command *sys #'disconnect)
         (register-disposable context))

    (->> (register-text-editor-command *sys #'send-selection-to-repl)
         (register-disposable context))

    (->> (register-text-editor-command *sys #'switch-namespace-to-current-file)
         (register-disposable context))

    (reset! *sys {:talky/output-channel output-channel})

    (.appendLine output-channel "Talky is active. Talk nicely.\n"))

  nil)

(defn deactivate []
  (disconnect *sys))

