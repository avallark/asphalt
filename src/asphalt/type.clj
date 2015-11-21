;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns asphalt.type
  (:import
    [java.sql Connection PreparedStatement ResultSet]))


(defprotocol IConnectionSource
  (obtain-connection            [this] "Obtain connection from the source")
  (return-connection [this connection] "Return connection to the source"))


(defprotocol ISql
  (get-sql    [this] "Return SQL string to be executed")
  (set-params [this ^PreparedStatement prepared-statement params] "Set prepared-statement params")
  (read-col   [this ^ResultSet result-set column-index] "Read column at specified index (1 based) from result-set")
  (read-row   [this ^ResultSet result-set column-count] "Read specified number of columns (starting at 1) as a row"))


(defprotocol ITransactionPropagation
  (begin-txn    [this ^Connection connection isolation]   "Begin transaction and return the context")
  (commit-txn   [this ^Connection connection txn-context] "Commit current transaction")
  (rollback-txn [this ^Connection connection txn-context] "Rollback current transaction")
  (end-txn      [this ^Connection connection txn-context] "End transaction"))


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


(defrecord StmtCreationEvent [^String sql
                              ;; #{:statement :prepared-statement :prepared-call}
                              jdbc-stmt-type])


(defrecord SQLExecutionEvent [^boolean prepared? ^String sql
                              ;; #{:sql :sql-query :sql-update}
                              sql-stmt-type])
