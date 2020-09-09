(ns frontend.handler
  (:require [frontend.state :as state]
            [frontend.db :as db]
            [frontend.util :as util :refer-macros [profile]]
            [frontend.config :as config]
            [clojure.string :as string]
            [promesa.core :as p]
            [cljs-bean.core :as bean]
            [frontend.date :as date]
            [frontend.handler.notification :as notification]
            [frontend.handler.migration :as migration-handler]
            [frontend.handler.repo :as repo-handler]
            [frontend.handler.file :as file-handler]
            [frontend.ui :as ui]))

(defn load-more-journals!
  []
  (let [current-length (:journals-length @state/state)]
    (when (< current-length (db/get-journals-length))
      (state/update-state! :journals-length inc))))

(defn- watch-for-date!
  []
  (js/setInterval #(state/set-today! (date/today))
                  10000))

;; Avoid introducing core.async for smaller bundle size for now
(defn restore-and-setup!
  [me repos logged?]
  ;; wait until pfs is loaded
  (let [pfs-loaded? (atom js/window.pfs)
        interval (atom nil)
        inner-fn (fn []
                   (when (and @interval js/window.pfs)
                     (js/clearInterval @interval)
                     (reset! interval nil)
                     (-> (p/all (db/restore! (assoc me :repos repos)
                                             (fn [repo]
                                               (file-handler/restore-config! repo false)
                                               (when (and (state/logged?)
                                                          (db/cloned? repo)
                                                          (not (db/get-today-journal repo)))
                                                 (repo-handler/read-repair-journals! repo)))))
                         (p/then
                          (fn []
                            (if (and (not logged?)
                                     (not (seq (db/get-files config/local-repo))))
                              (repo-handler/setup-local-repo-if-not-exists!)
                              (state/set-db-restoring! false))
                            (watch-for-date!)
                            (when-let [current-repo (state/get-current-repo)]
                              (when (db/monthly-journals-exists? current-repo)
                                (notification/show!
                                 [:div
                                  [:p "Logseq is migrating to creating journal pages on a daily basis for better performance and data safety. In the future, the current method of storing journal files once a month would be removed. Please click the following button to migrate, and feel free to let us know if anything unexpected happened!"]
                                  (when-not (= current-repo "local")
                                    [:p "After migrating, please wait a few seconds, until the sync indicator turned yellow then green, then re-index your repository."])
                                  (ui/button "Begin migration"
                                    :on-click #(migration-handler/handle-journal-migration-from-monthly-to-daily! current-repo))]
                                 :warning
                                 false)))
                            (when (seq (:repos me))
                              ;; FIXME: handle error
                              (repo-handler/request-app-tokens!
                               (fn []
                                 (repo-handler/clone-and-pull-repos me))
                               (fn []
                                 (js/console.error "Failed to request GitHub app tokens.")))))))))]
    ;; clear this interval
    (let [interval-id (js/setInterval inner-fn 50)]
      (reset! interval interval-id))))

(defn persist-repo-to-indexeddb!
  ([]
   (persist-repo-to-indexeddb! false))
  ([force?]
   (let [status (state/get-repo-persist-status)]
     (doseq [[repo {:keys [last-stored-at last-modified-at] :as repo-status}] status]
       (when (and (> last-modified-at last-stored-at)
                  (or force?
                      (and (state/get-edit-input-id)
                           (> (- (util/time-ms) last-stored-at) (* 5 60 1000)) ; 5 minutes
                           )
                      (nil? (state/get-edit-input-id))))
         (p/let [_ (repo-handler/persist-repo! repo)]
           (state/update-repo-last-stored-at! repo)))))))

(defn periodically-persist-repo-to-indexeddb!
  []
  (js/setInterval persist-repo-to-indexeddb! (* 5 1000)))

(defn set-save-before-unload! []
  (.addEventListener js/window "beforeunload"
                     (fn [e]
                       (when (state/repos-need-to-be-stored?)
                         (notification/show!
                          [:div
                           [:p "It seems that you have some unsaved changes!"]
                           (ui/button "Save"
                             :on-click (fn [e]
                                         (persist-repo-to-indexeddb!)
                                         (notification/show!
                                          "Saved successfully!"
                                          :success)))]
                          ;; replace with :warning
                          :error
                          false)
                         (let [message "\\o/"]
                           (set! (.-returnValue (or e js/window.event)) message)
                           message)))))

(defn start!
  [render]
  (let [me (and js/window.user (bean/->clj js/window.user))
        logged? (:name me)
        repos (if logged?
                (:repos me)
                [{:url config/local-repo}])]
    (when me (state/set-state! :me me))
    (state/set-db-restoring! true)
    (render)

    (util/indexeddb-check?
     (fn [_error]
       (notification/show! "Sorry, it seems that your browser doesn't support IndexedDB, we recommend to use latest Chrome(Chromium) or Firefox(Non-private mode)." :error false)
       (state/set-indexedb-support? false)))

    (restore-and-setup! me repos logged?)

    (periodically-persist-repo-to-indexeddb!)

    (db/run-batch-txs!)))
