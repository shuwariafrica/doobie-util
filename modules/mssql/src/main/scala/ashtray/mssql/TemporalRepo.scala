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

import scala.annotation.publicInBinary
import scala.annotation.unused

import cats.syntax.all.*

import doobie.*
import doobie.implicits.*
import doobie.util.Put

/** Repository for temporal queries. User provides schema; library provides operations.
  *
  * `TemporalRepo[F, ID, A]` is the core abstraction for querying SQL Server system-versioned
  * temporal tables. It provides a high-level API for common temporal operations without requiring
  * users to manually construct `FOR SYSTEM_TIME` clauses.
  *
  * ==Architecture==
  * {{{
  *   TemporalSchema[ID, A]    // User-provided metadata
  *         │
  *         ├──► TemporalRepo[F, ID, A]   // Derived repository
  *         │           │
  *         │           ├──► asOf          // Point-in-time queries
  *         │           ├──► history       // Full history
  *         │           ├──► diff          // Comparisons
  *         │           └──► restoreTo     // Rollback
  *         │
  *         └──► Transactor[F]             // Connection management
  * }}}
  *
  * ==Derivation==
  * Repositories are derived automatically from a `TemporalSchema[ID, A]` using the `derived`
  * method. This generates all query implementations inline at compile time:
  *
  * {{{
  * import ashtray.mssql.*
  * import cats.effect.IO
  * import doobie.Transactor
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
  * def repo(using xa: Transactor[IO]): TemporalRepo[IO, Identifier, Employee] =
  *   TemporalRepo.derived[IO, Identifier, Employee]
  * }}}
  *
  * ==Query categories==
  * '''Point-in-time queries''':
  *   - `asOf`: single entity at specific instant
  *   - `allAsOf`: all entities at specific instant
  *
  * '''Range queries''':
  *   - `history`: complete history for one entity
  *   - `historyBetween`: history within time range
  *   - `containedIn`: versions fully within time range
  *
  * '''Comparison and analysis''':
  *   - `diff`: compare entity at two instants
  *
  * '''Modification''':
  *   - `restoreTo`: rollback entity to historical state
  *   - `current`: get current state (no temporal clause)
  *
  * @tparam F effect type (typically `IO` or `ConnectionIO`)
  * @tparam ID primary key type (must have `Put` instance)
  * @tparam A entity type (must have `Read` and `Write` instances)
  * @see [[TemporalRepo.derived]] for automatic derivation
  * @see [[TemporalSchema]] for metadata specification
  */
trait TemporalRepo[F[_], ID, A]:
  /** Schema metadata used by this repository. */
  def schema: TemporalSchema[ID, A]

  // === Point-in-time queries ===

  /** Entity state at exact instant. Returns the row version active at the specified point in time.
    *
    * Uses `FOR SYSTEM_TIME AS OF` to retrieve the entity that was current at the given instant.
    * Returns `None` if no version existed at that time.
    *
    * ==Example==
    * {{{
    * val yesterday = Instant.now.minusSeconds(86400)
    * val snapshot: IO[Option[Temporal.Versioned[Employee]]] =
    *   repo.asOf(employeeId, yesterday)
    * }}}
    *
    * @param id entity primary key
    * @param instant point in time to query
    * @return entity version active at the instant, or None if not found
    */
  def asOf(id: ID, instant: Instant): F[Option[Temporal.Versioned[A]]]

  /** All entities at exact instant. Returns all current entity versions at the specified time.
    *
    * Uses `FOR SYSTEM_TIME AS OF` without an ID filter to retrieve a complete snapshot of the table
    * at a specific point in time.
    *
    * ==Example==
    * {{{
    * val lastWeek = Instant.now.minusSeconds(7 * 86400)
    * val snapshot: IO[List[Temporal.Versioned[Employee]]] =
    *   repo.allAsOf(lastWeek)
    * }}}
    *
    * @param instant point in time to query
    * @return all entity versions active at the instant
    */
  def allAsOf(instant: Instant): F[List[Temporal.Versioned[A]]]

  // === Range queries ===

  /** Complete history for one entity. Returns all versions ordered chronologically (oldest first).
    *
    * Uses `FOR SYSTEM_TIME ALL` to retrieve every version of the entity from creation to current
    * state, including deleted records.
    *
    * ==Example==
    * {{{
    * val allVersions: IO[List[Temporal.Versioned[Employee]]] =
    *   repo.history(employeeId)
    * }}}
    *
    * @param id entity primary key
    * @return all versions of the entity, ordered by ValidFrom ascending
    */
  def history(id: ID): F[List[Temporal.Versioned[A]]]

  /** History within time range (exclusive bounds). Returns versions overlapping the specified
    * period.
    *
    * Uses `FOR SYSTEM_TIME FROM...TO` which includes rows where:
    *   - `ValidFrom < to` AND `ValidTo > from`
    *
    * The `to` bound is exclusive, matching SQL Server semantics.
    *
    * ==Example==
    * {{{
    * val lastMonth = Instant.now.minusSeconds(30 * 86400)
    * val now = Instant.now
    * val recentHistory: IO[List[Temporal.Versioned[Employee]]] =
    *   repo.historyBetween(employeeId, lastMonth, now)
    * }}}
    *
    * @param id entity primary key
    * @param from start of time range (inclusive)
    * @param to end of time range (exclusive)
    * @return versions overlapping the time range
    */
  def historyBetween(id: ID, from: Instant, to: Instant): F[List[Temporal.Versioned[A]]]

  /** Versions fully within time range. Returns only versions that started AND ended within the
    * period.
    *
    * Uses `FOR SYSTEM_TIME CONTAINED IN` which includes rows where:
    *   - `ValidFrom >= from` AND `ValidTo <= to`
    *
    * Useful for finding versions that were created and superseded within a specific window.
    *
    * ==Example==
    * {{{
    * val start = Instant.parse("2024-01-01T00:00:00Z")
    * val end = Instant.parse("2024-12-31T23:59:59Z")
    * val yearVersions: IO[List[Temporal.Versioned[Employee]]] =
    *   repo.containedIn(employeeId, start, end)
    * }}}
    *
    * @param id entity primary key
    * @param from start of time range
    * @param to end of time range
    * @return versions that started and ended within the range
    */
  def containedIn(id: ID, from: Instant, to: Instant): F[List[Temporal.Versioned[A]]]

  // === Comparison and analysis ===

  /** Compare entity at two instants. Returns versions at both time points for diff analysis.
    *
    * Retrieves the entity state at two different points in time, useful for comparing changes or
    * calculating deltas. Returns `None` if the entity didn't exist at either instant.
    *
    * ==Example==
    * {{{
    * val lastYear = Instant.now.minusSeconds(365 * 86400)
    * val now = Instant.now
    * val comparison: IO[Option[(Temporal.Versioned[Employee], Temporal.Versioned[Employee])]] =
    *   repo.diff(employeeId, lastYear, now)
    *
    * comparison.map {
    *   case Some((before, after)) =>
    *     val salaryChange = after.entity.salary - before.entity.salary
    *     println(s"Salary changed by: $salaryChange")
    *   case None =>
    *     println("Entity not found at one or both time points")
    * }
    * }}}
    *
    * @param id entity primary key
    * @param instant1 first point in time
    * @param instant2 second point in time
    * @return tuple of (version at instant1, version at instant2), or None if not found at both
    *   times
    */
  def diff(id: ID, instant1: Instant, instant2: Instant): F[Option[(Temporal.Versioned[A], Temporal.Versioned[A])]]

  // === Modification ===

  /** Rollback entity to historical state. Inserts/updates the current row to match a past version.
    *
    * Retrieves the entity state at the specified instant and writes it as the current version,
    * effectively performing a time-travel rollback. Returns the number of rows affected (0 or 1).
    *
    * ==Example==
    * {{{
    * val yesterday = Instant.now.minusSeconds(86400)
    * val restored: IO[Int] = repo.restoreTo(employeeId, yesterday)
    * }}}
    *
    * @param id entity primary key
    * @param instant point in time to restore from
    * @return number of rows affected (0 if entity didn't exist at that instant, 1 if restored)
    */
  def restoreTo(id: ID, instant: Instant): F[Int]

  /** Current state without temporal clause. Returns the entity from the current table.
    *
    * Queries the current table directly without any `FOR SYSTEM_TIME` clause. This is a standard
    * query that returns the current version only.
    *
    * ==Example==
    * {{{
    * val current: IO[Option[Employee]] = repo.current(employeeId)
    * }}}
    *
    * @param id entity primary key
    * @return current entity, or None if not found
    */
  def current(id: ID): F[Option[A]]
end TemporalRepo

/** Companion for [[TemporalRepo]] providing automatic derivation. */
object TemporalRepo:
  /** Derive a `TemporalRepo[F, ID, A]` from a `TemporalSchema[ID, A]`.
    *
    * Creates a repository instance with all query methods implemented inline. Requires:
    *   - `TemporalSchema[ID, A]` — table metadata
    *   - `Read[A]` and `Write[A]` — entity serialization
    *   - `Put[ID]` — primary key parameter encoding
    *   - `Transactor[F]` — connection management
    *   - `MonadCancelThrow[F]` — effect capabilities
    *
    * All implementations are generated inline at the call site for zero abstraction overhead.
    *
    * ==Example==
    * {{{
    * given TemporalSchema[Identifier, Employee] = TemporalSchema(...)
    * given Transactor[IO] = ???
    *
    * val repo: TemporalRepo[IO, Identifier, Employee] =
    *   TemporalRepo.derived[IO, Identifier, Employee]
    * }}}
    *
    * @tparam F effect type
    * @tparam ID primary key type
    * @tparam A entity type
    * @param ts temporal schema for entity `A` with key type `ID`
    * @param r read instance for entity `A`
    * @param w write instance for entity `A`
    * @param p put instance for primary key `ID`
    * @param xa transactor for connection management
    * @param F monad instance for effect sequencing
    * @return a fully functional temporal repository
    */
  inline def derived[F[_], ID, A](using
    ts: TemporalSchema[ID, A],
    r: Read[A],
    w: Write[A],
    p: Put[ID],
    xa: Transactor[F],
    F: cats.effect.kernel.MonadCancelThrow[F]
  ): TemporalRepo[F, ID, A] =
    new TemporalRepoImpl[F, ID, A](ts, xa)

  class TemporalRepoImpl[F[_], ID, A] @publicInBinary private[mssql] (
    val schema: TemporalSchema[ID, A],
    xa: Transactor[F]
  )(using
    @unused A: Read[A],
    @unused B: Write[A],
    @unused P: Put[ID],
    @unused F: cats.effect.kernel.MonadCancelThrow[F])
      extends TemporalRepo[F, ID, A]:

    def asOf(id: ID, instant: Instant): F[Option[Temporal.Versioned[A]]] =
      (fr"SELECT" ++ schema.allColumns ++ fr"FROM" ++ schema.forSystemTime(SystemTime.asOf(instant)) ++
        Fragment.const(s"WHERE ${schema.idColumn} = ") ++ fr"$id")
        .query[Temporal.Versioned[A]]
        .option
        .transact(xa)

    def allAsOf(instant: Instant): F[List[Temporal.Versioned[A]]] =
      (fr"SELECT" ++ schema.allColumns ++ fr"FROM" ++ schema.forSystemTime(SystemTime.asOf(instant)))
        .query[Temporal.Versioned[A]]
        .to[List]
        .transact(xa)

    def history(id: ID): F[List[Temporal.Versioned[A]]] =
      (fr"SELECT" ++ schema.allColumns ++ fr"FROM" ++ schema.forSystemTime(SystemTime.All) ++
        Fragment.const(s"WHERE ${schema.idColumn} = ") ++ fr"$id" ++
        Fragment.const(s"ORDER BY ${schema.validFromColumn} ASC"))
        .query[Temporal.Versioned[A]]
        .to[List]
        .transact(xa)

    def historyBetween(id: ID, from: Instant, to: Instant): F[List[Temporal.Versioned[A]]] =
      (fr"SELECT" ++ schema.allColumns ++ fr"FROM" ++ schema.forSystemTime(SystemTime.fromTo(from, to)) ++
        Fragment.const(s"WHERE ${schema.idColumn} = ") ++ fr"$id" ++
        Fragment.const(s"ORDER BY ${schema.validFromColumn} ASC"))
        .query[Temporal.Versioned[A]]
        .to[List]
        .transact(xa)

    def containedIn(id: ID, from: Instant, to: Instant): F[List[Temporal.Versioned[A]]] =
      (fr"SELECT" ++ schema.allColumns ++ fr"FROM" ++ schema.forSystemTime(SystemTime.containedIn(from, to)) ++
        Fragment.const(s"WHERE ${schema.idColumn} = ") ++ fr"$id" ++
        Fragment.const(s"ORDER BY ${schema.validFromColumn} ASC"))
        .query[Temporal.Versioned[A]]
        .to[List]
        .transact(xa)

    def diff(id: ID, instant1: Instant, instant2: Instant): F[Option[(Temporal.Versioned[A], Temporal.Versioned[A])]] =
      (asOf(id, instant1), asOf(id, instant2)).flatMapN((v1, v2) => F.pure((v1, v2).mapN((_, _))))

    def restoreTo(id: ID, instant: Instant): F[Int] =
      // Extract validated column names from schema - propagate error as value
      schema.columnNames match
        case Left(error) =>
          // Lift validation error into F
          F.raiseError(error)
        case Right(columnNames) =>
          asOf(id, instant).flatMap {
            case Some(historical) =>
              // Generate SET clause: "col1 = ?, col2 = ?, ..."
              val setClause = columnNames.map(col => s"$col = ?").mkString(", ")

              // Use Write[A].toFragment to properly bind entity values
              val entityFragment = summon[Write[A]].toFragment(historical.entity, setClause)

              // Construct complete UPDATE statement
              val updateQuery = Fragment.const(s"UPDATE ${schema.tableName} SET ") ++
                entityFragment ++
                Fragment.const(s" WHERE ${schema.idColumn} = ") ++ fr"$id"

              updateQuery.update.run.transact(xa)
            case None => F.pure(0)
          }

    def current(id: ID): F[Option[A]] =
      (fr"SELECT" ++ schema.columns ++ Fragment.const(
        s" FROM ${schema.tableName} WHERE ${schema.idColumn} = ") ++ fr"$id")
        .query[A]
        .option
        .transact(xa)
  end TemporalRepoImpl
end TemporalRepo
