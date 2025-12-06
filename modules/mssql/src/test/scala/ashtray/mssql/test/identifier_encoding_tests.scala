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

import munit.FunSuite

class IdentifierEncodingTests extends FunSuite:

  private val canonical = "00112233-4455-6677-8899-aabbccddeeff"
  private val identifier = Identifier.parseUnsafe(canonical)

  test("toBytes and fromBytes roundtrip") {
    val bytes = identifier.toBytes
    val round = Identifier.fromBytes(bytes)
    assertEquals(round.map(Identifier.render), Right(canonical))
  }

  test("toSqlServerBytes uses mixed endian ordering") {
    val bytes = identifier.toSqlServerBytes
    val expected: Array[Byte] = Array(
      0x33,
      0x22,
      0x11,
      0x00,
      0x55,
      0x44,
      0x77,
      0x66,
      0x88.toByte,
      0x99.toByte,
      0xaa.toByte,
      0xbb.toByte,
      0xcc.toByte,
      0xdd.toByte,
      0xee.toByte,
      0xff.toByte
    )
    assertEquals(bytes.toSeq, expected.toSeq)
  }

  test("toSqlServerBytes and fromSqlServerBytes roundtrip") {
    val sqlBytes = identifier.toSqlServerBytes
    val round = Identifier.fromSqlServerBytes(sqlBytes)
    assertEquals(round.map(Identifier.render), Right(canonical))
  }

  test("fromSqlServerBytes rejects big-endian ordering by yielding a different identifier") {
    val bigEndian = identifier.toBytes
    val parsed = Identifier.fromSqlServerBytes(bigEndian)
    assertNotEquals(parsed.map(Identifier.render), Right(canonical))
  }

  test("toJava and fromJava roundtrip") {
    val javaUuid = identifier.toJava
    val round = Identifier.fromJava(javaUuid)
    assertEquals(Identifier.render(round), canonical)
  }
end IdentifierEncodingTests
