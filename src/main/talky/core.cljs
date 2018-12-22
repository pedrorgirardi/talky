(ns talky.core
  (:require
   ["vscode" :as vscode]
   ["net" :as net]

   [talky.gui :as gui]
   [kitchen-async.promise :as p]))

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

(defn make-socket-client
  [{:socket/keys [host port config on-connect on-close on-data]
    :or {config
         {:socket/encoder
          (fn [data]
            ;; See https://nodejs.org/api/net.html#net_socket_write_data_encoding_callback
            data)

          ;; You can also set the encoding.
          ;; See https://nodejs.org/api/net.html#net_socket_setencoding_encoding
          ;; :socket/encoding "utf8"

          :socket/decoder
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
    :as socket}]
  (let [net-socket (doto (net/connect #js {:host host :port port})
                     (.once "connect"
                            (fn []
                              (on-connect socket)))
                     (.once "close"
                            (fn [error?]
                              (on-close socket error?)))
                     (.on "data"
                          (fn [buffer]
                            (let [{:socket/keys [decoder]} config]
                              (on-data socket (decoder buffer))))))

        net-socket (if-let [encoding (:socket/encoding config)]
                     (.setEncoding net-socket encoding)
                     net-socket)]
    {:socket.api/write!
     (fn write [data]
       (let [{:socket/keys [encoder]} config]
         (.write ^js net-socket (encoder data))))

     :socket.api/end!
     (fn []
       (.end ^js net-socket))

     :socket.api/connected?
     (fn []
       (not (.-pending ^js net-socket)))}))

(defn- ^{:cmd "talky.connect"} connect [*sys]
  (.then (gui/show-input-box
          {:ignoreFocusOut true
           :prompt "Host"
           :value "localhost"})
         (fn [host]
           (when host
             (.then (gui/show-input-box
                     {:ignoreFocusOut true
                      :prompt "Port"
                      :value (str 5555)})
                    (fn [port]
                      (when port
                        (let [config
                              {:socket/encoding "utf8"

                               :socket/decoder
                               (fn [data]
                                 data)

                               :socket/encoder
                               (fn [data]
                                 (str data "\n"))}

                              on-connect
                              (fn [_]
                                (gui/show-information-message
                                 (str "Talky is connected.")))

                              on-close
                              (fn [_ error?]
                                (gui/show-information-message
                                 (if error?
                                   "Talky was disconnected due an error. Sorry."
                                   "Talky is disconnected.")))

                              on-data
                              (fn [_ buffer]
                                (let [^js output-channel (get @*sys :talky/output-channel)]
                                  (.appendLine output-channel buffer)

                                  (.show output-channel true)))

                              socket-client
                              (make-socket-client
                               #:socket {:host host
                                         :port (js/parseInt port)
                                         :config config
                                         :on-connect on-connect
                                         :on-close on-close
                                         :on-data on-data})]

                          (swap! *sys assoc :talky/socket-client socket-client)))))))))

(defn ^{:cmd "talky.disconnect"} disconnect [*sys]
  (let [{:socket.api/keys [end!]} (get @*sys :talky/socket-client)]
    (when end!
      (end!))))

(defn ^{:cmd "talky.sendSelectionToREPL"} send-selection-to-repl [*sys editor edit args]
  (let [document      (.-document editor)
        selection     (.-selection editor)
        range         (vscode/Range. (.-start selection) (.-end selection))
        selected-text (.getText document range)

        {:socket.api/keys [write! connected?]} (get @*sys :talky/socket-client)]
    (when connected?
      (write! selected-text))))

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

    (reset! *sys {:talky/output-channel output-channel})

    (.appendLine output-channel "Talky is active.\n"))

  nil)

(defn deactivate []
  (disconnect *sys))

