(ns talky.core
  (:require
   [cljs.reader :as reader]
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

(def decoration
  (let [type {:rangeBehavior (-> vscode
                                 (.-DecorationRangeBehavior)
                                 (.-ClosedOpen))
              :after {:margin "0 0 0 8px"
                      :textDecoration "none"
                      :fontWeight "normal"}}]
    (->  (.-window vscode)
         (.createTextEditorDecorationType (clj->js type)))))

(defn- prepl-encoder [data]
  (str "(do " data ")" "\n"))

(defn- prepl-decoder [data]
  (reader/read-string data))

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
         (fn []
           ;; Do stuff and returns nil.
           nil)

         on-close
         (fn [error?]
           ;; Do stuff and returns nil.
           nil)

         on-data
         (fn [buffer-or-string]
           ;; Do stuff and returns nil.
           nil)}
    :as this}]
  (let [{:keys [encoding encoder decoder]} config

        socket (doto (net/connect #js {:host host :port port})
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
                                 :decoder prepl-decoder
                                 :encoder prepl-encoder}

                                on-connect
                                (fn []
                                  (swap! *sys update :talky/connection merge {:connected? true
                                                                              :connecting? false})

                                  (window/show-information-message
                                   "Talky is connected."))

                                on-close
                                (fn [error?]
                                  (if error?
                                    (window/show-error-message
                                     (cond
                                       (connected? @*sys)
                                       "Talky was disconnected due an error."

                                       (connecting? @*sys)
                                       (str "Talky failed to connect. Is the REPL running on port " port "?")

                                       :else
                                       "Talky had a transmission error."))
                                    (window/show-information-message
                                     "Talky is disconnected."))

                                  (swap! *sys update :talky/connection merge {:connected? false
                                                                              :connecting? false}))

                                on-data
                                (fn [{:keys [tag val] :as m}]
                                  (let [^js output-channel (get @*sys :talky/output-channel)

                                        {:keys [editor selection]} (get @*sys :talky/eval)]

                                    ; (.appendLine output-channel m)
                                    ; (.show output-channel true)

                                    (cond
                                      (= :ret tag)
                                      (.setDecorations ^js editor decoration (clj->js [{:range selection
                                                                                        :renderOptions
                                                                                        {:after
                                                                                         {:contentText val}}}]))

                                      :else
                                      (.appendLine output-channel val))))

                                connection
                                (connect! {:host host
                                           :port (js/parseInt port)
                                           :config config
                                           :on-connect on-connect
                                           :on-close on-close
                                           :on-data on-data})]

                            (swap! *sys assoc :talky/connection connection))))))))))

(defn ^{:cmd "talky.disconnect"} disconnect [*sys]
  (let [{:keys [end!]} (get @*sys :talky/connection)]
    (if (connected? @*sys)
      (end!)
      (window/show-information-message "Talky is disconnected."))))

(defn ^{:cmd "talky.sendSelectionToREPL"} send-selection-to-repl [*sys ^js editor ^js edit ^js args]
  (let [^js document (.-document editor)
        ^js selection (.-selection editor)

        {:keys [write!]} (get @*sys :talky/connection)]
    (if (connected? @*sys)
      (do
        (swap! *sys assoc :talky/eval {:editor editor
                                       :selection selection})

        (write! (.getText document selection)))
      (window/show-information-message "Talky is disconnected and can't send selection to REPL."))))

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

  (reset! *sys {:talky/output-channel (.createOutputChannel (.-window ^js vscode) "Talky")})

  nil)

(defn deactivate []
  (let [{:keys [end!]} (get @*sys :talky/connection)]
    (when (connected? @*sys)
      (end!))))

