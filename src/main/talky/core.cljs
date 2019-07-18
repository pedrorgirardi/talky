(ns talky.core
  (:require
   ["vscode" :as vscode]
   ["net" :as net]

   [talky.window :as window]))

(defn- register-command [*sys cmd]
  (let [cmd-name (-> cmd meta :cmd)
        callback (fn []
                   (js/console.log (str "[Talky] RUN COMMAND '" cmd-name "'"))

                   (try
                     (cmd *sys)
                     (catch js/Error e
                       (js/console.error (str "[Talky] FAILED TO RUN COMMAND '" cmd-name "'") e))))]

    (-> (.-commands ^js vscode)
        (.registerCommand cmd-name callback))))

(defn- register-text-editor-command [*sys cmd]
  (let [cmd-name (-> cmd meta :cmd)
        callback (fn [editor edit args]
                   (js/console.log (str "[Talky] RUN EDITOR COMMAND '" cmd-name "'"))

                   (try
                     (cmd *sys editor edit args)
                     (catch js/Error e
                       (js/console.error (str "[Talky] FAILED TO RUN EDITOR COMMAND '" cmd-name "'") e))))]

    (-> (.-commands ^js vscode)
        (.registerTextEditorCommand cmd-name callback))))

(defn- register-disposable [^js context ^js disposable]
  (-> (.-subscriptions context)
      (.push disposable)))

(defn connect!
  [{:keys [host port config on-connect on-close on-data]
    :or {config
         {:encoder
          (fn [data]
            ;; See https://nodejs.org/api/net.html#net_socket_write_data_encoding_callback
            data)

          ;; You can also set the encoding.
          ;; See https://nodejs.org/api/net.html#net_socket_setencoding_encoding
          ;; :encoding "utf8"

          :decoder
          (fn [buffer-or-string]
            ;; See https://nodejs.org/api/net.html#net_event_data
            buffer-or-string)}

         on-connect
         (fn [socket]
           ;; Do stuff and returns nil.
           nil)

         on-close
         (fn [socket error?]
           ;; Do stuff and returns nil.
           nil)

         on-data
         (fn [socket buffer-or-string]
           ;; Do stuff and returns nil.
           nil)}
    :as this}]
  (let [socket (doto (net/connect #js {:host host :port port})
                     (.once "connect"
                            (fn []
                              (on-connect this)))
                     (.once "close"
                            (fn [error?]
                              (on-close this error?)))
                     (.on "data"
                          (fn [buffer]
                            (let [{:keys [decoder]} config]
                              (on-data this (decoder buffer))))))

        socket (if-let [encoding (:encoding config)]
                 (.setEncoding socket encoding)
                 socket)]
    {:socket socket

     :write!
     (fn write [data]
       (let [{:keys [encoder]} config]
         (.write ^js socket (encoder data))))

     :end!
     (fn []
       (.end ^js socket))}))

(defn connected? [{:keys [socket] :as connection}]
  (when socket
    (not (.-pending socket))))

(defn- ^{:cmd "talky.connect"} connect [*sys]
  (if (connected? (get @*sys :talky/connection))
    (window/show-information-message "Talky is connected.")
    (.then (window/show-input-box
            {:ignoreFocusOut true
             :prompt "Host"
             :value "localhost"})
           (fn [host]
             (when host
               (.then (window/show-input-box
                       {:ignoreFocusOut true
                        :prompt "Port"
                        :value (str 5555)})
                      (fn [port]
                        (when port
                          (let [config
                                {:encoding "utf8"

                                 :decoder
                                 (fn [data]
                                   data)

                                 :encoder
                                 (fn [data]
                                   (str data "\n"))}

                                on-connect
                                (fn [_]
                                  (window/show-information-message
                                   "Talky is connected."))

                                on-close
                                (fn [_ error?]
                                  (window/show-information-message
                                   (if error?
                                     "Talky was disconnected due an error. Sorry."
                                     "Talky is disconnected.")))

                                on-data
                                (fn [_ buffer]
                                  (let [^js output-channel (get @*sys :talky/output-channel)]
                                    (.appendLine output-channel buffer)

                                    (.show output-channel true)))

                                connection
                                (connect! {:host host
                                           :port (js/parseInt port)
                                           :config config
                                           :on-connect on-connect
                                           :on-close on-close
                                           :on-data on-data})]

                            (swap! *sys assoc :talky/connection connection))))))))))

(defn ^{:cmd "talky.disconnect"} disconnect [*sys]
  (let [{:keys [end!] :as socket-client} (get @*sys :talky/connection)]
    (if (connected? socket-client)
      (do
        (end!)
        (swap! *sys dissoc :talky/connection))
      (window/show-information-message "Talky is disconnected."))))

(defn ^{:cmd "talky.sendSelectionToREPL"} send-selection-to-repl [*sys ^js editor ^js edit ^js args]
  (let [^js document (.-document editor)
        ^js selection (.-selection editor)

        {:keys [write!] :as connection} (get @*sys :talky/connection)]
    (if (connected? connection)
      (write! (.getText document selection))
      (window/show-information-message "Talky is disconnected."))))

(def *sys
  (atom {}))


;; How to start a Clojure socket-based REPL
;; clj -J-Dclojure.server.repl="{:port 5555 :accept clojure.core.server/repl}"

(defn activate [^js context]
  (let [^js output-channel (-> (.-window ^js vscode)
                               (.createOutputChannel "Talky"))]

    (->> (register-command *sys #'connect)
         (register-disposable context))

    (->> (register-command *sys #'disconnect)
         (register-disposable context))

    (->> (register-text-editor-command *sys #'send-selection-to-repl)
         (register-disposable context))

    (reset! *sys {:talky/output-channel output-channel})

    (.appendLine output-channel "Talky is active.\n"))

  nil)

(defn deactivate []
  (let [{:keys [end!] :as connection} (get @*sys :talky/connection)]
    (when (connected? connection)
      (end!))))

