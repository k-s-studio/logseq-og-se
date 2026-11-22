(ns frontend.components.git-sync
  "Toolbar git menu: commit the graph now, and push or pull its ref
  (refs/logseq-og-se/<name>) against a remote of the bound repo."
  (:require [clojure.string :as string]
            [electron.ipc :as ipc]
            [frontend.context.i18n :refer [t]]
            [frontend.handler.notification :as notification]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [promesa.core :as p]
            [rum.core :as rum]))

(defn- message
  ;; Keep each translation call literal: the translation lint greps for them.
  [code]
  (case code
    :committed          (t :git-sync/committed)
    :no-changes         (t :git-sync/no-changes)
    :pushed             (t :git-sync/pushed)
    :push-up-to-date    (t :git-sync/push-up-to-date)
    :push-rejected      (t :git-sync/push-rejected)
    :pulled             (t :git-sync/pulled)
    :pull-up-to-date    (t :git-sync/pull-up-to-date)
    :pull-conflict      (t :git-sync/pull-conflict)
    :remote-ref-missing (t :git-sync/remote-ref-missing)
    :no-remote          (t :git-sync/no-remote)
    :not-bound          (t :git-sync/not-bound)
    (t :git-sync/error)))

(defn- ->clj
  [result]
  (some-> (js->clj result :keywordize-keys true)
          (update :code keyword)))

(defn- show-result!
  [{:keys [ok code detail]}]
  (if (= code :identity-unknown)
    (state/pub-event! [:modal/set-git-username-and-email])
    (let [msg (message code)]
      (notification/show!
       (if (string/blank? detail)
         msg
         [:div
          [:div msg]
          [:pre.text-xs.mt-2.whitespace-pre-wrap detail]])
       (cond
         ok :success
         (contains? #{:push-rejected :remote-ref-missing :no-remote} code) :warning
         :else :error)
       ;; Errors carry details worth reading, so they stay until dismissed.
       (boolean ok)))))

(rum/defc git-sync-menu
  [repo]
  (let [[info set-info!] (rum/use-state nil)
        [busy set-busy!] (rum/use-state nil)
        load! (fn []
                (p/let [result (ipc/ipc :gitSyncInfo repo)]
                  (set-info! (->clj result))))
        run! (fn [kind f]
               (when-not busy
                 (set-busy! kind)
                 (-> (p/let [result (f)]
                       (show-result! (->clj result)))
                     (p/catch #(show-result! {:ok false :code :error :detail (str %)}))
                     (p/finally #(set-busy! nil)))))
        {:keys [ok code ref remotes remote]} info
        bound? (and ok ref)
        label (fn [text kind] (if (= busy kind) (str text " …") text))]

    (rum/use-effect! (fn [] (load!) nil) [repo])

    (ui/dropdown-with-links
     (fn [{:keys [toggle-fn]}]
       [:button.button.icon
        {:title    "Git"
         :on-click (fn [] (load!) (toggle-fn))}
        (ui/icon "brand-git" {:size ui/icon-size})])

     (cond
       bound?
       (cond-> [{:title   (label (t :git-sync/commit) :commit)
                 :options {:on-click #(run! :commit (fn [] (ipc/ipc :gitCommitNow repo)))}
                 :icon    (ui/icon "git-commit")}]
         remote
         (into [{:title   (label (t :git-sync/push) :push)
                 :options {:on-click #(run! :push (fn [] (ipc/ipc :gitPush repo remote)))}
                 :icon    (ui/icon "cloud-upload")}
                {:title   (label (t :git-sync/pull) :pull)
                 :options {:on-click #(run! :pull (fn [] (ipc/ipc :gitPull repo remote)))}
                 :icon    (ui/icon "cloud-download")}]))

       (= code :not-bound)
       [{:title   (t :git-sync/go-bind)
         :options {:on-click #(state/open-settings! :version-control)}
         :icon    (ui/icon "settings")}]

       :else
       [])

     {:links-header
      [:div.px-4.py-2.text-sm {:style {:max-width 320}}
       (cond
         (nil? info)
         [:span.opacity-50 "…"]

         bound?
         [:div.flex.flex-col.gap-2
          [:code.text-xs (str "refs/logseq-og-se/" ref)]
          (if (seq remotes)
            [:label.flex.items-center.gap-2
             [:span.opacity-70 (t :git-sync/remote)]
             [:select.form-select.is-small
              {:value     (or remote "")
               :on-change (fn [e]
                            (let [v (.. e -target -value)]
                              (set-info! (assoc info :remote v))
                              (ipc/ipc :gitSetRemote repo v)))}
              (for [r remotes]
                [:option {:key r :value r} r])]]
            [:span.opacity-70 (t :git-sync/no-remotes)])]

         (= code :not-bound)
         [:span.opacity-70 (t :git-sync/not-bound)]

         :else
         [:span.text-red-500 (or (:detail info) (t :git-sync/error))])]})))
