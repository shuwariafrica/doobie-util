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
package africa.shuwari.doobie.prelude.test

import cats.effect.unsafe.implicits.global
import doobie.*
import doobie.implicits.*
import zio.prelude.Newtype

import africa.shuwari.doobie.prelude.newTypeMeta
import africa.shuwari.doobie.test.database

class NewtypeTests extends munit.FunSuite:

  override def beforeAll(): Unit =
    sql"""CREATE TABLE [dbo].[newtype_test](
            value VARCHAR(4) NOT NULL
          );
         """.update.run.map(_ => ()).transact(database.defaultTransactor).unsafeRunSync()

  override def afterAll(): Unit =
    sql"""DROP TABLE [dbo].[newtype_test];""".update.run
      .map(_ => ())
      .transact(database.defaultTransactor)
      .unsafeRunSync()

  object NewtypeInstance extends Newtype[String]
  type NewtypeInstance = NewtypeInstance.Type

  given Meta[NewtypeInstance] = newTypeMeta(NewtypeInstance)

  test("Encode and decode between zio.Prelude.Newtype type instances with available Meta instances") {
    val newtype = NewtypeInstance.wrap("test")
    assertEquals(
      sql"INSERT INTO [dbo].[newtype_test] (value) OUTPUT (inserted.value) VALUES ($newtype)"
        .query[NewtypeInstance]
        .nel
        .transact(database.defaultTransactor)
        .unsafeRunSync()
        .head,
      newtype
    )
  }
end NewtypeTests
