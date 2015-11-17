(ns asphalt.core
  (:refer-clojure :exclude [update])
  (:require
    [clojure.string   :as str]
    [asphalt.type     :as t]
    [asphalt.internal :as i])
  (:import
    [java.util.regex Pattern]
    [java.sql  Connection PreparedStatement
               ResultSet ResultSetMetaData]
    [javax.sql DataSource]
    [asphalt.instrument JdbcEventListener]
    [asphalt.instrument.wrapper DataSourceWrapper]))


(defn instrument-datasource
  "Make instrumented javax.sql.DataSource instance using statement-creation and SQL-execution listeners.

  Option :stmt-creation corresponds to a map containing the following fns, triggered when JDBC statements are created:
  {:before     (fn [^asphalt.type.StmtCreationEvent event])
   :on-success (fn [^String id ^long nanos ^asphalt.type.StmtCreationEvent event])
   :on-error   (fn [^String id ^long nanos ^asphalt.type.StmtCreationEvent event ^Exception error])
   :lastly     (fn [^String id ^long nanos ^asphalt.type.StmtCreationEvent event])}

  Option :sql-execution corresponds to a map containing the following fns, triggered when SQL statements are executed:
  {:before     (fn [^asphalt.type.SQLExecutionEvent event])
   :on-success (fn [^String id ^long nanos ^asphalt.type.SQLExecutionEvent event])
   :on-error   (fn [^String id ^long nanos ^asphalt.type.SQLExecutionEvent event ^Exception error])
   :lastly     (fn [^String id ^long nanos ^asphalt.type.SQLExecutionEvent event])}"
  ^javax.sql.DataSource [^DataSource ds {:keys [stmt-creation sql-execution]
                                         :or {stmt-creation JdbcEventListener/NOP
                                              sql-execution      JdbcEventListener/NOP}}]
  (let [stmt-creation-listener (if (instance? JdbcEventListener stmt-creation)
                                 stmt-creation
                                 (i/make-jdbc-event-listener stmt-creation))
        sql-execution-listener (if (instance? JdbcEventListener sql-execution)
                                 sql-execution
                                 (i/make-jdbc-event-listener sql-execution))]
    (DataSourceWrapper. ds i/jdbc-event-factory stmt-creation-listener sql-execution-listener)))


;; ----- parse SQL for named parameters and types -----


(defn parse-sql
  "Given a SQL statement with embedded parameter names return a three-element vector:
  [SQL-template-string
   param-pairs           (each pair is a two-element vector of param key and type)
   result-column-types]
  that can be used later to extract param values from maps."
  ([^String sql]
    (parse-sql sql {}))
  ([^String sql {:keys [escape-char param-start-char type-start-char name-encoder]
                 :or {escape-char \\ param-start-char \$ type-start-char \^}
                 :as options}]
    (let [[sql named-params return-col-types]  (i/parse-sql-str sql escape-char param-start-char type-start-char)]
      (i/make-sql-template
        sql
        (mapv #(let [[p-name p-type] %]
                 [(i/encode-name p-name) (i/encode-type p-type sql)])
          named-params)
        (mapv #(i/encode-type % sql) return-col-types)))))


(defmacro defsql
  "Define a parsed SQL template that can be used to execute it later."
  ([var-symbol sql]
    (when-not (symbol? var-symbol)
      (i/unexpected "a symbol" var-symbol))
    `(def ~var-symbol (parse-sql ~sql)))
  ([var-symbol sql options]
    (when-not (symbol? var-symbol)
      (i/unexpected "a symbol" var-symbol))
    `(def ~var-symbol (parse-sql ~sql ~options))))


;; ----- java.sql.ResultSet operations -----


(defn fetch-maps
  "Fetch a collection of maps."
  [isql ^ResultSet result-set]
  (doall (resultset-seq result-set)))


(defn fetch-rows
  "Given java.sql.ResultSet and asphalt.type.ISql instances fetch a vector of rows using ISql."
  ([isql ^ResultSet result-set]
    (fetch-rows t/read-row isql result-set))
  ([row-maker isql ^ResultSet result-set]
    (let [rows (transient [])
          ^ResultSetMetaData rsmd (.getMetaData result-set)
          column-count (.getColumnCount rsmd)]
      (while (.next result-set)
        (conj! rows (row-maker isql result-set column-count)))
      (persistent! rows))))


(defn fetch-single-row
  "Given java.sql.ResultSet and asphalt.type.ISql instances ensure result has exactly one row and fetch it using
  ISql."
  ([isql ^ResultSet result-set]
    (fetch-single-row t/read-row isql result-set))
  ([row-maker isql ^ResultSet result-set]
    (if (.next result-set)
      (let [^ResultSetMetaData rsmd (.getMetaData result-set)
            column-count (.getColumnCount rsmd)
            row (row-maker isql result-set column-count)]
        (if (.next result-set)
          (let [sql (t/get-sql isql)]
            (throw (ex-info (str "Expected exactly one JDBC result row, but found more than one for SQL: " sql)
                     {:sql sql :multi? true})))
          row))
      (let [sql (t/get-sql isql)]
        (throw (ex-info (str "Expected exactly one JDBC result row, but found no result row for SQL: " sql)
                 {:sql sql :empty? true}))))))


(defn fetch-single-value
  "Given java.sql.ResultSet and asphalt.type.ISql instances, ensure result has exactly one row and one column, and
  fetch it using ISql."
  ([isql ^ResultSet result-set]
    (fetch-single-value t/read-col isql result-set))
  ([column-reader isql ^ResultSet result-set]
    (let [^ResultSetMetaData rsmd (.getMetaData result-set)
          column-count (.getColumnCount rsmd)]
      (when (not= 1 column-count)
        (let [sql (t/get-sql isql)]
          (throw (ex-info (str "Expected exactly one JDBC result column but found " column-count " for SQL: " sql)
                   {:column-count column-count
                    :sql sql}))))
      (if (.next result-set)
        (let [column-value (column-reader isql result-set 1)]
          (if (.next result-set)
            (let [sql (t/get-sql isql)]
              (throw (ex-info (str "Expected exactly one JDBC result row, but found more than one for SQL: " sql)
                       {:sql sql :multi? true})))
            column-value))
        (let [sql (t/get-sql isql)]
          (throw (ex-info (str "Expected exactly one JDBC result row, but found no result row for SQL: " sql)
                   {:sql sql :empty? true})))))))


;; ----- working with asphalt.type.IConnectionSource -----


(defmacro with-connection
  "Bind `connection` (symbol) to a connection obtained from specified source, evaluating the body of code in that
  context. Return connection in the end."
  [[connection connection-source] & body]
  (when-not (symbol? connection)
    (i/unexpected "a symbol" connection))
  `(let [conn-source# ~connection-source
         ~(if (:tag (meta connection))
            connection
            (vary-meta connection assoc :tag java.sql.Connection)) (t/obtain-connection conn-source#)]
     (try ~@body
       (finally
         (t/return-connection conn-source# ~connection)))))


;; ----- transactions -----


(defn wrap-transaction
  "Given an arity-1 connection-worker fn, wrap it such that it prepares the specified transaction context on the
  java.sql.Connection argument first."
  ([connection-worker]
    (wrap-transaction connection-worker nil))
  ([connection-worker transaction-isolation]
    (fn wrapper [connection-source]
      (with-connection [^Connection connection connection-source]
        (.setAutoCommit connection false)
        (when-not (nil? transaction-isolation)
          (.setTransactionIsolation connection (i/resolve-txn-isolation transaction-isolation)))
        (try
          (let [result (connection-worker connection)]
            (.commit connection)
            result)
          (catch Exception e
            (try
              (.rollback connection)
              (catch Exception _)) ; ignore auto-rollback exceptions
            (throw e)))))))


(defn invoke-with-transaction
  "Obtain java.sql.Connection object from specified source, setup a transaction calling f with the connection as
  argument. Close the connection in the end and return what f returned. Transaction is committed if f returns normally,
  rolled back in the case of any exception."
  ([f connection-source]
    (with-connection [^Connection connection connection-source]
      (.setAutoCommit connection false)
      (try
        (let [result (f connection)]
          (.commit connection)
          result)
        (catch Exception e
          (try
            (.rollback connection)
            (catch Exception _)) ; ignore auto-rollback exceptions
          (throw e)))))
  ([f connection-source isolation]
    (with-connection [^Connection connection connection-source]
      (.setAutoCommit connection false)
      (.setTransactionIsolation connection (i/resolve-txn-isolation isolation))
      (try
        (let [result (f connection)]
          (.commit connection)
          result)
        (catch Exception e
          (try
            (.rollback connection)
            (catch Exception _)) ; ignore auto-rollback exceptions
          (throw e))))))


(defmacro with-transaction
  "Bind `connection` (symbol) to a connection obtained from specified source, setup a transaction evaluating the
  body of code in that context. Close connection in the end. Transaction is committed if the code returns normally,
  rolled back in the case of any exception.
  See also: invoke-with-transaction"
  ([[connection connection-source] expr]
    (when-not (symbol? connection)
      (i/unexpected "a symbol" connection))
    `(invoke-with-transaction
       (^:once fn* [~(vary-meta connection assoc :tag java.sql.Connection)] ~expr) ~connection-source))
  ([[connection connection-source] isolation & body]
    (when-not (symbol? connection)
      (i/unexpected "a symbol" connection))
    `(invoke-with-transaction
       (^:once fn* [~(vary-meta connection assoc :tag java.sql.Connection)] ~@body) ~connection-source ~isolation)))


;; ----- java.sql.PreparedStatement (connection-worker) stuff -----


(defn query
  "Execute query with params and process the java.sql.ResultSet instance with result-set-worker. The java.sql.ResultSet
  instance is closed in the end, so result-set-worker should neither close it nor make a direct/indirect reference to
  it in the value it returns."
  ([result-set-worker connection-source sql-or-template params]
    (query t/set-params result-set-worker connection-source sql-or-template params))
  ([params-setter result-set-worker connection-source sql-or-template params]
    (with-connection [connection connection-source]
      (with-open [^PreparedStatement pstmt (i/prepare-statement connection
                                             (t/get-sql sql-or-template) false)]
        (params-setter sql-or-template pstmt params)
        (with-open [^ResultSet result-set (.executeQuery pstmt)]
          (result-set-worker sql-or-template result-set))))))


(defn genkey
  "Execute an update statement returning the keys generated by the statement. The generated keys are extracted from a
  java.sql.ResultSet instance using the optional result-set-worker argument."
  ([connection-source sql-or-template params]
    (genkey t/set-params fetch-single-value connection-source sql-or-template params))
  ([result-set-worker connection-source sql-or-template params]
    (genkey t/set-params result-set-worker connection-source sql-or-template params))
  ([params-setter result-set-worker connection-source sql-or-template params]
    (with-connection [connection connection-source]
      (with-open [^PreparedStatement pstmt (i/prepare-statement connection
                                             (t/get-sql sql-or-template) true)]
        (params-setter sql-or-template pstmt params)
        (.executeUpdate pstmt)
        (with-open [^ResultSet generated-keys (.getGeneratedKeys pstmt)]
          (result-set-worker sql-or-template generated-keys))))))


(defn update
  "Execute an update statement returning the number of rows impacted."
  ([connection-source sql-or-template params]
    (update t/set-params connection-source sql-or-template params))
  ([params-setter connection-source sql-or-template params]
    (with-connection [connection connection-source]
      (with-open [^PreparedStatement pstmt (i/prepare-statement connection
                                             (t/get-sql sql-or-template) false)]
        (params-setter sql-or-template pstmt params)
        (.executeUpdate pstmt)))))


(defn batch-update
  "Execute a SQL write statement with a batch of parameters returning the number of rows updated as a vector."
  ([connection-source sql-or-template batch-params]
    (batch-update t/set-params connection-source sql-or-template batch-params))
  ([params-setter connection-source sql-or-template batch-params]
    (with-connection [connection connection-source]
      (with-open [^PreparedStatement pstmt (i/prepare-statement connection
                                             (t/get-sql sql-or-template) false)]
        (doseq [params batch-params]
          (params-setter sql-or-template pstmt params)
          (.addBatch pstmt))
        (vec (.executeBatch pstmt))))))


;; ----- convenience functions and macros -----


(defn set-params-with-query-timeout
  "Return a params setter fn usable with asphalt.core/query, that times out on query execution and throws a
  java.sql.SQLTimeoutException instance. Supported by JDBC 4.0 (and higher) drivers only."
  [^long n-seconds]
  (fn [sql-or-template ^PreparedStatement pstmt params]
    (.setQueryTimeout pstmt n-seconds)
    (t/set-params sql-or-template pstmt params)))


(defmacro defquery
  "Compose a SQL query template and a fetch fn into a convenience arity-2 (data-source-or-connection, params) fn.
  Option map may include :params-setter corresponding to an arity-3 fn for setting query parameters."
  ([var-symbol sql result-set-worker]
    `(defquery ~var-symbol ~sql ~result-set-worker {}))
  ([var-symbol sql result-set-worker options]
    (let [sql-template-sym (gensym "sql-template-")]
      `(let [~sql-template-sym (parse-sql ~sql ~options)
             query-fetch# (if-let [params-setter# (:params-setter ~options)]
                            (partial query params-setter# ~result-set-worker)
                            (partial query ~result-set-worker))]
         (defn ~var-symbol
           ~(str "Execute SQL query using fetch fn " result-set-worker)
           [data-source-or-connection# params#]
           (query-fetch# data-source-or-connection# ~sql-template-sym params#))))))
