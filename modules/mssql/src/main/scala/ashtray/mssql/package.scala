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
package ashtray

/** Public entrypoint; mixing in [[ashtray.mssql.MetaInstances]] for simple importing.
  *
  * Exposes convenient aliases:
  *   - [[VersionV1]], [[VersionV4]], [[VersionV7]] for version singletons.
  *   - [[IdentifierV1]], [[IdentifierV4]], [[IdentifierV7]] for phantom-typed identifiers.
  *   - Literal helpers [[identifier_literal.idv1]], [[identifier_literal.idv4]],
  *     [[identifier_literal.idv7]] yield narrowed types at compile time.
  *   - [[TemporalVersioned]], [[TemporalCurrent]] for temporal table wrappers.
  *   - Extension methods for `Transactor[F]` providing temporal repository derivation.
  */
package object mssql extends MetaInstances:

  // Version type aliases for convenience
  type VersionV1 = Version.V1.type
  type VersionV4 = Version.V4.type
  type VersionV7 = Version.V7.type

  // Identifier aliases for phantom-typed identifiers
  type IdentifierV1 = Identifier.Versioned[VersionV1]
  type IdentifierV4 = Identifier.Versioned[VersionV4]
  type IdentifierV7 = Identifier.Versioned[VersionV7]

  // Temporal table type aliases for convenience
  type TemporalVersioned[A] = Temporal.Versioned[A]
  type TemporalCurrent[A] = Temporal.Current[A]

  // === Temporal Repository Extensions ===

  /** Extension methods for `Transactor[F]` providing temporal repository derivation.
    *
    * These methods allow users to derive `TemporalRepo[F, ID, A]` instances directly from a
    * `Transactor[F]` using the `temporal[ID, A]` method. This provides the most ergonomic API for
    * temporal table access with a single import: `import ashtray.mssql.*`
    *
    * ==Example==
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
    * val xa: Transactor[IO] = ???
    * val repo = xa.temporal[Identifier, Employee]
    *
    * // Use repository methods directly
    * val history = repo.history(employeeId)
    * }}}
    */
  extension [F[_]](xa: doobie.Transactor[F])
    /** Derive a temporal repository for entity type `A` with primary key type `ID`.
      *
      * Requires:
      *   - `TemporalSchema[ID, A]` — table metadata
      *   - `Read[A]` and `Write[A]` — entity serialization
      *   - `Put[ID]` — primary key parameter encoding
      *   - `MonadCancelThrow[F]` — effect capabilities
      *
      * @tparam ID primary key type (e.g., Long, Identifier)
      * @tparam A entity type (e.g., Employee, Product)
      * @return a fully functional temporal repository
      */
    inline def temporal[ID, A](using
      ts: TemporalSchema[ID, A],
      r: doobie.Read[A],
      w: doobie.Write[A],
      p: doobie.util.Put[ID],
      F: cats.effect.kernel.MonadCancelThrow[F]
    ): TemporalRepo[F, ID, A] =
      TemporalRepo.derived[F, ID, A](using ts, r, w, p, xa, F)
  end extension
end mssql
