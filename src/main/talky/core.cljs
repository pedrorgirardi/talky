(ns talky.core
  (:require
   ["vscode" :as vscode]
   ["net" :as net]))

(def -window
  (.-window ^js vscode))

(defn show-information-message [message & {:keys [modal?]}]
  (.showInformationMessage -window message #js {:modal modal?}))

(defn show-warning-message [message]
  (.showWarningMessage -window message))

(defn show-error-message [message]
  (.showErrorMessage -window message))

(defn show-quick-pick [items]
  (.showQuickPick -window (clj->js items)))

(defn show-input-box [& [options]]
  (.showInputBox -window (clj->js options)))

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

(defn decoration []
  (let [type {:isWholeLine true
              :rangeBehavior (-> vscode
                                 (.-DecorationRangeBehavior)
                                 (.-ClosedClosed))
              :after {:border "solid"
                      :margin "0 0 0 8px"}}]
    (.createTextEditorDecorationType -window (clj->js type))))

(defn connect!
  [{:keys [host port encoding encoder decoder on-connect on-close on-data]
    :or {encoder
         (fn [data]
            ;; See https://nodejs.org/api/net.html#net_socket_write_data_encoding_callback
           data)

          ;; You can also set the encoding.
          ;; See https://nodejs.org/api/net.html#net_socket_setencoding_encoding
          ;; :encoding "utf8"

         decoder
         (fn [buffer-or-string]
            ;; See https://nodejs.org/api/net.html#net_event_data
           buffer-or-string)

         on-connect
         (fn []
           ;; Do stuff and returns nil.
           nil)

         on-close
         (fn [_error?]
           ;; Do stuff and returns nil.
           nil)

         on-data
         (fn [_buffer-or-string]
           ;; Do stuff and returns nil.
           nil)}}]
  (let [socket (doto (net/connect #js {:host host :port port})
                 (.once "connect" (fn []
                                    (on-connect)))

                 (.once "close" (fn [error?]
                                  (on-close error?)))

                 (.on "data" (fn [buffer]
                               (on-data (decoder buffer)))))

        socket (if encoding
                 (.setEncoding socket encoding)
                 socket)]
    {:socket socket
     :connecting? true
     :write! #(.write ^js socket (encoder %))
     :end! #(.end ^js socket)}))

(defn connecting? [sys]
  (get-in sys [:talky/connection :connecting?]))

(defn connected? [sys]
  (get-in sys [:talky/connection :connected?]))

(defn- ^{:cmd "talky.connect"} connect [*sys]
  (if (connected? @*sys)
    (show-information-message "Talky is connected.")
    (.then (show-input-box
            {:ignoreFocusOut true
             :prompt "Host"
             :value "localhost"})
           (fn [host]
             (when host
               (.then (show-input-box
                       {:ignoreFocusOut true
                        :prompt "Port"
                        :value (str 5555)})
                      (fn [port]
                        (when port
                          (let [on-connect
                                (fn []
                                  (swap! *sys update :talky/connection merge {:connected? true
                                                                              :connecting? false})

                                  (show-information-message
                                   "Talky is connected."))

                                on-close
                                (fn [error?]
                                  (if error?
                                    (show-error-message
                                     (cond
                                       (connected? @*sys)
                                       "Talky was disconnected due an error."

                                       (connecting? @*sys)
                                       (str "Talky failed to connect to REPL on port " port ".")

                                       :else
                                       "Talky had a transmission error."))
                                    (show-information-message
                                     "Talky is disconnected."))

                                  (swap! *sys update :talky/connection merge {:connected? false
                                                                              :connecting? false}))

                                on-data
                                (fn [data]
                                  (let [^js output-channel (get @*sys :talky/output-channel)]
                                    (.appendLine output-channel data)))

                                connection
                                (connect! {:host host
                                           :port (js/parseInt port)
                                           :encoding "utf8"
                                           :encoder #(str % "\n")
                                           :decoder identity
                                           :on-connect on-connect
                                           :on-close on-close
                                           :on-data on-data})]

                            (swap! *sys assoc :talky/connection connection))))))))))

(defn ^{:cmd "talky.disconnect"} disconnect [*sys]
  (let [{:keys [end!]} (get @*sys :talky/connection)]
    (if (connected? @*sys)
      (end!)
      (show-information-message "Talky is disconnected."))))

(defn- selected-text [^js editor]
  (let [^js document (.-document editor)
        ^js selection (.-selection editor)]
    (.getText document selection)))

(defn- transmit! [*sys text]
  (let [^js output-channel (get @*sys :talky/output-channel)

        {:keys [write!]} (get @*sys :talky/connection)]
    (if (connected? @*sys)
      (do
        (.appendLine output-channel (str text "\n"))
        (write! text))
      (show-warning-message "Talky is disconnected."))))

(defn ^{:cmd "talky.sendSelectionToREPL"} send-selection-to-repl [*sys ^js editor ^js _edit ^js _args]
  (->> (selected-text editor)
       (transmit! *sys)))

(def *sys
  (atom {}))


;; Start a Clojure socket-based REPL
;; -- REPL
;; clj -J-Dclojure.server.repl="{:port 5555 :accept clojure.core.server/repl}"
;; -- pREPL
;; clj -J-Dclojure.server.repl="{:port 5555 :accept clojure.core.server/io-prepl}"

(defn activate [^js context]
  (->> (register-command *sys #'connect)
       (register-disposable context))

  (->> (register-command *sys #'disconnect)
       (register-disposable context))

  (->> (register-text-editor-command *sys #'send-selection-to-repl)
       (register-disposable context))

  (reset! *sys {:talky/output-channel (.createOutputChannel -window "Talky")})

  nil)

(defn deactivate []
  (let [{:keys [end!]} (get @*sys :talky/connection)]
    (when (connected? @*sys)
      (end!))))

