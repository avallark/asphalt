(ns asphalt.internal
  (:require
    [clojure.string :as str])
  (:import
    [java.io Writer]
    [java.util.regex Pattern]
    [java.sql Blob Clob Date Time Timestamp
              Connection PreparedStatement Statement
              ResultSet ResultSetMetaData]))


;; ----- error reporting -----


(defn illegal-arg
  [msg & more]
  (throw
    ^IllegalArgumentException (cond
                                (instance? Throwable msg)         (IllegalArgumentException.
                                                                    ^String (str/join \space more)
                                                                    ^Throwable msg)
                                (instance? Throwable (last more)) (IllegalArgumentException.
                                                                    ^String (str/join \space (cons msg (butlast more)))
                                                                    ^Throwable (last more))
                                :otherwise (IllegalArgumentException. ^String (str/join \space (cons msg more))))))


(defn unexpected
  "Report error about expectation mismatch."
  [msg found]
  (illegal-arg "Expected" msg "but found:" (class found) (pr-str found)))


(defn assert-symbols
  [& syms]
  (doseq [x syms]
    (when-not (symbol? x)
      (unexpected "a symbol" x))))


;; ----- utilities -----


(defmacro loop-indexed
  [[counter init-count iteratee coll & more-pairs] & body]
  (assert-symbols counter iteratee)
  (->> (partition 2 more-pairs)
    (map first)
    (apply assert-symbols))
  (when (odd? (count more-pairs))
    (unexpected "an even number of forms in binding vector" more-pairs))
  (let [more-syms (->> (partition 2 more-pairs)
                    (map first))
        more-seq  (map  (fn [x] `(seq ~x)) more-syms)
        more-rest (map  (fn [x] `(rest ~x)) more-syms)]
    `(loop [~counter ~init-count
            ~iteratee ~coll
            ~@more-pairs]
      (when (and (seq ~iteratee) ~@more-seq)
        (do ~@body)
        (recur (unchecked-inc ~counter) (rest ~iteratee) ~@more-rest)))))


;; ----- type definitions -----


(def ^:const sql-nil        0)
(def ^:const sql-bool       1)
(def ^:const sql-boolean    1) ; duplicate of bool
(def ^:const sql-byte       2)
(def ^:const sql-byte-array 3)
(def ^:const sql-date       4)
(def ^:const sql-double     5)
(def ^:const sql-float      6)
(def ^:const sql-int        7)
(def ^:const sql-integer    7) ; duplicate for int
(def ^:const sql-long       8)
(def ^:const sql-nstring    9)
(def ^:const sql-object    10)
(def ^:const sql-string    11)
(def ^:const sql-time      12)
(def ^:const sql-timestamp 13)

(def sql-type-map {nil         sql-nil
                   :bool       sql-bool
                   :boolean    sql-boolean
                   :byte       sql-byte
                   :byte-array sql-byte-array
                   :date       sql-date
                   :double     sql-double
                   :float      sql-float
                   :int        sql-int
                   :integer    sql-integer
                   :long       sql-long
                   :nstring    sql-nstring
                   :object     sql-object
                   :string     sql-string
                   :time       sql-time
                   :timestamp  sql-timestamp})


(def supported-sql-types (str "either of " (->> (keys sql-type-map)
                                             (remove nil?)
                                             (map name)
                                             (str/join ", "))))


(defrecord SQLTemplate
  [^String sql ^objects param-pairs ^bytes result-column-types])


(defmethod print-method SQLTemplate [^SQLTemplate obj ^Writer w]
  (let [m {:sql (.-sql obj)
           :param-pairs (vec (.-param-pairs obj))
           :result-column-types (vec (.-result-column-types obj))}]
    (.write w (str "#SQLTemplate" (pr-str m)))))


(definline param-pairs [^SQLTemplate x] `(:param-pairs ~x))


(definline result-column-types [^SQLTemplate x] `(:result-column-types ~x))


(defn sql-template?
  "Return true if specified arg is an instance of SQLTemplate, false otherwise."
  [x]
  (instance? SQLTemplate x))


(definline resolve-sql [x] `(if (sql-template? ~x) (:sql ~x) ~x))


(defn make-sql-template
  "Given SQL template arguments create an SQLTemplate instance. Example args are below:
  sql - \"SELECT name, salary FROM emp WHERE salary > ? AND dept = ?\"
  param-pairs - [[:salary :int] [:dept]]
  result-column-types - [:string :int]"
  ^SQLTemplate [sql param-pairs result-column-types]
  (when-not (string? sql)
    (unexpected "SQL string" sql))
  (->SQLTemplate sql (object-array param-pairs) (byte-array result-column-types)))


;; ----- SQL parsing helpers -----


(def encode-name keyword)


(defn encode-type
  [^String token ^String sql]
  (let [k (keyword token)]
    (when-not (contains? sql-type-map k)
      (unexpected (str supported-sql-types " in SQL string: " sql) token))
    (get sql-type-map k)))


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
  (when (:d? parser-state) (illegal-arg "SQL cannot end with incomplete double-quote token:" sql))
  (when (:e? parser-state) (illegal-arg (format "SQL cannot end with a dangling escape character '%s':" ec) sql))
  (let [parser-state (merge parser-state (when (:n? parser-state)
                                           (name-handler (:ns parser-state) (:ts parser-state))
                                           {:ts nil :n? false :ns nil}))]
    (when (:s? parser-state) (illegal-arg "SQL cannot end with incomplete single-quote token:" sql))
    (when (:t? parser-state) (illegal-arg "SQL cannot end with a type hint"))
    (when (:ts parser-state) (illegal-arg "SQL cannot end with a type hint"))))


(defn update-param-name!
  "Update named param name."
  [^StringBuilder sb parser-state ch mc special-chars name-handler delta-state]
  (let [^StringBuilder nsb (:ns parser-state)]
    (if (valid-name-char? nsb ch)
      (do (.append nsb ^char ch)
        nil)
      (if-let [c (special-chars ^char ch)]
        (illegal-arg (format "Named parameter cannot precede special chars %s: %s%s%s%s"
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
      :otherwise (illegal-arg
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


;; ----- statement helpers -----


(defn prepare-statement
  ^PreparedStatement [^Connection connection ^String sql return-generated-keys?]
  (if return-generated-keys?
    (.prepareStatement connection sql Statement/RETURN_GENERATED_KEYS)
    (.prepareStatement connection sql)))


;; ----- params helpers -----


(defn set-param-value!
  ([^PreparedStatement prepared-statement ^long param-index value]
    (cond
      (instance? Integer             value) (.setInt       prepared-statement param-index ^int value)
      (instance? Long                value) (.setLong      prepared-statement param-index ^long value)
      (instance? clojure.lang.BigInt value) (.setLong     prepared-statement param-index ^long (long value))
      (instance? String              value) (.setString    prepared-statement param-index ^String value)
      (instance? Timestamp           value) (.setTimestamp prepared-statement param-index ^Timestamp value)
      :otherwise                            (.setObject    prepared-statement param-index ^Object value)))
  ([^PreparedStatement prepared-statement ^long param-index ^long param-type param-value]
    (case param-type
      #_sql-nil         0 (set-param-value! prepared-statement param-index param-value)
      #_sql-boolean     1 (.setBoolean   prepared-statement param-index (boolean    param-value))
      #_sql-byte        2 (.setByte      prepared-statement param-index (byte       param-value))
      #_sql-byte-array  3 (.setBytes     prepared-statement param-index (byte-array param-value))
      #_sql-date        4 (.setDate      prepared-statement param-index ^java.sql.Date param-value)
      #_sql-double      5 (.setDouble    prepared-statement param-index (double     param-value))
      #_sql-float       6 (.setFloat     prepared-statement param-index (float      param-value))
      #_sql-int         7 (.setInt       prepared-statement param-index (int        param-value))
      #_sql-long        8 (.setLong      prepared-statement param-index (long       param-value))
      #_sql-nstring     9 (.setNString   prepared-statement param-index ^String param-value)
      #_sql-object     10 (.setObject    prepared-statement param-index ^Object param-value)
      #_sql-string     11 (.setString    prepared-statement param-index ^String param-value)
      #_sql-time       12 (.setTime      prepared-statement param-index ^java.sql.Time param-value)
      #_sql-timestamp  13 (.setTimestamp prepared-statement param-index ^java.sql.Timestamp param-value)
      (unexpected supported-sql-types param-type))))


(defn set-params!
  ([^PreparedStatement prepared-statement params]
    (cond
      (vector? params) (let [param-count (count params)]
                         (loop [i (int 0)]
                           (when (< i param-count)
                             (let [j (unchecked-inc i)]
                               (set-param-value! prepared-statement j (get params i))
                               (recur j)))))
      (nil? params)    nil
      :otherwise       (unexpected "params vector" params)))
  ([^PreparedStatement prepared-statement ^objects param-types params]
    (cond
      (map? params)    (let [param-count (alength param-types)]
                         (loop [i (int 0)]
                           (when (< i param-count)
                             (let [[param-key param-type] (aget param-types i)
                                   j (unchecked-inc i)]
                               (if (contains? params param-key)
                                 (set-param-value! prepared-statement j param-type (get params param-key))
                                 (illegal-arg "No value found for key:" param-key "in" (pr-str params)))
                               (recur j)))))
      (vector? params) (let [types-count (alength param-types)
                             param-count (count params)]
                         (loop [i (int 0)]
                           (when (< i param-count)
                             (let [j (unchecked-inc i)]
                               (set-param-value! prepared-statement j
                                 (if (< i types-count) (second (aget param-types i)) sql-nil) (get params i))
                               (recur j)))))
      (nil? params)    nil
      :otherwise       (unexpected "map or vector" params))))


;; ----- result-set stuff -----


(defn read-column-value
  "Read column value from given ResultSet instance and integer column-index."
  ([^ResultSet result-set ^long column-index]
    (let [data (.getObject result-set column-index)]
      (cond
        (instance? Clob data) (.getString result-set column-index)
        (instance? Blob data) (.getBytes  result-set column-index)
        (nil? data)           nil
        :otherwise            (let [^String class-name (.getName ^Class (class data))
                                    ^ResultSetMetaData rsmd (.getMetaData result-set)
                                    ^String mdcn (.getColumnClassName rsmd column-index)]
                                (cond
                                  (.startsWith class-name
                                    "oracle.sql.DATE")                (if (#{"java.sql.Timestamp"
                                                                             "oracle.sql.TIMESTAMP"} mdcn)
                                                                        (.getTimestamp result-set column-index)
                                                                        (.getDate      result-set column-index))
                                  (and (instance? java.sql.Date data)
                                    (= "java.sql.Timestamp" mdcn))    (.getTimestamp result-set column-index)
                                  :otherwise                          data)))))
  ([^ResultSet result-set ^long column-index ^long column-type]
    (case column-type
      #_sql-nil         0 (read-column-value result-set column-index)
      #_sql-boolean     1 (.getBoolean   result-set column-index)
      #_sql-byte        2 (.getByte      result-set column-index)
      #_sql-byte-array  3 (.getBytes     result-set column-index)
      #_sql-date        4 (.getDate      result-set column-index)
      #_sql-double      5 (.getDouble    result-set column-index)
      #_sql-float       6 (.getFloat     result-set column-index)
      #_sql-int         7 (.getInt       result-set column-index)
      #_sql-long        8 (.getLong      result-set column-index)
      #_sql-nstring     9 (.getNString   result-set column-index)
      #_sql-object     10 (.getObject    result-set column-index)
      #_sql-string     11 (.getString    result-set column-index)
      #_sql-time       12 (.getTime      result-set column-index)
      #_sql-timestamp  13 (.getTimestamp result-set column-index)
      (unexpected supported-sql-types column-type))))


(defn read-result-row
  "Given a java.sql.ResultSet instance, read exactly one row as a vector and return the same."
  [^ResultSet result-set column-count-or-types]
  (let [column-count? (integer? column-count-or-types)
        column-count  (if column-count?
                        (int column-count-or-types)
                        (alength ^bytes column-count-or-types))
        row (object-array column-count)]
    (if column-count?
      (loop [i (int 0)]
        (when (< i column-count)
          (let [j (unchecked-inc i)]
            (aset row i (read-column-value result-set j))
            (recur j))))
      (loop [i (int 0)]
        (when (< i column-count)
          (let [j (unchecked-inc i)]
            (aset row i (read-column-value result-set j (aget ^bytes column-count-or-types i)))
            (recur j)))))
    row))


;; ----- transaction stuff -----


(def isolation-levels #{Connection/TRANSACTION_NONE
                        Connection/TRANSACTION_READ_COMMITTED
                        Connection/TRANSACTION_READ_UNCOMMITTED
                        Connection/TRANSACTION_REPEATABLE_READ
                        Connection/TRANSACTION_SERIALIZABLE})


(defn resolve-txn-isolation
  ^long [isolation]
  (if (isolation-levels isolation)
    isolation
    (condp = isolation
      :none             Connection/TRANSACTION_NONE
      :read-committed   Connection/TRANSACTION_READ_COMMITTED
      :read-uncommitted Connection/TRANSACTION_READ_UNCOMMITTED
      :repeatable-read  Connection/TRANSACTION_REPEATABLE_READ
      :serializable     Connection/TRANSACTION_SERIALIZABLE
      (illegal-arg "Expected either of" (pr-str isolation-levels)
        "or an integer representing a java.sql.Connection/TRANSACTION_XXX value, but found"
        (class isolation) (pr-str isolation)))))


;; Spring (and EJB) transaction propagation
;; http://docs.spring.io/spring/docs/current/javadoc-api/org/springframework/transaction/annotation/Propagation.html
(def propagation #{:mandatory
                   :nested
                   :never
                   :not-supported
                   :required
                   :requires-new
                   :supports})
