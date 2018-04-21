;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns asphalt.core-test
  (:require
    [clojure.test :refer :all]
    [asphalt.test-util :as u]
    [asphalt.core      :as a]
    [asphalt.param     :as p]
    [asphalt.result    :as r]
    [asphalt.type      :as t]
    [asphalt.transaction :as x])
  (:import
    [java.io  ByteArrayInputStream InputStream]
    [java.sql Date SQLTimeoutException]
    [clojure.lang ExceptionInfo]))


(defn test-fixture
  [f]
  (u/create-db)
  (f)
  (u/drop-db))


(use-fixtures :each test-fixture)


;"CREATE TABLE emp
;id     INT PRIMARY KEY AUTO_INCREMENT,
;name   VARCHAR(50) NOT NULL,
;salary INT NOT NULL,
;dept   VARCHAR(50),
;j_date DATE NOT NULL,
;bio    CLOB,
;pic    BLOB"


;; ----- templates -----


(a/defsql t-count  "SELECT ^int COUNT(*) FROM emp" {:result-set-worker a/fetch-single-value})

(a/defsql t-insert "INSERT INTO emp (name, salary, dept, j_date)
VALUES (^string $name, ^int $salary, ^string $dept, ^date $joined)" {:conn-worker a/genkey})

(a/defsql t-insert-np "INSERT INTO ^sql $emp-table (name, salary, dept, j_date)
VALUES (^string $name, ^int $salary, ^string $dept, ^date $joined)" {:conn-worker a/genkey})

(a/defsql t-select "SELECT ^string name, -- ^int age,
-- ^boolean gender,
^int salary, ^string dept, ^date j_date FROM emp")

(a/defsql t-crosstype-select "SELECT ^string j_date FROM emp")
(a/defsql t-crosstype-update "UPDATE emp SET j_date = ^string $jd")

(a/defsql t-selfew "SELECT ^^ name, ^^ salary, ^^ dept, ^^ j_date FROM emp WHERE name = ?")

(a/defsql t-qfetch "SELECT ^string name, ^int salary, ^string dept, ^date j_date FROM emp WHERE name = ?"
  {:result-set-worker a/fetch-single-row})

(a/defsql t-update "UPDATE emp SET salary = ^int $new-salary WHERE dept = ^string $dept")

(a/defsql t-delete "DELETE FROM emp")

(a/defsql t-batch-update "UPDATE emp SET salary = $new-salary WHERE id = $id")

(a/defsql t-insert-lobs "INSERT INTO emp (name, salary, dept, j_date, bio, pic)
VALUES (^string $name, ^int $salary, ^string $dept, ^date $joined, ^ascii-stream $bio, ^binary-stream $pic)"
  {:conn-worker a/genkey})

(a/defsql t-select-lobs "SELECT ^ascii-stream bio, ^binary-stream pic FROM emp WHERE id = $id"
  {:result-set-worker a/fetch-single-row})


;; ----- tests -----


(deftest test-crosstype
  (let [jd1 (u/make-date)
        vs1 ["Joe Coder" 100000 "Accounts" jd1]
        row (zipmap [:name :salary :dept :joined] vs1)]
    (let [generated-key (t-insert u/ds row)]
      (is (= 1 generated-key) "Verify that insertion generated a key"))
    ;; retrieval test
    (is (= 1 (t-count u/ds)) "Verify that row was inserted")
    (is (= (str jd1) (a/query a/fetch-single-value u/ds t-crosstype-select [])))
    (is (= [(str jd1)] (a/query a/fetch-single-row u/ds t-crosstype-select [])))
    (is (= [[(str jd1)]] (a/query a/fetch-rows u/ds t-crosstype-select [])))
    ;; update test
    (let [strdate "2016-02-01"]
      (t-crosstype-update u/ds [strdate])
      (is (= strdate (a/query a/fetch-single-value u/ds t-crosstype-select [])))
      (is (= [strdate] (a/query a/fetch-single-row u/ds t-crosstype-select [])))
      (is (= [[strdate]] (a/query a/fetch-rows u/ds t-crosstype-select []))))))


(deftest test-params
  (let [jd1 (u/make-date)
        vs1 ["Joe Coder" 100000 "Accounts" jd1]
        row (zipmap [:name :salary :dept :joined] vs1)
        vs2 ["Joe Coder" 110000 "Accounts" jd1]
        upa {:new-salary 110000
             :dept "Accounts"}
        upb [false "Accounts"]  ; bad params, first param should be int
        upv [110000 "Accounts"]]
    ;; create
    (let [params-setter (fn [sql-source prepared-statement params]
                          (p/lay-params prepared-statement [:name   :string
                                                            :salary :int
                                                            :dept   :string
                                                            :joined :date]
                            params))
          generated-key (a/genkey params-setter a/fetch-single-value
                          u/ds t-insert row)]
      (is (= 1 generated-key) "Verify that insertion generated a key"))
    (is (= 1 (t-count u/ds)) "Verify that row was inserted")
    ;; retrieve
    (is (= vs1 (a/query a/fetch-single-row
                 u/ds t-select [])))
    (is (= vs1 (a/query (fn [sql-source prepared-statement params]
                          (p/lay-params prepared-statement [:name :string] params))
                 a/fetch-single-row
                 u/ds t-selfew [(first vs1)])))
    (is (= vs1 (t-qfetch u/ds [(first vs1)])))
    ;; update
    (let [update-setter (fn [sql-source prepared-statement params]
                          (p/lay-params prepared-statement [:new-salary :int
                                                            :dept       :string] params))]
      (a/update update-setter u/ds t-update upa)
      (testing "bad vector params"
        (is (thrown? IllegalArgumentException
              (a/update u/ds t-update upb))))
      (testing "vanilla vector params"
        (a/update update-setter u/ds t-update upv))
      (is (= vs2 (a/query a/fetch-single-row
                   u/ds t-select []))))))


(deftest test-crud
  (let [jd1 (u/make-date)
        vs1 ["Joe Coder" 100000 "Accounts" jd1]
        row (zipmap [:name :salary :dept :joined] vs1)
        vs2 ["Joe Coder" 110000 "Accounts" jd1]
        upa {:new-salary 110000
             :dept "Accounts"}
        upb [false "Accounts"]  ; bad params, first param should be int
        upv [110000 "Accounts"]]
    ;; create
    (let [generated-key (t-insert u/ds row)]
      (is (= 1 generated-key) "Verify that insertion generated a key"))
    (is (= 1 (t-count u/ds)) "Verify that row was inserted")
    ;; retrieve
    (is (= vs1 (a/query a/fetch-single-row u/ds t-select [])))
    (is (= vs1 (a/query a/fetch-single-row u/ds t-selfew [(first vs1)])))
    (is (= vs1 (t-qfetch u/ds [(first vs1)])))
    ;; update
    (a/update u/ds t-update upa)
    (testing "bad vector params"
      (is (thrown? IllegalArgumentException
            (a/update u/ds t-update upb))))
    (testing "vanilla vector params"
      (a/update u/ds t-update upv))
    (is (= vs2 (a/query a/fetch-single-row u/ds t-select [])))
    ;; delete
    (a/update u/ds t-delete [])
    (is (= 0 (t-count u/ds)) "Verify that row was deleted")))


(deftest test-non-params
  (let [jd1 (u/make-date)
        vs1 ["Joe Coder" 100000 "Accounts" jd1]
        row (zipmap [:name :salary :dept :joined] vs1)
        generated-key (t-insert-np u/ds (assoc row :emp-table "emp"))]
    (is (= 1 generated-key) "Verify that insertion generated a key")
    (is (= 1 (t-count u/ds)) "Verify that row was inserted")))


(deftest test-rows
  (let [jd1 (u/make-date)
        vs1 ["Joe Coder" 100000 "Accounts" jd1]
        row (zipmap [:name :salary :dept :joined] vs1)]
    ;; fetch single row in absence of rows
    (is (= vs1 (a/query (partial a/fetch-optional-row {:default vs1})
                 u/ds t-select [])))
    ;; fetch single column value in absence of rows
    (is (= (a/query (partial a/fetch-optional-value {:default 1000 :column-index 2})
             u/ds t-selfew ["Harry"])
          1000))
    ;; 50 rows
    (dotimes [_ 50]
      (t-insert u/ds row))
    (is (= 50 (t-count u/ds)) "Verify that all rows were inserted")
    (doseq [each (a/query a/fetch-rows
                   u/ds t-select [])]
      (is (= vs1 each)))
    ;; fetch single row in presence of multiple rows
    (is (= vs1 (a/query (partial a/fetch-optional-row {:on-multi r/ignore-multi})
                 u/ds t-select [])))
    ;; fetch single column value in absence of rows
    (is (= (a/query (partial a/fetch-optional-value {:column-reader (r/make-column-value-reader :int 2)
                                                     :on-multi r/ignore-multi})
             u/ds t-selfew ["Joe Coder"])
          100000))
    ;; test lay-params
    (a/update (fn [sql-source pstmt params]
                (p/lay-params pstmt [:name   :string
                                     :salary :int
                                     :dept   :string
                                     :joined :date]
                  (update-in params [:joined] p/date->cal :utc)))
      u/ds t-insert row)
    (is (= 51 (t-count u/ds)) "Verify that row was inserted")
    ;; test set-params
    (a/update (fn [sql-source pstmt params]
                (p/set-params pstmt [:name   :string
                                     :salary :int
                                     :dept   :string
                                     :joined :date]
                  (update-in params [:joined] p/date->cal :utc)))
      u/ds t-insert row)
    (is (= 52 (t-count u/ds)) "Verify that row was inserted")
    ;; test fetch-maps
    (testing "fetch-maps"
      (let [rows (a/query a/fetch-maps
                   u/ds t-selfew ["Joe Coder"])]
        (is (= (zipmap [:name :salary :dept :j_date] vs1) (first rows))))
      (let [rows (a/query (partial a/fetch-maps {:key-maker r/_label->key})
                   u/ds t-selfew ["Joe Coder"])]
        (is (= (zipmap [:name :salary :dept :j-date] vs1) (first rows)))))
    ;; test letcol
    (testing "letcol"
      (let [run-query (fn [row-maker]
                        (a/query (partial a/fetch-single-row {:row-maker row-maker
                                                              :on-multi (fn [_ _ v] v)})
                          u/ds t-selfew ["Joe Coder"]))]
        (is (= vs1 (run-query (fn [_ rs _]
                                (r/letcol [[name salary dept joined] rs]
                                  [name salary dept joined])))))
        (is (= vs1 (run-query (fn [_ rs _]
                                (r/letcol [[^string name ^int salary ^string dept ^date joined] rs]
                                  [name salary dept joined])))))
        (is (= vs1 (run-query (fn [_ rs _]
                                (r/letcol [[^string name ^int salary ^string dept [^date joined :utc]] rs]
                                  [name salary dept joined])))))
        (is (= vs1 (run-query (fn [_ rs _]
                                (r/letcol [{:labels  [^string name]
                                            :_labels [^date   j-date]
                                            ^int salary 2
                                            ^string dept 3} rs]
                                  [name salary dept j-date])))))
        (is (= vs1 (run-query (fn [_ rs _]
                                (r/letcol [{:labels  [^string name [^date j_date :utc]]
                                            :_labels [^int salary]
                                            ^string dept 3} rs]
                                  [name salary dept j_date])))))
        (is (= vs1 (run-query (fn [_ rs _]
                                (r/letcol [{:labels  [^string name]
                                            :_labels [^int salary]
                                            ^string dept 3
                                            [^date   joined :utc] 4} rs]
                                  [name salary dept joined])))))))))


(deftest test-batch-update
  (let [jd1 (u/make-date)]
    (a/batch-update u/ds t-insert [["Joe Coder"     100000 "Accounts"  jd1]
                                   ["Harry Hacker"   90000 "R&D"       jd1]
                                   ["Sam Librarian"  85000 "Library"   jd1]
                                   ["Kishore Newbie" 55000 "Sales"     jd1]
                                   ["Neal Manager"  110000 "Marketing" jd1]]))
  (a/batch-update u/ds t-batch-update [[100001 1]
                                       [ 90002 2]
                                       [ 85003 3]
                                       [ 55004 4]
                                       [110005 5]])
  (is (= [[100001]
          [ 90002]
          [ 85003]
          [ 55004]
          [110005]]
        (a/query a/fetch-rows u/ds "SELECT salary FROM emp" []))))


(deftest test-query-timeout
  (let [jd1 (u/make-date)
        vs1 ["Joe Coder" 100000 "Accounts" jd1]
        row (zipmap [:name :salary :dept :joined] vs1)]
    (let [generated-key (t-insert u/delay-ds row)]
      (is (= 1 generated-key) "Verify that insertion generated a key"))
    (is (thrown? SQLTimeoutException
          (a/query (p/set-params-with-query-timeout 1) a/fetch-single-value
            u/delay-ds t-count [])) "Query should throw exception on delay")))


(defn str->stream
  ^InputStream
  [^String s]
  (ByteArrayInputStream. (.getBytes s)))


(defn slurp-bytes
  "Slurp the bytes from a slurpable thing"
  [x]
  (with-open [out (java.io.ByteArrayOutputStream.)]
    (clojure.java.io/copy (clojure.java.io/input-stream x) out)
    (.toByteArray out)))


(deftest test-lobs
  (let [jd1 (u/make-date)
        bio "This is my bio"
        pic [10 20 30]
        vs  {:name "Joe" :salary 12345 :dept "Electrical"
             :joined jd1 :bio (str->stream bio) :pic (ByteArrayInputStream. (byte-array pic))}
        id  (t-insert-lobs u/ds vs)
        [dbio dpic] (t-select-lobs u/ds {:id id})]
    (is (= bio (slurp dbio)))
    (is (= pic (vec (slurp-bytes dpic))))))
