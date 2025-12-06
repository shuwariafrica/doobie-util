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
import ashtray.mssql.IdentifierV1
import ashtray.mssql.IdentifierV7
import ashtray.mssql.Version
import ashtray.mssql.VersionV1
import ashtray.mssql.VersionV7

import munit.FunSuite

class VersionTests extends FunSuite:

  test("version detection for V4") {
    val msb = 0x123456789abc4000L // version 4 bits set
    val lsb = 0x8000000000000001L // variant 2, rest random
    val id = Identifier(msb, lsb)
    assertEquals(id.version, Version.V4)
    assert(id.asV4.isDefined)
    assert(id.asV7.isEmpty)
    assertEquals(id.variant, 2)
  }

  test("version detection and timestamp for V7") {
    val timestampMillis = 1700000000000L
    val msb = (timestampMillis << 16) | 0x7000L
    val lsb = 0x8000000000000001L
    val id: IdentifierV7 = Identifier.Versioned.unsafeWrap[VersionV7](Identifier(msb, lsb))
    assertEquals(id.untyped.version, Version.V7)
    assertEquals(id.timestampMillis, timestampMillis)
  }

  test("v1 fields are extracted correctly") {
    val timestamp100ns: Long = 0x1edcba987654L
    val clockSeq: Int = 0x1234
    val node: Long = 0xaabbccddeeffL

    val timeLow = (timestamp100ns & 0xffffffffL) << 32
    val timeMid = (timestamp100ns >>> 32 & 0xffffL) << 16
    val timeHi = (timestamp100ns >>> 48) & 0x0fffL
    val msb = timeLow | timeMid | timeHi | 0x1000L

    val lsb = ((0x8000L | clockSeq.toLong) << 48) | node
    val id: IdentifierV1 = Identifier.Versioned.unsafeWrap[VersionV1](Identifier(msb, lsb))
    assertEquals(id.timestamp100Nanos, timestamp100ns)
    assertEquals(id.clockSequence, clockSeq)
    assertEquals(id.node, node)
  }

  test("unknown version yields Version.Unknown and does not narrow") {
    val msb = 0x0000000000008000L // version nibble 8
    val lsb = 0x8000000000000001L
    val id = Identifier(msb, lsb)
    assertEquals(id.version, Version.Unknown(8))
    assert(id.asV4.isEmpty)
    assert(id.asV7.isEmpty)
  }
end VersionTests
