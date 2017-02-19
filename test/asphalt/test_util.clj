;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns asphalt.test-util
  (:require
    [clojure.edn     :as e]
    [clojure.java.io :as io]
    [asphalt.test-connpool :as pool]
    [asphalt.core    :as a])
  (:import
    [java.sql Date]
    [java.util Calendar]
    [asphalt.type StmtCreationEvent SQLExecutionEvent]))


(defn echo
  [x]
  (println x)
  x)


(defn echoln
  [& args]
  (when (when-let [verbose (System/getenv "ASPHALT_VERBOSE")]
          (Boolean/parseBoolean verbose))
    (apply println "[Echo] " args)))


(defn sleep
  ([^long millis]
    (when (when-let [verbose (System/getenv "ASPHALT_DELAY")]
            (Boolean/parseBoolean verbose))
      (print "Sleeping" millis "ms...")
      (try (Thread/sleep millis)
        (catch InterruptedException e
          (.interrupt (Thread/currentThread))))
      (println "woke up.")))
  ([]
    (sleep 1000)))


(def config (->> (io/resource "database.edn")
              slurp
              e/read-string))


(def orig-ds (pool/make-datasource config))


(def ds
  (a/instrument-connection-source
    orig-ds
    {:conn-creation {:before     (fn [event]
                                   (echoln "Before:-" event))
                     :on-success (fn [^String id ^long nanos event]
                                   (echoln "Success:- ID:" id "- nanos:" nanos "-" event))
                     :on-error   (fn [^String id ^long nanos event ^Exception error]
                                   (echoln "Error:- ID:" id "- nanos:" nanos "-" event "- error:" error))
                     :lastly     (fn [^String id ^long nanos event]
                                   (echoln "Lastly:- ID:" id "- nanos:" nanos "-" event))}
     :stmt-creation {:before     (fn [^StmtCreationEvent event]
                                   (echoln "Before:-" event))
                     :on-success (fn [^String id ^long nanos ^asphalt.type.StmtCreationEvent event]
                                   (echoln "Success:- ID:" id "- nanos:" nanos "-" event))
                     :on-error   (fn [^String id ^long nanos ^asphalt.type.StmtCreationEvent event ^Exception error]
                                   (echoln "Error:- ID:" id "- nanos:" nanos "-" event "- error:" error))
                     :lastly     (fn [^String id ^long nanos ^asphalt.type.StmtCreationEvent event]
                                   (echoln "Lastly:- ID:" id "- nanos:" nanos "-" event))}
     :sql-execution {:before     (fn [^SQLExecutionEvent event]
                                   (echoln "Before:-" event))
                     :on-success (fn [^String id ^long nanos ^asphalt.type.SQLExecutionEvent event]
                                   (echoln "Success:- ID:" id "- nanos:" nanos "-" event))
                     :on-error   (fn [^String id ^long nanos ^asphalt.type.SQLExecutionEvent event ^Exception error]
                                   (echoln "Error:- ID:" id "- nanos:" nanos "-" event "- error:" error))
                     :lastly     (fn [^String id ^long nanos ^asphalt.type.SQLExecutionEvent event]
                                   (echoln "Lastly:- ID:" id "- nanos:" nanos "-" event))}}))


(def delay-ds
  (a/instrument-connection-source
    orig-ds
    {:conn-creation {:before     (fn [event]
                                   (echoln "Before:-" event)
                                   (sleep))
                     :on-success (fn [^String id ^long nanos event]
                                   (echoln "Success:- ID:" id "- nanos:" nanos "-" event)
                                   (sleep))
                     :on-error   (fn [^String id ^long nanos event ^Exception error]
                                   (echoln "Error:- ID:" id "- nanos:" nanos "-" event "- error:" error)
                                   (sleep))
                     :lastly     (fn [^String id ^long nanos event]
                                   (echoln "Lastly:- ID:" id "- nanos:" nanos "-" event)
                                   (sleep))}
     :stmt-creation {:before     (fn [^StmtCreationEvent event]
                                   (echoln "Before:-" event)
                                   (sleep))
                     :on-success (fn [^String id ^long nanos ^asphalt.type.StmtCreationEvent event]
                                   (echoln "Success:- ID:" id "- nanos:" nanos "-" event)
                                   (sleep))
                     :on-error   (fn [^String id ^long nanos ^asphalt.type.StmtCreationEvent event ^Exception error]
                                   (echoln "Error:- ID:" id "- nanos:" nanos "-" event "- error:" error)
                                   (sleep))
                     :lastly     (fn [^String id ^long nanos ^asphalt.type.StmtCreationEvent event]
                                   (echoln "Lastly:- ID:" id "- nanos:" nanos "-" event)
                                   (sleep))}
     :sql-execution {:before     (fn [^SQLExecutionEvent event]
                                   (echoln "Before:-" event)
                                   (sleep))
                     :on-success (fn [^String id ^long nanos ^asphalt.type.SQLExecutionEvent event]
                                   (echoln "Success:- ID:" id "- nanos:" nanos "-" event)
                                   (sleep))
                     :on-error   (fn [^String id ^long nanos ^asphalt.type.SQLExecutionEvent event ^Exception error]
                                   (echoln "Error:- ID:" id "- nanos:" nanos "-" event "- error:" error)
                                   (sleep))
                     :lastly     (fn [^String id ^long nanos ^asphalt.type.SQLExecutionEvent event]
                                   (echoln "Lastly:- ID:" id "- nanos:" nanos "-" event)
                                   (sleep))}}))


(defn create-db
  []
  (a/update ds (:create-ddl config) []))


(defn drop-db
  []
  (a/update ds (:drop-ddl config) []))


(defn make-date
  "Return a Date instance with the time component stripped."
  []
  (-> (System/currentTimeMillis)
    (quot 86400000)  ; strip hours/minutes/seconds/milliseconds
    (* 86400000)     ; and pad them up with zeros
    (Date.)))
