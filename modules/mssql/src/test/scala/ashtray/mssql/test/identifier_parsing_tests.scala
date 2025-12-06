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

import ashtray.mssql.Identifier
import ashtray.mssql.IdentifierError
import ashtray.mssql.Version

import munit.FunSuite

class IdentifierParsingTests extends FunSuite:

  private val canonical = "00112233-4455-6677-8899-aabbccddeeff"

  test("parse accepts canonical UUID string") {
    val parsed = Identifier.parse(canonical)
    assert(parsed.isRight)
    assertEquals(parsed.toOption.map(Identifier.render).getOrElse(""), canonical)
  }

  test("parse accepts upper and mixed case input") {
    val parsed = Identifier.parse(canonical.toUpperCase)
    assert(parsed.isRight)
    assertEquals(parsed.toOption.map(Identifier.render), Some(canonical))
  }

  test("parse rejects invalid length") {
    val parsed = Identifier.parse("1234")
    assertEquals(parsed, Left(IdentifierError.InvalidLength(4, 36)))
  }

  test("parse rejects invalid character") {
    val parsed = Identifier.parse("00112233-4455-6677-8899-aabbccddeezz")
    parsed match
      case Left(IdentifierError.InvalidCharacter('z', 34, _)) => ()
      case other                                              => fail(s"Unexpected: $other")
  }

  test("parse rejects misplaced hyphens") {
    val parsed = Identifier.parse("00112233-44556677-8899-aabb-ccddeeff")
    parsed match
      case Left(IdentifierError.InvalidFormat(_, _)) => ()
      case other                                     => fail(s"Unexpected: $other")
  }

  test("parse rejects surrounding whitespace") {
    val parsed = Identifier.parse(s"  $canonical  ")
    parsed match
      case Left(IdentifierError.InvalidLength(len, 36)) => assertEquals(len, canonical.length + 4)
      case other                                        => fail(s"Unexpected: $other")
  }

  test("fromBytes rejects wrong length") {
    val parsed = Identifier.fromBytes(Array.fill(15)(0.toByte))
    assertEquals(parsed, Left(IdentifierError.InvalidByteArrayLength(15, 16)))
  }

  test("fromSqlServerBytes rejects wrong length") {
    val parsed = Identifier.fromSqlServerBytes(Array.fill(15)(0.toByte))
    assertEquals(parsed, Left(IdentifierError.InvalidByteArrayLength(15, 16)))
  }

  test("unknown version nibble parses to Version.Unknown") {
    val parsed = Identifier.parse("00112233-4455-8677-8899-aabbccddeeff").toOption.get
    assertEquals(parsed.version, Version.Unknown(8))
  }
end IdentifierParsingTests
