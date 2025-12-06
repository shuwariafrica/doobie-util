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
import ashtray.mssql.IdentifierV4
import ashtray.mssql.Version
import ashtray.mssql.idv4

import munit.FunSuite

class NarrowingRoundtripTests extends FunSuite:

  test("parse then narrow to V7 succeeds when version nibble is 7") {
    val str = "019012f3-a456-7def-8901-234567890abc"
    val parsed = Identifier.parseUnsafe(str)
    assert(parsed.asV7.isDefined)
    assert(parsed.asV4.isEmpty)
  }

  test("parse then narrow to V4 succeeds when version nibble is 4") {
    val str = "00112233-4455-4677-8899-aabbccddeeff"
    val parsed = Identifier.parseUnsafe(str)
    assert(parsed.asV4.isDefined)
    assert(parsed.asV7.isEmpty)
  }

  test("typed literal idv narrows to specific version") {
    val v4: IdentifierV4 = idv4"00112233-4455-4677-8899-aabbccddeeff"
    assertEquals(v4.untyped.version, Version.V4)
    assert(v4.untyped.asV4.isDefined)
  }
