(ns hours.handler
    (:require
      [clj-time.core :as t]
      [clj-time.format :as f]
      [clojure.java.io :as io]
      [hiccup.core :refer [html]]
      [hiccup.page :refer [html5 include-js include-css]]
      [hiccup.bootstrap.middleware :refer [wrap-bootstrap-resources]]
      [hiccup.bootstrap.page :refer [include-bootstrap]]
      [ring.adapter.jetty :refer [run-jetty]]
      [ring.util.anti-forgery :refer [anti-forgery-field]]
      [compojure.core :refer :all]
      [compojure.route :as route]
      [compojure.handler :as handler]
      [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
      [cemerick.friend        :as friend]
      [friend-oauth2.workflow :as oauth2]
      [friend-oauth2.util     :refer [format-config-uri]]
      [environ.core           :refer [env]]
      [clj-http.client :as client]
      [cheshire.core :as parse]
      [hours.migrations :as migrations])
    (:gen-class))

(def hours (atom []))

(def current (atom {}))

(def logged-in-user (atom {}))

(def client-config
  {:client-id     (env :hours-oauth2-client-id)
   :client-secret (env :hours-oauth2-client-secret)
   :callback      {:domain (env :hours-uri) ;; replace this for production with the appropriate site URL
                   :path "/oauth2callback"}})

(defn google-user-details [token]
  (->> (str "https://www.googleapis.com/oauth2/v1/userinfo?access_token=" token)
       (client/get)
       :body
        (parse/parse-string)))

(defn credential-fn [token]
  (let [userinfo (google-user-details (:access-token token))]
    (reset! logged-in-user userinfo)
    {:identity token
     :user-info userinfo
     :roles #{::user}}))

(def uri-config
  {:authentication-uri {:url "https://accounts.google.com/o/oauth2/auth"
                        :query {:client_id (:client-id client-config)
                               :response_type "code"
                               :redirect_uri (format-config-uri client-config)
                               :scope "email"}}

   :access-token-uri {:url "https://accounts.google.com/o/oauth2/token"
                      :query {:client_id (:client-id client-config)
                              :client_secret (:client-secret client-config)
                              :grant_type "authorization_code"
                              :redirect_uri (format-config-uri client-config)}}})

(defn add-hours [m]
  (swap! hours conj m))

(def custom-formatter (f/formatter "dd/MM-yy"))

(defn prev-monday [dt]
  (let [mon 1
        today (t/day-of-week dt)]
    (if (= mon today)
      dt
      (t/minus dt (t/days (dec today)))))) 

(defn prev-week [mon]
  (t/minus mon (t/weeks 1)))

(defn next-week [mon]
  (t/plus mon (t/weeks 1)))

(defn add-days [dt i]
  (t/plus dt (t/days i)))

(defn week [dt]
  (map (partial add-days (prev-monday dt)) (range 0 7)))

(defn ->hour-mins [interval]
  (let [minutes (t/in-minutes interval)]
    (vector (int (/ minutes 60)) (rem minutes 60))))

(defn trunc-seconds [dt]
  (-> dt
      (t/minus (t/seconds (t/second dt)))
      (t/minus (t/millis (t/milli dt)))))

(defn format-interval [hours-mins]
  (apply format (cons "%02d:%02d" hours-mins)))

(defn find-total-by-day [[date periods]]
  {date {:start (:start (first periods)) :stop (:stop (last periods))}})

(defn by-date [hours]
  (let [days (group-by (fn [period] (vector (f/unparse custom-formatter (:start period)) (:project period))) hours)]
    (mapcat find-total-by-day days)))

(defn display-week-chooser [mon]
  (let [prev (prev-week mon)
        next (next-week mon)]
    [:div "Uke " [:a {:href (str "/user/week/" (f/unparse (f/formatters :basic-date) prev )) } (.getWeekOfWeekyear prev) ]
     " "
     (.getWeekOfWeekyear mon)
     " "
     [:a {:href (str "/user/week/" (f/unparse (f/formatters :basic-date) next )) } (.getWeekOfWeekyear next) ]
     ]))



(defn display-user-nav-bar [userinfo]
  [:div.pull-right (get userinfo "name") [:img {:src (get userinfo "picture") :width "40"}]])

(defn display-week [week]
  [:div
   (display-week-chooser (first week))
   [:table {:border "1"}
   [:tbody
    [:tr
     [:th "Date"] [:th "Project"][:th "From"] [:th "To"] [:th "Sum"] [:th "Extra"] [:th "Iterate"] [:th "Total"] [:th "&nbsp;"]]
    (for [day week]
      [:form {:method "POST" :action (str "/user/register/" (f/unparse (f/formatters :basic-date) day))}
       (anti-forgery-field)
       [:tr
        [:td (f/unparse custom-formatter day)]
        [:td [:input {:type "text" :name "from"}]]
        [:td [:input {:type "text" :name "to"}]]
        [:td "&nbsp;"]
        [:td [:input {:type "text" :name "extra"}]]
        [:td [:input {:type "text" :name "iterate"}]]
        [:td "&nbsp;"]
        [:td [:input {:type "submit" :value "Go"}]]]])]]])

(defn display-hours [hours]
  [:table.table
   [:tbody
    [:tr
     [:th "Date"]
     [:th "Project"]     
     [:th "Start"]
     [:th "End"]
     [:th "Total"]]
    (for [hour (by-date hours)]
      (let [start (:start (second hour) )
            stop (:stop (second hour) )
            diff (t/interval start stop)]
        [:tr
         [:td  (first (first hour))]
         [:td  (second (first hour))]         
          [:td (f/unparse (f/formatters :hour-minute) start) ]
          [:td (f/unparse (f/formatters :hour-minute) stop) ]
          [:td (format-interval (->hour-mins diff))]]))]])

(defn start-stop [action project content]
  [:div [:form.form-inline {:method "POST" :action (str "/user/register/" action) }
         (anti-forgery-field)
         [:fieldset
          (if (= action "start")
            [:input {:type "text" :name "project" :placeholder "Project name..."}]
            [:input {:type "text" :name "project" :value project :readonly "readonly"}])
          [:button.btn {:type "submit" :value action} action]]]
   content])

(defn page-template [content]
  (html5
   [:head
    [:script {:src "https://ajax.googleapis.com/ajax/libs/jquery/1.11.3/jquery.min.js"}]
    [:title "hours"]
    (include-bootstrap)]
   [:body
    [:div.navbar.navbar-inverse.navbar-fixed-top
     [:div.navbar-inner
      [:div.container
       [:button.btn.btn-navbar {:type "button" :data-toggle "collapse" :data-target ".nav-collapse"}
        [:span.icon-bar]
        [:span.icon-bar]
        [:span.icon-bar]]
       [:a.brand {:href "/"} "workday"]
       (display-user-nav-bar @logged-in-user)
       [:div.nav-collapse.collapse
        [:ul.nav
         [:li.active [:a {:href "/user"} "Home"]]
         [:li [:a {:href "/logout"} "Logout"]]
         [:li [:a {:href "#contact"} "Contact"]]]]
       ]]]
    [:div.container content]]))

(defn start [project]
  (swap! current assoc :start (trunc-seconds (t/now)) :project project)
  (page-template (start-stop "stop" project (display-hours @hours))))

(defn stop []
  (swap! current assoc :stop (trunc-seconds (t/now)))
  (add-hours @current)
  (reset! current {})
  (page-template  (start-stop "start" "" (display-hours @hours))))

(defn ->dt [date hour]
  (let [fmt (f/formatter "yyyyMMddHH:mm")]
    (f/parse fmt (str date hour))))

(defn add-interval [date from to extra iterate]
  (add-hours {:start (->dt date from)
              :stop  (->dt date to)
              :extra extra
              :iterate iterate}))

(defroutes secure-routes
  (GET "/" [] (page-template  (start-stop "start" "" (display-hours @hours))))
  (GET "/week" [] (page-template (display-week (week (t/now)))))
  (GET "/week/:date" [date] (page-template (display-week (week (f/parse (f/formatters :basic-date) date)))))
  (POST "/register/start" [project] (start project))
  (POST "/register/stop" [] (stop))
  (POST "/register/:date" [date from to extra iterate] (page-template (display-hours (add-interval date from to extra iterate)))))

(defroutes app-routes
  (context "/user" request
    (friend/wrap-authorize secure-routes #{::user}))
  (GET "/status" request
       (let [count (:count (:session request) 0)
             session (assoc (:session request) :count (inc count))]
         (-> (ring.util.response/response
              (str "<p>We've hit the session page " (:count session)
                   " times.</p><p>The current session: " session "</p>"))
             (assoc :session session))))

  
  (friend/logout (ANY "/logout" request (ring.util.response/redirect "/")))
  (route/not-found "not found"))

(def friend-config
  {:allow-anon? true
   :workflows   [(oauth2/workflow
                  {:client-config client-config
                   :uri-config uri-config
                   :credential-fn credential-fn})
                   ;; Optionally add other workflows here...
                   ]})

(def app
  (-> #'app-routes
        (friend/authenticate friend-config)
        (wrap-bootstrap-resources)
        handler/site))

(defn start-jetty  [port]
  (run-jetty #'app {:port port
                         :join? false}))

(defn -main []
  (migrations/migrate)
  (let [port (Integer. (or (env :port) "3000"))]
    (start-jetty port)))
