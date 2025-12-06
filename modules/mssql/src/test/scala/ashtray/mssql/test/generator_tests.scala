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

import cats.effect.IO
import cats.effect.std.SecureRandom
import cats.effect.unsafe.implicits.global

import ashtray.mssql.Identifier
import ashtray.mssql.IdentifierError
import ashtray.mssql.IdentifierGen
import ashtray.mssql.IdentifierV4
import ashtray.mssql.IdentifierV7
import ashtray.mssql.Version
import ashtray.mssql.VersionV4
import ashtray.mssql.VersionV7
import ashtray.mssql.generate
import ashtray.mssql.generateBatch

import munit.FunSuite

class GeneratorTests extends FunSuite:

  given SecureRandom[IO] = SecureRandom.javaSecuritySecureRandom[IO](64).unsafeRunSync()

  test("default V7 generator produces RFC variant and version") {
    val id: IdentifierV7 = generate[IO, VersionV7].unsafeRunSync()
    assertEquals(id.untyped.version, Version.V7)
    assertEquals(id.untyped.variant, 2)
  }

  test("generateBatch rejects non-positive counts") {
    val result = generateBatch[IO, VersionV7](0).attempt.unsafeRunSync()
    assertEquals(result, Left(IdentifierError.InvalidBatchCount(0)))
  }

  test("generateBatch rejects negative counts") {
    val result = generateBatch[IO, VersionV7](-1).attempt.unsafeRunSync()
    assertEquals(result, Left(IdentifierError.InvalidBatchCount(-1)))
  }

  test("generateBatch returns requested size of V7 identifiers") {
    val batch = generateBatch[IO, VersionV7](5).unsafeRunSync()
    assertEquals(batch.length, 5)
    assert(batch.forall(_.untyped.version == Version.V7))
  }

  test("generateBatch V7 identifiers narrow to V7 and not V4") {
    val batch = generateBatch[IO, VersionV7](5).unsafeRunSync()
    assert(batch.forall(_.untyped.asV7.isDefined))
    assert(batch.forall(_.untyped.asV4.isEmpty))
  }

  test("V4 generator produces version 4 with correct variant") {
    val id: IdentifierV4 = generate[IO, VersionV4].unsafeRunSync()
    assertEquals(id.untyped.version, Version.V4)
    assertEquals(id.untyped.variant, 2)
  }

  test("V4 batch produces correct version and variant with no duplicates in small sample") {
    val batch = generateBatch[IO, VersionV4](64).unsafeRunSync()
    assert(batch.forall(_.untyped.version == Version.V4))
    assert(batch.forall(_.untyped.variant == 2))
    assertEquals(batch.distinct.length, batch.length)
  }

  test("V7 batch timestamps are non-decreasing") {
    val batch = generateBatch[IO, VersionV7](64).unsafeRunSync()
    val ts = batch.map(_.timestampMillis)
    assert(ts.zip(ts.drop(1)).forall { case (a, b) => b >= a })
  }

  test("V7 batch has no duplicates in small sample") {
    val batch = generateBatch[IO, VersionV7](256).unsafeRunSync()
    assertEquals(batch.distinct.length, batch.length)
  }

  test("V7 batch uniqueness holds for a larger sample") {
    val batch = generateBatch[IO, VersionV7](1024).unsafeRunSync()
    assertEquals(batch.distinct.length, batch.length)
  }

  test("generate propagates IdentifierGen failures") {
    given IdentifierGen[IO, VersionV7] with
      override def generate: IO[IdentifierV7] = IO.raiseError(new RuntimeException("boom"))

    val result = generate[IO, VersionV7].attempt.unsafeRunSync()
    assert(result.isLeft)
  }
end GeneratorTests
