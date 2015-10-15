(ns hours.handler
    (:require
      [clojure.java.jdbc :as sql]
      [ring.middleware.file :refer [wrap-file]]
      [ring.adapter.jetty :refer [run-jetty]]
      [compojure.core :refer :all]
      [compojure.route :as route]
      [compojure.handler :as handler]
      [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
      [cemerick.friend        :as friend]
      [clj-time.core :as t]
      [hours.time :as time]
      [hours.layout :as layout]
      [hours.client :as client]
      [hours.prjct :as prjct]
      [hours.migrations :as migrations]
      [hours.security :as security]
      [environ.core :refer [env]])
    (:gen-class))

(def db-spec {:connection-uri (env :jdbc-database-url)})

(def hours (atom {}))

(def current (atom {}))

(defn add-hours [m u]
  (swap! hours (fn [hours]
                 (update-in hours [u] conj m))))

(defn start [project]
  (swap! current assoc :start (time/trunc-seconds (t/now)) :project project)
  (layout/show-hours-page (security/logged-in-user) "stop" project @hours))

(defn stop []
  (swap! current assoc :stop (time/trunc-seconds (t/now)))
  (add-hours @current (security/user-id-kw))
  (reset! current {})
  (layout/show-hours-page (security/logged-in-user) "start" "" @hours))

(defn add-interval [date from to extra iterate]
  (add-hours {:start (time/->dt date from)
              :stop  (time/->dt date to)
              :extra extra
              :iterate iterate}))

(defn add-client [name]
  (client/add-client<! {:name name :user_id (security/user-id)}  {:connection db-spec})
  (ring.util.response/redirect "/client/"))

(defn add-project [client-id name]
  (prjct/add-project<! {:name name :client_id client-id}  {:connection db-spec})
  (ring.util.response/redirect "/project/"))

(defn logout []
  (security/logout) 
  (reset! current {})
  (ring.util.response/redirect "/"))

(defroutes user-routes
  (GET "/" [] (layout/show-hours-page (security/logged-in-user) "start" "" @hours))
  (GET "/status" request (layout/show-status-page (security/logged-in-user) request))
  (GET "/week" [] (layout/show-week-page (security/logged-in-user) (t/now)))
  (GET "/week/:date" [date] (layout/show-week-page (security/logged-in-user) date))
  (POST "/register/start" [project] (start project))
  (POST "/register/stop" [] (stop))
  (POST "/register/:date" [date from to extra iterate] (layout/page-template (security/logged-in-user)
                                                                             (layout/display-hours (add-interval date from to extra iterate)))))

(defroutes client-routes
  (GET "/" [] (layout/show-clients-page (security/logged-in-user) (client/user-clients {:user_id (security/user-id)} {:connection db-spec})))
  (GET "/:client-id/projects" [client-id] (layout/show-projects-page (security/logged-in-user) (prjct/user-client-projects {:user_id (security/user-id)  :client_id client-id} {:connection db-spec})) )
  
  (GET "/add" [] (layout/show-add-client-page (security/logged-in-user)))
  (POST "/add" [name] (add-client name)))

(defroutes project-routes
  (GET "/" [] (layout/show-projects-page (security/logged-in-user) (prjct/user-projects {:user_id (security/user-id)} {:connection db-spec})))
  (GET "/add/:client-id" [client-id] (layout/show-add-project-page (security/logged-in-user) (first (client/user-client {:user_id (security/user-id) :client_id client-id} {:connection db-spec}))))
  (POST "/add/:client-id" [client-id name] (add-project client-id name)))

(defroutes app-routes
  (GET "/" [] (layout/login-page))
  (context "/user" request (friend/wrap-authorize user-routes #{security/user}))
  (context "/client" request (friend/wrap-authorize client-routes #{security/user}))
  (context "/project" request (friend/wrap-authorize project-routes #{security/user}))  
  (friend/logout (ANY "/logout" request (logout)))
  (route/not-found "not found"))

(def app
  (-> #'app-routes
        (friend/authenticate (security/friend-config db-spec))
        (wrap-file "resources/public")
        handler/site))

(defn start-jetty  [port]
  (run-jetty #'app {:port port
                         :join? false}))

(defn -main []
  (migrations/migrate)
  (let [port (Integer. (or (env :port) "3000"))]
    (start-jetty port)))
