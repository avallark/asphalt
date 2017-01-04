;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns asphalt.core.internal.sql
  (:require
    [clojure.string   :as string]
    [asphalt.internal :as i]
    [asphalt.type     :as t])
  (:import
    [java.sql PreparedStatement ResultSet]))


;; ----- parsing helpers -----


(def encode-name keyword)


;(defn encode-type
;  [^String token ^String sql]
;  (let [k (keyword token)]
;    (when-not (contains? sql-type-map k)
;      (i/expected (str supported-sql-types " in SQL string: " sql) token))
;    (get sql-type-map k)))


(defn valid-name-char?
  [^StringBuilder partial-name ch]
  (if (empty? partial-name)
    (Character/isJavaIdentifierStart ^char ch)
    (or
      (Character/isJavaIdentifierPart ^char ch)
      ;; for keywords with hyphens
      (= ^char ch \-))))


(defn valid-type-char?
  [^StringBuilder partial-name ch]
  (if (empty? partial-name)
    (Character/isJavaIdentifierStart ^char ch)
    (or
      (Character/isJavaIdentifierPart ^char ch)
      ;; for keywords with hyphens
      (= ^char ch \-))))


(def initial-parser-state
  {:c? false ; SQL comment in progress?
   :d? false ; double-quote string in progress?
   :e? false ; current escape state
   :n? false ; named param in progress
   :ns nil   ; partial named param string
   :s? false ; single-quote string in progress?
   :t? false ; type-hinted token in progress
   :ts nil   ; partial type-hinted token string
   })


(defn finalize-parser-state
  "Verify that final state is sane and clean up any unfinished stuff."
  [sql ec name-handler parser-state]
  (when (:d? parser-state) (i/illegal-arg "SQL cannot end with incomplete double-quote token:" sql))
  (when (:e? parser-state) (i/illegal-arg (format "SQL cannot end with a dangling escape character '%s':" ec) sql))
  (let [parser-state (merge parser-state (when (:n? parser-state)
                                           (name-handler (:ns parser-state) (:ts parser-state))
                                           {:ts nil :n? false :ns nil}))]
    (when (:s? parser-state) (i/illegal-arg "SQL cannot end with incomplete single-quote token:" sql))
    (when (:t? parser-state) (i/illegal-arg "SQL cannot end with a type hint"))
    (when (:ts parser-state) (i/illegal-arg "SQL cannot end with a type hint"))))


(defn update-param-name!
  "Update named param name."
  [^StringBuilder sb parser-state ch mc special-chars name-handler delta-state]
  (let [^StringBuilder nsb (:ns parser-state)]
    (if (valid-name-char? nsb ch)
      (do (.append nsb ^char ch)
        nil)
      (if-let [c (special-chars ^char ch)]
        (i/illegal-arg (format "Named parameter cannot precede special chars %s: %s%s%s%s"
                         (pr-str special-chars) sb mc nsb c))
        (do
          (name-handler nsb (:ts parser-state))
          (.append sb ^char ch)
          delta-state)))))


(defn update-type-hint!
  [^StringBuilder sb parser-state ch tc delta-state]
  (let [^StringBuilder tsb (:ts parser-state)]
    (cond
      ;; type char
      (valid-type-char? tsb ^char ch)
      (do (.append tsb ^char ch)        nil)
      ;; whitespace implies type has ended
      (Character/isWhitespace ^char ch) delta-state
      ;; catch-all default case
      :otherwise (i/illegal-arg
                   (format "Expected type-hint '%s%s' to precede a whitespace, but found '%s': %s%s%s%s"
                     tc (:ts parser-state) ch
                     sb tc (:ts parser-state) ch)))))


(defn encounter-sql-comment
  [^StringBuilder sb delta-state]
  (let [idx (unchecked-subtract (.length sb) 2)]
    (when (and (>= idx 0)
            (= (.charAt sb idx) \-))
      delta-state)))


(defn parse-sql-str
  "Parse SQL string using escape char, named-param char and type-hint char, returning [sql named-params return-col-types]"
  [^String sql ec mc tc]
  (let [^char ec ec ^char mc mc ^char tc tc nn (count sql)
        ^StringBuilder sb (StringBuilder. nn)
        ks (transient [])  ; param keys
        ts (transient [])  ; result column types
        handle-named! (fn [^StringBuilder buff ^StringBuilder param-type]
                        (.append sb \?)
                        (conj! ks [(.toString buff) (when param-type (.toString param-type))]))
        handle-typed! (fn [^StringBuilder buff] (conj! ts (.toString buff)))
        special-chars #{ec mc tc \" \'}]
    (loop [i 0 ; current index
           s initial-parser-state]
      (if (>= i nn)
        (finalize-parser-state sql ec handle-named! s)
        (let [ch (.charAt sql i)
              ps (merge s
                   (condp s false
                    :c? (do (.append sb ch) (when (= ch \newline) {:c? false})) ; SQL comment in progress
                    :d? (do (.append sb ch) (when (= ch \")       {:d? false})) ; double-quote string in progress
                    :e? (do (.append sb ch)                       {:e? false})  ; escape state
                    :n? (update-param-name!  ; clear :ts at end
                          sb s ch mc special-chars handle-named!  {:ts nil :n? false :ns nil}) ; named param in progress
                    :s? (do (.append sb ch) (when (= ch \')       {:s? false})) ; single-quote string in progress
                    :t? (update-type-hint! sb s ch tc             {:t? false})  ; type-hint in progress (:ts intact)
                    (condp = ch  ; catch-all
                      mc                                          {:n? true :ns (StringBuilder.)}
                      (do (when-let [^StringBuilder tsb (:ts s)]
                            (handle-typed! tsb))
                        (merge {:ts nil}
                          (condp = ch
                            ec                                    {:e? true}
                            tc                                    {:t? true :ts (StringBuilder.)}
                            (do (.append sb ch)
                              (case ch
                                \"                                {:d? true}
                                \'                                {:s? true}
                                \- (encounter-sql-comment sb      {:c? true})
                                nil))))))))]
          (recur (unchecked-inc i) ps))))
    [(.toString sb) (persistent! ks) (persistent! ts)]))


(def cached-qmarks
  (memoize (fn [^long n]
             (string/join ", " (repeat n \?)))))


(defn make-sql
  ^String
  [sql-template params]
  (let [^StringBuilder sb (StringBuilder.)]
    (i/loop-indexed [i 0
                     token sql-template]
      (if (string? token)
        (.append sb ^String token)
        (let [[param-key param-type] token]
          (if (contains? t/multi-typemap param-type)
            (let [k (if (vector? params) i param-key)]
              (if (contains? params k)
               (.append sb ^String (cached-qmarks (count (get params k))))
               (i/illegal-arg "No value found for key:" k "in" (pr-str params))))
            (.append sb \?)))))
    (.toString sb)))


(defrecord StaticSqlTemplate
  [^String sql param-setter row-maker column-reader]
  t/ISqlSource
  (get-sql    [this params] sql)
  (set-params [this prepared-stmt params] (param-setter prepared-stmt params))
  (read-col   [this result-set col-index] (column-reader result-set col-index))
  (read-row   [this result-set col-count] (row-maker result-set col-count)))


(defrecord DynamicSqlTemplate
  [sql-template param-setter row-maker column-reader]
  t/ISqlSource
  (get-sql    [this params] (make-sql sql-template params))
  (set-params [this prepared-stmt params] (param-setter prepared-stmt params))
  (read-col   [this result-set col-index] (column-reader result-set col-index))
  (read-row   [this result-set col-count] (row-maker result-set col-count)))
