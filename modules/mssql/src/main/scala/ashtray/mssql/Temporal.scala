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

import doobie.Read
import doobie.Write

/** Wrapper adding period columns to any entity type `A`.
  *
  * `Temporal[A, M]` combines a base entity of type `A` with period columns representing the
  * validity window of that entity version. The phantom type parameter `M <: TemporalMode` encodes
  * whether the entity is system-versioned or not, enabling mode-specific operations to be available
  * only when appropriate.
  *
  * ==Architecture==
  * {{{
  *   Entity A
  *       │
  *       ├─► Temporal[A, Standard]         // Non-temporal entity with period
  *       │                                  // (for future extensibility)
  *       │
  *       └─► Temporal[A, SystemVersioned]  // System-versioned temporal entity
  *                                          // (supports temporal queries)
  * }}}
  *
  * ==Usage with doobie==
  * `Temporal` provides automatic `Read` and `Write` derivation when the underlying entity type has
  * these instances. The period columns are read/written alongside the entity columns:
  *
  * {{{
  * import ashtray.mssql.*
  * import doobie.implicits.*
  *
  * case class Employee(id: Long, name: String, salary: BigDecimal)
  *
  * // Automatic derivation
  * val query: Query0[Temporal.Versioned[Employee]] =
  *   sql"SELECT * FROM Employee FOR SYSTEM_TIME AS OF $instant"
  *     .query[Temporal.Versioned[Employee]]
  *
  * // Extract entity
  * query.to[List].map(_.map(_.entity))
  * }}}
  *
  * ==Type safety==
  * The phantom type parameter `M` ensures that temporal-specific operations (like `isCurrent`,
  * `current`) are only available on `Temporal.Versioned[A]` and not on standard (non-temporal)
  * entities.
  *
  * @tparam A base entity type
  * @tparam M TemporalMode — `Standard` or `SystemVersioned`
  * @param entity the underlying entity value
  * @param period the validity period for this entity version
  * @see [[Temporal.Versioned]] for system-versioned entities
  * @see [[Temporal.Current]] for standard entities
  */
final case class Temporal[A, M <: TemporalMode](
  entity: A,
  period: Period.DateTime2
)

/** Companion for [[Temporal]] providing type aliases, derivation, and extensions. */
object Temporal:
  /** Alias for system-versioned temporal entities.
    *
    * Use this type when working with SQL Server temporal tables that have `SYSTEM_VERSIONING = ON`.
    * Provides access to temporal-specific extension methods like `isCurrent`.
    *
    * @tparam A the base entity type
    */
  type Versioned[A] = Temporal[A, TemporalMode.SystemVersioned]

  /** Alias for standard (non-temporal) entities with period columns.
    *
    * This type exists for symmetry and future extensibility but is not commonly used since standard
    * entities typically don't carry period columns.
    *
    * @tparam A the base entity type
    */
  type Current[A] = Temporal[A, TemporalMode.Standard]

  /** Derive `Read` instance for `Temporal[A, M]` from `Read[A]`.
    *
    * The instance reads the entity columns followed by the two period columns (`ValidFrom`,
    * `ValidTo`). This ordering matches the typical structure of temporal table queries.
    *
    * ==Column ordering==
    * ```
    * SELECT EntityCol1, EntityCol2, ..., ValidFrom, ValidTo
    *        ^^^^^^^^^^^^^^^^^^^^^^^^^^^^  ^^^^^^^^^^^^^^^^^^
    *        Read[A]                       Period columns
    * ```
    *
    * `inline given` ensures this derivation is specialized per entity type at compile time,
    * avoiding megamorphic dispatch overhead.
    *
    * @tparam A entity type with a `Read` instance
    * @tparam M temporal mode
    */
  inline given [A: Read, M <: TemporalMode]: Read[Temporal[A, M]] =
    Read[(A, LocalDateTime, LocalDateTime)].map { case (a, validFrom, validTo) =>
      Temporal(a, Period(validFrom, validTo))
    }

  /** Derive `Write` instance for `Temporal[A, M]` from `Write[A]`.
    *
    * The instance writes the entity columns followed by the two period columns. This is useful for
    * scenarios like history table bulk inserts or data migrations.
    *
    * ==Note==
    * Direct writes to temporal table period columns are typically blocked by SQL Server when
    * `SYSTEM_VERSIONING = ON`. This instance is primarily useful for:
    *   - Writing to staging tables before enabling temporal versioning
    *   - History table data migrations
    *   - Testing and data seeding
    *
    * `inline given` ensures specialization per entity type.
    *
    * @tparam A entity type with a `Write` instance
    * @tparam M temporal mode
    */
  inline given [A: Write, M <: TemporalMode]: Write[Temporal[A, M]] =
    Write[(A, LocalDateTime, LocalDateTime)].contramap { t =>
      (t.entity, t.period.validFrom, t.period.validTo)
    }

  // === Extension methods gated on SystemVersioned mode ===

  extension [A](t: Temporal.Versioned[A])
    /** True if this is the current (non-historical) version.
      *
      * Current rows in SQL Server temporal tables have `ValidTo` set to the maximum `DATETIME2`
      * value: `9999-12-31 23:59:59.9999999`. This method checks if the year component is 9999 as a
      * fast discriminator.
      *
      * Inlined for hot path optimization — the check expands directly at the call site with zero
      * method call overhead.
      *
      * ==Usage==
      * {{{
      * val temporal: Temporal.Versioned[Employee] = ???
      * if temporal.isCurrent then
      *   println("This is the current version")
      * else
      *   println("This is a historical version")
      * }}}
      */
    inline def isCurrent: Boolean =
      t.period.validTo.getYear == 9999

    /** Extract just the entity, discarding period information.
      *
      * This is a convenience method for scenarios where you need only the entity data without the
      * temporal metadata. Inlined to avoid method call overhead.
      *
      * ==Usage==
      * {{{
      * val temporal: Temporal.Versioned[Employee] = ???
      * val employee: Employee = temporal.current
      * }}}
      *
      * ==Note==
      * Despite the name "current", this method works on both current and historical versions. Use
      * `isCurrent` to distinguish between them.
      */
    inline def current: A = t.entity
  end extension
end Temporal
