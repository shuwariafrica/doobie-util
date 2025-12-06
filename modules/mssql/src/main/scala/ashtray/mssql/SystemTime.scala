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

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

import doobie.implicits.*
import doobie.util.fragment.Fragment

/** Temporal query mode — sealed ADT for `FOR SYSTEM_TIME` clauses.
  *
  * SQL Server system-versioned temporal tables support five distinct temporal query modes via the
  * `FOR SYSTEM_TIME` clause. Each mode defines different semantics for selecting rows based on
  * their validity period.
  *
  * ==Query modes==
  *   - [[SystemTime.AsOf AsOf]]: Point-in-time snapshot at a specific instant
  *   - [[SystemTime.FromTo FromTo]]: Rows overlapping a period (exclusive bounds)
  *   - [[SystemTime.Between Between]]: Rows overlapping a period (inclusive end bound)
  *   - [[SystemTime.ContainedIn ContainedIn]]: Rows entirely within a period
  *   - [[SystemTime.All All]]: All rows (current + history)
  *
  * ==Fragment generation==
  * Each mode provides an inlined `toFragment` method that generates the corresponding SQL clause.
  * This ensures zero-overhead query composition at the call site.
  *
  * ==Usage==
  * {{{
  * import ashtray.mssql.*
  * import java.time.Instant
  *
  * val now = Instant.now
  * val lastMonth = now.minusSeconds(30 * 24 * 3600)
  *
  * // Point-in-time query
  * val asOf: SystemTime = SystemTime.asOf(lastMonth)
  *
  * // Range query with exclusive end
  * val fromTo: SystemTime = SystemTime.fromTo(lastMonth, now)
  *
  * // Range query with inclusive end
  * val between: SystemTime = SystemTime.between(lastMonth, now)
  *
  * // Rows contained within period
  * val contained: SystemTime = SystemTime.containedIn(lastMonth, now)
  *
  * // All rows
  * val all: SystemTime = SystemTime.All
  * }}}
  *
  * @see
  *   [[https://learn.microsoft.com/en-us/sql/relational-databases/tables/querying-data-in-a-system-versioned-temporal-table?view=sql-server-ver17]]
  */
sealed trait SystemTime derives CanEqual:
  /** Fragment generation inlined at call site for zero-overhead composition.
    *
    * Each concrete case class implements this method to produce the appropriate SQL clause fragment
    * for its temporal semantics.
    */
  inline def toFragment: Fragment

/** Companion for [[SystemTime]] providing case classes and smart constructors. */
object SystemTime:
  /** Row valid at exact instant. Returns the row version that was active at the specified point in
    * time.
    *
    * ==SQL semantics==
    * Selects rows where: `ValidFrom <= instant AND ValidTo > instant`
    *
    * This corresponds to SQL Server's `FOR SYSTEM_TIME AS OF` clause.
    *
    * ==Use cases==
    *   - Reconstruct entity state at a specific point in time
    *   - Audit queries: "What was the value on date X?"
    *   - Point-in-time reporting and snapshots
    *
    * @param instant the point in time to query
    */
  final case class AsOf(instant: LocalDateTime) extends SystemTime:
    inline def toFragment: Fragment = fr"FOR SYSTEM_TIME AS OF $instant"

  /** Rows overlapping (from, to) — exclusive bounds.
    *
    * ==SQL semantics==
    * Selects rows where: `ValidFrom < to AND ValidTo > from`
    *
    * This corresponds to SQL Server's `FOR SYSTEM_TIME FROM ... TO` clause.
    *
    * ==Boundary behavior==
    *   - Rows that started being active ''before'' `from` are '''excluded'''
    *   - Rows that stopped being active ''at or before'' `from` are '''excluded'''
    *   - Rows that became active ''at or after'' `to` are '''excluded'''
    *
    * ==Use cases==
    *   - Analyze changes within a time window
    *   - Audit trails with exclusive end boundary
    *   - Period-based analytics
    *
    * @param from the start of the period (exclusive lower bound)
    * @param to the end of the period (exclusive upper bound)
    */
  final case class FromTo(from: LocalDateTime, to: LocalDateTime) extends SystemTime:
    inline def toFragment: Fragment = fr"FOR SYSTEM_TIME FROM $from TO $to"

  /** Rows overlapping [from, to] — inclusive end bound.
    *
    * ==SQL semantics==
    * Selects rows where: `ValidFrom <= to AND ValidTo > from`
    *
    * This corresponds to SQL Server's `FOR SYSTEM_TIME BETWEEN ... AND` clause.
    *
    * ==Boundary behavior==
    *   - Rows that became active ''at'' `to` are '''included''' (difference from `FromTo`)
    *   - Otherwise identical to `FromTo` semantics
    *
    * ==Use cases==
    *   - Audit queries with inclusive end boundary
    *   - Time-range analysis where end boundary should be included
    *   - Compliance reporting with specific date ranges
    *
    * @param from the start of the period
    * @param to the end of the period (inclusive)
    */
  final case class Between(from: LocalDateTime, to: LocalDateTime) extends SystemTime:
    inline def toFragment: Fragment = fr"FOR SYSTEM_TIME BETWEEN $from AND $to"

  /** Rows entirely within (from, to).
    *
    * ==SQL semantics==
    * Selects rows where: `ValidFrom >= from AND ValidTo <= to`
    *
    * This corresponds to SQL Server's `FOR SYSTEM_TIME CONTAINED IN` clause.
    *
    * ==Boundary behavior==
    *   - Only rows that '''started''' at or after `from` are included
    *   - Only rows that '''ended''' at or before `to` are included
    *   - Rows that span beyond the period boundaries are '''excluded'''
    *
    * ==Use cases==
    *   - Find rows with validity periods completely within a time window
    *   - Precision auditing: "Show only changes that occurred entirely during this period"
    *   - Compliance queries with strict period containment
    *
    * @param from the start of the period (inclusive)
    * @param to the end of the period (inclusive)
    */
  final case class ContainedIn(from: LocalDateTime, to: LocalDateTime) extends SystemTime:
    inline def toFragment: Fragment = fr"FOR SYSTEM_TIME CONTAINED IN ($from, $to)"

  /** All rows: current + history.
    *
    * ==SQL semantics==
    * Returns the union of all rows from both the current table and the history table, without any
    * temporal filtering.
    *
    * This corresponds to SQL Server's `FOR SYSTEM_TIME ALL` clause.
    *
    * ==Use cases==
    *   - Full history analysis
    *   - Complete audit trails
    *   - Data migrations and exports
    *   - Trend analysis across all time
    *
    * ==Note==
    * Queries using `ALL` can be expensive on large temporal tables. Consider using more specific
    * temporal modes when possible, or add additional `WHERE` clauses to filter the result set.
    */
  case object All extends SystemTime:
    inline def toFragment: Fragment = fr"FOR SYSTEM_TIME ALL"

  // === Smart constructors for Instant ===
  // Inlined for zero-overhead Instant→LocalDateTime conversion at call site

  /** Create an `AsOf` query from an `Instant`.
    *
    * Converts the instant to `LocalDateTime` in UTC timezone for SQL Server compatibility.
    *
    * @param instant the point in time to query
    * @return an `AsOf` query mode
    */
  inline def asOf(instant: Instant): AsOf =
    AsOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC))

  /** Create a `FromTo` query from `Instant` values.
    *
    * Converts both instants to `LocalDateTime` in UTC timezone.
    *
    * @param from the start of the period (exclusive lower bound)
    * @param to the end of the period (exclusive upper bound)
    * @return a `FromTo` query mode
    */
  inline def fromTo(from: Instant, to: Instant): FromTo =
    FromTo(LocalDateTime.ofInstant(from, ZoneOffset.UTC), LocalDateTime.ofInstant(to, ZoneOffset.UTC))

  /** Create a `Between` query from `Instant` values.
    *
    * Converts both instants to `LocalDateTime` in UTC timezone.
    *
    * @param from the start of the period
    * @param to the end of the period (inclusive)
    * @return a `Between` query mode
    */
  inline def between(from: Instant, to: Instant): Between =
    Between(LocalDateTime.ofInstant(from, ZoneOffset.UTC), LocalDateTime.ofInstant(to, ZoneOffset.UTC))

  /** Create a `ContainedIn` query from `Instant` values.
    *
    * Converts both instants to `LocalDateTime` in UTC timezone.
    *
    * @param from the start of the period (inclusive)
    * @param to the end of the period (inclusive)
    * @return a `ContainedIn` query mode
    */
  inline def containedIn(from: Instant, to: Instant): ContainedIn =
    ContainedIn(LocalDateTime.ofInstant(from, ZoneOffset.UTC), LocalDateTime.ofInstant(to, ZoneOffset.UTC))
end SystemTime
