# Changelog

## TODO and Ideas

* [TODO] Support stored procedure call
* [TODO] Params setter can be specified via `defsql`
* [TODO] New macros - `defgenkey`, `defupdate`


## 0.4.0 / 2015-????-??

* Redundant/unwanted type hint was omitted to avoid compilation error with Clojure 1.8.0
* Fetching SQL statement
  * Rename `asphalt.type.ISql` to `asphalt.type.ISqlSource` (internal breaking change)
* JDBC connections are obtained from and returned to an abstraction `asphalt.type.IConnectionSource`
  * Extends to `java.sql.Connection` and `javax.sql.DataSource` (already supported)
  * Extends to map for [clojure/java.jdbc](https://github.com/clojure/java.jdbc) compatibility (new feature)
* Instrumentation endpoint `instrument-connection-source` for creating JDBC connections
  * [TODO] Deprecate/remove `instrument-datasource` (breaking change)
* Overhauled transaction API
  * All transaction management code moved to new namespace `asphalt.transaction` (breaking change)
  * `with-transaction` accepts an option map as second argument (breaking change)
  * Option keys `:result-success?`, `:error-failure?`, `:isolation`, `:propagation`
  * Transaction propagation is a first class abstraction `asphalt.type.ITransactionPropagation`
  * Nested transactions support (using savepoints)


## 0.3.0 / 2015-October-08

* Require Java 7 due to DataSource instrumentation
* Logic errors now throw `clojure.lang.ExceptionInfo` with meta data
* Pervasive instrumentation of `javax.sql.DataSource` objects
* Helper macro `defquery` to bind SQL templates to intended operations
* Helper fn `set-params-with-query-timeout` as `set-params` replacement


## 0.2.1 / 2015-September-23

* When error encountered setting a parameter, include parameter index/key in error message
* Use ex-info (`ExceptionInfo`) for runtime errors


## 0.2.0 / 2015-September-07

* Expect type hints _before_ subjects, consistent with Clojure
* New SQL abstraction: `asphalt.type.ISql`
* ISql supports string, map and `SQLTemplate` out of the box
* Performance improvement via primitive arrays in `SQLTemplate`
* Make type hint parsing robust (comments, string tokens etc.)


## 0.1.2 / 2015-July-07

* Version 0.1.1 introduced an array lookup bug, which caused the bug it meant to fix still open.
  This release fixes the array lookup bug, thereby also fixing the bug that 0.1.1 meant to fix.


## 0.1.1 / 2015-July-06

* Fix issue where default param setter wrongly assumed named params are always set in a SQL template


## 0.1.0 / 2015-June-19

* Support for SQL-template with type annotations
* Named parameters for SQL templates
* Functions (result-set-worker) to extract data from `java.sql.ResultSet`
* JDBC update and query operations (with `java.sql.Connection` and `javax.sql.DataSource`)
* Extraction of generated keys
* Transaction (auto-rollback on exceptions)
