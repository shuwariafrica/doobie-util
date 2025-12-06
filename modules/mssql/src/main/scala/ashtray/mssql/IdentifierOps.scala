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
import java.util.UUID

import cats.Eq
import cats.Hash
import cats.Order
import cats.Show

/** Operations for opaque type wrappers over [[Identifier]].
  *
  * When defining domain-specific opaque types wrapping `Identifier`, users face boilerplate for
  * re-exporting type class instances and extension methods. This abstract class provides a
  * composable API that can be selectively exported to eliminate repetition.
  *
  * ==Problem==
  * Manual delegation required for each wrapper:
  * {{{
  * opaque type UserId = Identifier
  * object UserId:
  *   given CanEqual[UserId, UserId] = CanEqual.derived
  *   given Order[UserId] = Order[Identifier].asInstanceOf[Order[UserId]]
  *   given Show[UserId] = Show[Identifier].asInstanceOf[Show[UserId]]
  *
  *   extension (id: UserId)
  *     def toJava: UUID = id.toJava
  *     def toBytes: Array[Byte] = id.toBytes
  *     // ... 15+ more methods
  * }}}
  *
  * ==Solution==
  * Create an `ops` object by extending `IdentifierOps[T]`, allowing selective export of members:
  *
  * {{{
  * import ashtray.mssql.*
  *
  * opaque type UserId = Identifier
  *
  * object UserId:
  *   // Create the operations object for this opaque type
  *   object ops extends IdentifierOps[UserId]
  *
  *   // Selectively export what you need
  *   export ops.{given, toJava, toBytes, version}
  *
  *   // Or export everything
  *   // export ops.*
  *
  *   // Add domain-specific methods
  *   def apply(id: Identifier): UserId = id
  * }}}
  *
  * ==Available operations==
  *
  * '''Type class instances''' (export with `given`):
  *   - `Eq[T]`, `Hash[T]`, `Order[T]`, `Show[T]`, `CanEqual[T, T]`
  *
  * '''Core conversions''':
  *   - `toJava`, `toBytes`, `toSqlServerBytes`
  *   - `mostSignificant`, `leastSignificant`
  *
  * '''Version operations''':
  *   - `version`, `variant`, `asV1`, `asV4`, `asV7`
  *
  * '''V7 timestamp operations''' (requires `Versioned[V7.type]`):
  *   - Use [[IdentifierV7Ops]] instead
  *
  * '''V1 operations''' (requires `Versioned[V1.type]`):
  *   - Use [[IdentifierV1Ops]] instead
  *
  * @see [[Identifier]] for the underlying opaque type
  * @see [[IdentifierV7Ops]] for V7-specific operations
  * @see [[IdentifierV1Ops]] for V1-specific operations
  * @tparam T the opaque type wrapping `Identifier` or `Identifier.Versioned[V]`
  */
abstract class IdentifierOps[T]:

  // === Type class instances ===

  // scalafix:off DisableSyntax.asInstanceOf
  /** Equality instance derived from [[Identifier]]'s equality. */
  transparent inline given eqInstance: Eq[T] =
    Identifier.given_Eq_Identifier.asInstanceOf[Eq[T]]

  /** Hash instance derived from [[Identifier]]'s hash. */
  transparent inline given hashInstance: Hash[T] =
    Identifier.given_Hash_Identifier.asInstanceOf[Hash[T]]

  /** Ordering instance derived from [[Identifier]]'s ordering. */
  transparent inline given orderInstance: Order[T] =
    Identifier.given_Order_Identifier.asInstanceOf[Order[T]]

  /** Show instance derived from [[Identifier]]'s show. */
  transparent inline given showInstance: Show[T] =
    Identifier.given_Show_Identifier.asInstanceOf[Show[T]]
  // scalafix:on

  /** CanEqual instance for this opaque type. */
  transparent inline given canEqualInstance: CanEqual[T, T] =
    CanEqual.derived

  // === Core conversions ===

  // scalafix:off DisableSyntax.asInstanceOf
  extension (id: T)
    /** Convert to `java.util.UUID` for Java interoperability. */
    transparent inline def toJava: UUID =
      id.asInstanceOf[Identifier].toJava

    /** Encode as 16-byte big-endian array (RFC 9562 standard format). */
    transparent inline def toBytes: Array[Byte] =
      id.asInstanceOf[Identifier].toBytes

    /** Encode as SQL Server mixed-endian bytes for `UNIQUEIDENTIFIER` columns. */
    transparent inline def toSqlServerBytes: Array[Byte] =
      id.asInstanceOf[Identifier].toSqlServerBytes

    /** Most significant 64 bits of the identifier. */
    transparent inline def mostSignificant: Long =
      id.asInstanceOf[Identifier].mostSignificant

    /** Least significant 64 bits of the identifier. */
    transparent inline def leastSignificant: Long =
      id.asInstanceOf[Identifier].leastSignificant
  // scalafix:on

  // === Version operations ===

  // scalafix:off DisableSyntax.asInstanceOf
  extension (id: T)
    /** UUID version (1, 4, 7, etc.) extracted from identifier bits. */
    transparent inline def version: Version =
      id.asInstanceOf[Identifier].version

    /** UUID variant bits (should be 2 for RFC 9562 identifiers). */
    transparent inline def variant: Int =
      id.asInstanceOf[Identifier].variant

    /** Try to narrow this identifier to V1 type if version matches. */
    transparent inline def asV1: Option[Identifier.Versioned[Version.V1.type]] =
      id.asInstanceOf[Identifier].asV1

    /** Try to narrow this identifier to V4 type if version matches. */
    transparent inline def asV4: Option[Identifier.Versioned[Version.V4.type]] =
      id.asInstanceOf[Identifier].asV4

    /** Try to narrow this identifier to V7 type if version matches. */
    transparent inline def asV7: Option[Identifier.Versioned[Version.V7.type]] =
      id.asInstanceOf[Identifier].asV7
  // scalafix:on

end IdentifierOps

/** Operations specific to V7 identifiers.
  *
  * Use when an opaque type is `Identifier.Versioned[Version.V7.type]`:
  * {{{
  * opaque type EventId = Identifier.Versioned[Version.V7.type]
  * object EventId:
  *   object ops extends IdentifierV7Ops[EventId]
  *   export ops.{given, timestampMillis, instant}
  *
  *   def apply(id: Identifier.Versioned[Version.V7.type]): EventId = id
  * }}}
  *
  * @tparam T the opaque type wrapping `Identifier.Versioned[Version.V7.type]`
  */
abstract class IdentifierV7Ops[T] extends IdentifierOps[T]:

  // scalafix:off DisableSyntax.asInstanceOf
  extension (id: T)
    /** Timestamp in milliseconds since Unix epoch encoded in a version 7 identifier. */
    transparent inline def timestampMillis: Long =
      id.asInstanceOf[Identifier.Versioned[Version.V7.type]].timestampMillis

    /** Timestamp as `Instant` for a version 7 identifier. */
    transparent inline def instant: Instant =
      id.asInstanceOf[Identifier.Versioned[Version.V7.type]].instant
  // scalafix:on

/** Operations specific to V1 identifiers.
  *
  * Use when an opaque type is `Identifier.Versioned[Version.V1.type]`:
  * {{{
  * opaque type LegacyId = Identifier.Versioned[Version.V1.type]
  * object LegacyId:
  *   object ops extends IdentifierV1Ops[LegacyId]
  *   export ops.{given, timestamp, clockSequence, node}
  *
  *   def apply(id: Identifier.Versioned[Version.V1.type]): LegacyId = id
  * }}}
  *
  * @tparam T the opaque type wrapping `Identifier.Versioned[Version.V1.type]`
  */
abstract class IdentifierV1Ops[T] extends IdentifierOps[T]:

  // scalafix:off DisableSyntax.asInstanceOf
  extension (id: T)
    /** Timestamp for a version 1 identifier (100-nanosecond intervals since 1582-10-15). */
    transparent inline def timestamp: Long =
      id.asInstanceOf[Identifier.Versioned[Version.V1.type]].timestamp

    /** Clock sequence component of a version 1 identifier. */
    transparent inline def clockSequence: Int =
      id.asInstanceOf[Identifier.Versioned[Version.V1.type]].clockSequence

    /** Node component of a version 1 identifier. */
    transparent inline def node: Long =
      id.asInstanceOf[Identifier.Versioned[Version.V1.type]].node
  // scalafix:on

end IdentifierV1Ops
