/*****************************************************************
 * Copyright © Shuwari Africa Ltd. All rights reserved.          *
 *                                                               *
 * Shuwari Africa Ltd. licenses this file to you under the terms *
 * of the Apache License Version 2.0 (the "License"); you may    *
 * not use this file except in compliance with the License. You  *
 * may obtain a copy of the License at:                          *
 *                                                               *
 *     https://www.apache.org/licenses/LICENSE-2.0               *
 *                                                               *
 * Unless required by applicable law or agreed to in writing,    *
 * software distributed under the License is distributed on an   *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,  *
 * either express or implied. See the License for the specific   *
 * language governing permissions and limitations under the      *
 * License.                                                      *
 *****************************************************************/
package africa.shuwari.doobie.mssql.test

import _root_.munit.FunSuite
import cats.*
import cats.effect.*
import cats.effect.unsafe.implicits.global
import doobie.*
import doobie.implicits.*

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID

import africa.shuwari.doobie.mssql.time.formatter
import africa.shuwari.doobie.test.database

class MetaTests extends FunSuite:

  final val uuidString = "F228E288-C5D0-EE11-B29C-AC198E6E1C53"
  val uuid = UUID.fromString(uuidString)
  final val offsetDateTimeString = "2024-02-21 16:30:07.2019148 +06:00"
  val offsetDateTime = OffsetDateTime.parse(offsetDateTimeString, formatter.datetimeoffset)
  final val localDateTimeString = "2024-02-21 16:30:07.2019148"
  val localDateTime = LocalDateTime.parse(localDateTimeString, formatter.dateTime2)

  override def beforeAll(): Unit =
    sql"""
          CREATE TABLE [dbo].[meta_test_local_date_time](
            value DATETIME2 DEFAULT SYSDATETIME() NOT NULL
          );
          CREATE TABLE [dbo].[meta_test_offset_date_time](
            value DATETIMEOFFSET DEFAULT SYSDATETIMEOFFSET() NOT NULL
          );
          CREATE TABLE [dbo].[meta_test_uuid](
            value UNIQUEIDENTIFIER DEFAULT NEWSEQUENTIALID() NOT NULL
          );
         """.update.run.map(_ => ()).transact(database.defaultTransactor).unsafeRunSync()
    ()

  override def afterAll(): Unit =
    println("Teardown meta_test tables.")
    sql"""
          DROP TABLE [dbo].[meta_test_local_date_time];
          DROP TABLE [dbo].[meta_test_offset_date_time];
          DROP TABLE [dbo].[meta_test_uuid];
          """.update.run.map(_ => ()).transact(database.defaultTransactor).unsafeRunSync()

  test("Encode and decode between java UUID instances and SQL Server UNIQUEIDENTIFIER records.") {
    import africa.shuwari.doobie.mssql.given_Meta_UUID
    assertMeta(sql"INSERT INTO [dbo].[meta_test_uuid] (value) OUTPUT (inserted.value) VALUES ($uuid)".query[UUID], uuid)
  }

  test("Encode and decode between java LocalDateTime instances and SQL Server DATETIME2 records.") {
    import africa.shuwari.doobie.mssql.given_Meta_LocalDateTime
    assertMeta(
      sql"INSERT INTO [dbo].[meta_test_local_date_time] (value) OUTPUT (inserted.value) VALUES ($localDateTime)"
        .query[LocalDateTime],
      localDateTime
    )
  }

  test("Encode and decode between java OffsetDateTime instances and SQL Server DATETIMEOFFSET records.") {
    import africa.shuwari.doobie.mssql.given_Meta_OffsetDateTime
    assertMeta(
      sql"INSERT INTO [dbo].[meta_test_offset_date_time] (value) OUTPUT (inserted.value) VALUES ($offsetDateTime)"
        .query[OffsetDateTime],
      offsetDateTime
    )
  }

  def assertMeta[A](q: Query0[A], value: A)(using Meta[A]) =
    assertEquals(q.nel.transact(database.defaultTransactor).unsafeRunSync().head, value)
end MetaTests
