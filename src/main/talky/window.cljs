(ns talky.window
  (:require
   ["vscode" :as vscode]))

(defn show-information-message [message]
  (-> (.-window ^js vscode)
      (.showInformationMessage message)))

(defn show-error-message [message]
  (-> (.-window ^js vscode)
      (.showErrorMessage message)))      

(defn show-quick-pick [items]
  (-> (.-window ^js vscode)
      (.showQuickPick (clj->js items))))

(defn show-input-box [& [options]]
  (-> (.-window ^js vscode)
      (.showInputBox (clj->js options))))