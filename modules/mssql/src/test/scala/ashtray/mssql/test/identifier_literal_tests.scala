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
import ashtray.mssql.IdentifierV7
import ashtray.mssql.Version
import ashtray.mssql.id
import ashtray.mssql.idv4
import ashtray.mssql.idv7

import munit.FunSuite

class IdentifierLiteralTests extends FunSuite:

  test("id literal constructs identifier at compile time") {
    val literal = id"00112233-4455-6677-8899-aabbccddeeff"
    val parsed = Identifier.parseUnsafe("00112233-4455-6677-8899-aabbccddeeff")
    assertEquals(Identifier.render(literal), Identifier.render(parsed))
  }

  test("idv literal returns typed V7") {
    val v7: IdentifierV7 = idv7"019012f3-a456-7def-8901-234567890abc"
    assertEquals(v7.untyped.version, Version.V7)
    assert(v7.untyped.asV7.isDefined)
  }

  test("idv literal returns typed V4 with correct variant") {
    val v4: IdentifierV4 = idv4"00112233-4455-4677-8899-aabbccddeeff"
    assertEquals(v4.untyped.version, Version.V4)
    assertEquals(v4.untyped.variant, 2)
  }

  test("id literal rejects invalid input at compile time") {
    val errors = compileErrors("""
      import ashtray.mssql.id
      val bad = id"00112233-4455-6677-8899-aabbccddeezz"
    """)
    assert(errors.contains("Invalid character"))
  }

  test("id literal rejects wrong length and hyphen positions at compile time") {
    val errorsLength = compileErrors("""
      import ashtray.mssql.id
      val bad = id"00112233-4455-6677-8899-aabbccddeeff00"
    """)
    assert(errorsLength.contains("Invalid length"))

    val errorsHyphens = compileErrors("""
      import ashtray.mssql.id
      val bad = id"001122334455-6677-8899-aabb-ccddeeff"
    """)
    assert(errorsHyphens.contains("Missing or misplaced hyphens"))
  }

  test("id literal rejects interpolated usage") {
    val errors = compileErrors("""
      import ashtray.mssql.id
      val x = "00112233-4455-6677-8899-aabbccddeeff"
      val bad = id"$x"
    """)
    assert(errors.contains("does not support arguments"))
  }

  test("idv literal rejects unsupported version") {
    val errors = compileErrors("""
      import ashtray.mssql.idv
      val bad = idv"00112233-4455-2677-8899-aabbccddeeff" // version 2
    """)
    assert(errors.contains("unsupported version"))
  }

  test("idv literal rejects non 4/7/1 versions at compile time") {
    val errors = compileErrors("""
      import ashtray.mssql.idv
      val bad = idv"00112233-4455-5677-8899-aabbccddeeff" // version 5
    """)
    assert(errors.contains("unsupported version"))
  }

  test("literals render in canonical lowercase and retain variant bits") {
    val upper = id"F228E288-C5D0-EE11-B29C-AC198E6E1C53"
    assertEquals(Identifier.render(upper), "f228e288-c5d0-ee11-b29c-ac198e6e1c53")
    assertEquals(upper.variant, 2)
  }
end IdentifierLiteralTests
