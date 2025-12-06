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
package ashtray.mssql.test

import ashtray.mssql.*

import munit.FunSuite

/** Tests demonstrating [[IdentifierOps]] usage for reducing opaque type boilerplate.
  *
  * Shows how to selectively export functionality groups into domain-specific opaque type wrappers.
  */
class IdentifierOpsTests extends FunSuite:

  // === Example 1: Minimal wrapper with just type classes ===

  opaque type UserId = Identifier

  object UserId:
    object ops extends IdentifierOps[UserId]
    export ops.given // Only type class instances

    def apply(id: Identifier): UserId = id

  test("Minimal wrapper exports type classes correctly"):
    import cats.syntax.show.*
    import cats.syntax.order.*

    val id1 = UserId(Identifier.parseUnsafe("550e8400-e29b-41d4-a716-446655440000"))
    val id2 = UserId(Identifier.parseUnsafe("550e8400-e29b-41d4-a716-446655440001"))

    // Show works
    assertEquals(id1.show, "550e8400-e29b-41d4-a716-446655440000")

    // Order works
    assert(id1 < id2)

    // Equality works
    assertEquals(id1, id1)
    assertNotEquals(id1, id2)

  // === Example 2: Full-featured wrapper ===

  opaque type ProductId = Identifier

  object ProductId:
    object ops extends IdentifierOps[ProductId]

    // Export everything
    export ops.*

    def apply(id: Identifier): ProductId = id

  test("Full wrapper has all operations available"):
    import cats.syntax.show.*

    val uuid = java.util.UUID.randomUUID()
    val id = ProductId(Identifier.fromJava(uuid))

    // Conversions work
    assertEquals(id.toJava, uuid)
    assert(id.toBytes.length == 16)
    assert(id.toSqlServerBytes.length == 16)

    // Version operations work
    assertEquals(id.version, Version.V4)
    assert(id.variant == 2)
    assert(id.asV4.isDefined)
    assert(id.asV7.isEmpty)

    // Type classes work
    assertEquals(id.show, Identifier.render(Identifier.fromJava(uuid)))

  // === Example 3: V7-specific wrapper ===

  opaque type EventId = Identifier.Versioned[Version.V7.type]

  object EventId:
    object ops extends IdentifierV7Ops[EventId]

    export ops.given
    export ops.{toJava, toBytes, timestampMillis, instant}

    def apply(id: Identifier.Versioned[Version.V7.type]): EventId = id

    def generate()(using gen: IdentifierGen[cats.effect.IO, Version.V7.type]): cats.effect.IO[EventId] =
      gen.generate.map(apply)

  test("V7 wrapper has timestamp operations"):
    import cats.effect.unsafe.implicits.global
    import java.time.Instant

    val now = Instant.now()
    val id = generate[cats.effect.IO, Version.V7.type].unsafeRunSync()
    val eventId = EventId(id)

    // V7-specific operations work
    val timestamp = eventId.timestampMillis
    val instant = eventId.instant

    assert(timestamp > 0)
    assert(instant.toEpochMilli == timestamp)
    assert(Math.abs(instant.toEpochMilli - now.toEpochMilli) < 1000) // Within 1 second

  // === Example 4: Selective exports ===

  opaque type CustomerId = Identifier

  object CustomerId:
    object ops extends IdentifierOps[CustomerId]

    // Cherry-pick specific operations
    export ops.given // All type class instances
    export ops.{toJava, version}

    // Not exported: toBytes, toSqlServerBytes, variant, asV1, asV4, asV7, mostSignificant, leastSignificant

    def apply(id: Identifier): CustomerId = id

  test("Selective exports limit API surface"):
    import cats.syntax.order.*

    val id1 = CustomerId(Identifier.parseUnsafe("550e8400-e29b-41d4-a716-446655440000"))
    val id2 = CustomerId(Identifier.parseUnsafe("550e8400-e29b-41d4-a716-446655440001"))

    // Exported operations work
    assert(id1 < id2)
    assertEquals(id1, id1)
    assert(id1.toJava.toString == "550e8400-e29b-41d4-a716-446655440000")
    assertEquals(id1.version, Version.V4)

    // Show is NOT exported - this would fail to compile:
    // import cats.syntax.show.*
    // id1.show // Error: value show is not a member of CustomerId

  test("Non-exported operations are not available"):
    val id = CustomerId(Identifier.parseUnsafe("550e8400-e29b-41d4-a716-446655440000"))

    // These would fail to compile if uncommented:
    // id.toBytes // Not exported
    // id.toSqlServerBytes // Not exported
    // id.variant // Not exported
    // id.asV4 // Not exported

    // Verify that only exported methods work (version and toJava were exported)
    assertEquals(id.version, Version.V4)
    assert(id.toJava.toString.nonEmpty)

  // === Example 5: Multiple wrappers with different capabilities ===

  opaque type InternalId = Identifier
  opaque type ExternalId = Identifier

  object InternalId:
    object ops extends IdentifierOps[InternalId]
    export ops.*

    def apply(id: Identifier): InternalId = id

  object ExternalId:
    object ops extends IdentifierOps[ExternalId]
    // Minimal surface - just equality and rendering
    export ops.given

    def apply(id: Identifier): ExternalId = id

  test("Different wrappers can have different capabilities"):
    import cats.syntax.show.*

    val internal = InternalId(Identifier.parseUnsafe("550e8400-e29b-41d4-a716-446655440000"))
    val external = ExternalId(Identifier.parseUnsafe("550e8400-e29b-41d4-a716-446655440001"))

    // InternalId has full capabilities
    assert(internal.toBytes.length == 16)
    assert(internal.toJava.toString.nonEmpty)
    assertEquals(internal.version, Version.V4)

    // ExternalId has minimal capabilities
    assertEquals(external.show, "550e8400-e29b-41d4-a716-446655440001")
    assertEquals(external, external)

    // ExternalId doesn't have conversions - would fail to compile:
    // external.toBytes // Error
    // external.toJava // Error
    // external.version // Error

end IdentifierOpsTests
