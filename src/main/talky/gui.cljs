(ns talky.gui
  (:require
   ["vscode" :as vscode]))


(defn show-information-message [message]
  (vscode/window.showInformationMessage message))


(defn show-quick-pick [items]
  (vscode/window.showQuickPick (clj->js items)))


(defn show-input-box [& [options]]
  (vscode/window.showInputBox (clj->js options)))