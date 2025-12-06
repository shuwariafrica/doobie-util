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

import java.time.LocalDateTime

import doobie.util.fragment.Fragment

/** Period columns for a temporal entity.
  *
  * SQL Server system-versioned temporal tables require two `DATETIME2` columns to track the period
  * of validity for each row:
  *   - `validFrom`: the start of the validity period (inclusive)
  *   - `validTo`: the end of the validity period (exclusive)
  *
  * These columns are declared with `GENERATED ALWAYS AS ROW START/END` and are managed
  * automatically by the database engine. The period is defined with
  * `PERIOD FOR SYSTEM_TIME (validFrom, validTo)`.
  *
  * ==Encoding current rows==
  * Current (non-historical) rows have `validTo` set to the maximum `DATETIME2` value:
  * `9999-12-31 23:59:59.9999999`. This convention allows temporal queries to use simple range
  * checks to distinguish current from historical data.
  *
  * ==Usage==
  * {{{
  * import ashtray.mssql.*
  * import java.time.LocalDateTime
  *
  * // Standard period using DATETIME2
  * val period: Period.DateTime2 = Period(
  *   validFrom = LocalDateTime.of(2024, 1, 1, 0, 0),
  *   validTo = LocalDateTime.of(9999, 12, 31, 23, 59, 59)
  * )
  *
  * // Check if this represents a current row
  * val isCurrent = period.validTo.getYear == 9999
  * }}}
  *
  * @tparam A the type of the period columns (typically `LocalDateTime` for `DATETIME2`)
  * @see [[Period.DateTime2]] for the standard type alias
  */
final case class Period[A](
  validFrom: A,
  validTo: A
)

/** Companion for [[Period]] providing type aliases and fragment builders. */
object Period:
  /** Period using `LocalDateTime` (standard for SQL Server `DATETIME2`).
    *
    * This is the canonical type for temporal table periods, matching the `DATETIME2` data type used
    * by SQL Server's system-versioned temporal tables.
    */
  type DateTime2 = Period[LocalDateTime]

  /** Fragment builder for period columns. Inlined for zero-overhead query composition.
    *
    * Generates a SQL fragment selecting the two period columns by name, suitable for inclusion in
    * `SELECT` clauses.
    *
    * ==Example==
    * {{{
    * val frag = Period.columns("ValidFrom", "ValidTo")
    * // Expands to: Fragment.const("ValidFrom, ValidTo")
    * }}}
    *
    * @param validFromName the name of the `ValidFrom` column
    * @param validToName the name of the `ValidTo` column
    * @return a SQL fragment selecting both period columns
    */
  inline def columns(validFromName: String, validToName: String): Fragment =
    Fragment.const(s"$validFromName, $validToName")

  /** SQL Server maximum DATETIME2 value used to mark current rows.
    *
    * SQL Server uses `9999-12-31 23:59:59.9999999` as the sentinel value for the `ValidTo` column
    * of current rows in temporal tables. This value represents "infinity" in the temporal model.
    */
  val MaxDateTime2: LocalDateTime = LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999999900)
end Period
