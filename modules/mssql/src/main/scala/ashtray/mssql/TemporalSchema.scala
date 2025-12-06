/****************************************************************
 * Copyright © Shuwari Africa Ltd.                              *
 *                                                              *
 * This file is licensed to you under the terms of the Apache   *
 * License Version 2.0 (the "License"); you may not use this    *
 * file except in compliance with the License. You may obtain   *
 * a copy of the License at:                                    *
 *                                                              *
 *     https://www.apache.org/licenses/LICENSE-2.0              *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, *
 * either express or implied. See the License for the specific  *
 * language governing permissions and limitations under the     *
 * License.                                                     *
 ****************************************************************/
package ashtray.mssql

import scala.annotation.publicInBinary

import doobie.implicits.*
import doobie.util.fragment.Fragment

/** Schema metadata for a system-versioned temporal table.
  *
  * `TemporalSchema[ID, A]` captures the structural information needed to query a SQL Server
  * system-versioned temporal table: table names, column names, and the entity type `A` with primary
  * key type `ID`.
  *
  * ==Required metadata==
  *   - '''Table names''': current table and history table
  *   - '''ID column''': primary key column name for filtering by entity ID
  *   - '''Period columns''': `ValidFrom` and `ValidTo` column names
  *   - '''Entity columns''': fragment listing all entity columns (excluding period columns)
  *
  * ==Design principles==
  * {{{
  * 1. No naming enforcement — users control their schema
  * 2. Inline fragment builders — zero overhead at runtime
  * 3. Type-indexed — one schema per (ID, entity) pair
  * 4. Immutable — schema is fixed at construction
  * }}}
  *
  * ==Usage==
  * {{{
  * import ashtray.mssql.*
  * import doobie.implicits.*
  *
  * case class Employee(id: Identifier, name: String, salary: BigDecimal)
  *
  * given TemporalSchema[Identifier, Employee] = TemporalSchema(
  *   table = "dbo.Employee",
  *   history = "dbo.EmployeeHistory",
  *   id = "EmployeeID",
  *   validFrom = "ValidFrom",
  *   validTo = "ValidTo",
  *   cols = fr"EmployeeID, Name, Salary"
  * )
  *
  * // Now TemporalRepo[IO, Identifier, Employee] can be derived
  * }}}
  *
  * @tparam ID the primary key type
  * @tparam A the entity type this schema describes
  * @see [[TemporalRepo]] for repository derivation using this schema
  */
trait TemporalSchema[ID, A]:
  /** Current table name (with schema if needed, e.g. `"dbo.Employee"`). */
  def tableName: String

  /** History table name (with schema if needed, e.g. `"dbo.EmployeeHistory"`). */
  def historyTableName: String

  /** Primary key column name used for filtering by entity ID. */
  def idColumn: String

  /** `ValidFrom` column name (or equivalent period start column). */
  def validFromColumn: String

  /** `ValidTo` column name (or equivalent period end column). */
  def validToColumn: String

  /** Fragment listing all entity columns (excluding period columns).
    *
    * Must match the field order of the entity type for automatic `Read`/`Write` derivation.
    */
  def columns: Fragment

  /** Period columns fragment. Inlined for hot query building.
    *
    * Generates: `ValidFrom, ValidTo`
    */
  final inline def periodColumns: Fragment =
    Fragment.const(s"$validFromColumn, $validToColumn")

  /** All columns = entity + period. Inlined for zero-overhead composition.
    *
    * Used in `SELECT` clauses when reading `Temporal[A, M]` instances.
    */
  final inline def allColumns: Fragment =
    columns ++ fr", " ++ periodColumns

  /** Table + `FOR SYSTEM_TIME` clause. Inlined for zero-overhead composition.
    *
    * Combines the table name with the appropriate temporal query mode, generating SQL like:
    * {{{
    *   dbo.Employee FOR SYSTEM_TIME AS OF '2024-01-15 10:00:00'
    *   dbo.Employee FOR SYSTEM_TIME FROM '2024-01-01' TO '2024-12-31'
    *   dbo.Employee FOR SYSTEM_TIME ALL
    * }}}
    *
    * ==Example==
    * {{{
    * val schema = summon[TemporalSchema[Identifier, Employee]]
    * val systemTime = SystemTime.asOf(Instant.now)
    * val tableClause = schema.forSystemTime(systemTime)
    * // Use in query: fr"SELECT * FROM" ++ tableClause
    * }}}
    *
    * @param mode the temporal query mode (AsOf, FromTo, Between, ContainedIn, All)
    * @return fragment combining table name and FOR SYSTEM_TIME clause
    */
  final inline def forSystemTime(inline mode: SystemTime): Fragment =
    Fragment.const(tableName) ++ fr" " ++ mode.toFragment
end TemporalSchema

/** Companion for [[TemporalSchema]] providing smart constructor. */
object TemporalSchema:
  /** Construct a `TemporalSchema[ID, A]` from inline parameters.
    *
    * All parameters are captured at compile time and stored in an implementation class with inline
    * method implementations for zero-cost fragment generation.
    *
    * ==Example==
    * {{{
    * given TemporalSchema[Identifier, Employee] = TemporalSchema(
    *   table = "dbo.Employee",
    *   history = "dbo.EmployeeHistory",
    *   id = "EmployeeID",
    *   validFrom = "ValidFrom",
    *   validTo = "ValidTo",
    *   cols = fr"EmployeeID, Name, Salary"
    * )
    * }}}
    *
    * @param table current table name
    * @param history history table name
    * @param id primary key column name
    * @param validFrom period start column name
    * @param validTo period end column name
    * @param cols entity columns fragment
    * @return a schema instance for type `A` with primary key type `ID`
    */
  inline def apply[ID, A](
    inline table: String,
    inline history: String,
    inline id: String,
    inline validFrom: String,
    inline validTo: String,
    inline cols: Fragment
  ): TemporalSchema[ID, A] =
    new TemporalSchemaImpl[ID, A](table, history, id, validFrom, validTo, cols)

  class TemporalSchemaImpl[ID, A] @publicInBinary private[mssql] (
    table: String,
    history: String,
    id: String,
    validFrom: String,
    validTo: String,
    cols: Fragment
  ) extends TemporalSchema[ID, A]:
    inline def tableName: String = table
    inline def historyTableName: String = history
    inline def idColumn: String = id
    inline def validFromColumn: String = validFrom
    inline def validToColumn: String = validTo
    inline def columns: Fragment = cols
  end TemporalSchemaImpl
end TemporalSchema
