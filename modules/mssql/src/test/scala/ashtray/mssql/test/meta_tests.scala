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

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID

import cats.effect.unsafe.implicits.global

import doobie.*
import doobie.implicits.*

import ashtray.mssql.Identifier
import ashtray.mssql.IdentifierV4
import ashtray.mssql.IdentifierV7
import ashtray.mssql.Version
import ashtray.mssql.VersionV4
import ashtray.mssql.VersionV7
import ashtray.mssql.time.formatter
import ashtray.test.MSSQLContainerSuite

import com.dimafeng.testcontainers.MSSQLServerContainer

class MetaTests extends MSSQLContainerSuite:

  final val uuidString = "F228E288-C5D0-EE11-B29C-AC198E6E1C53"
  val uuid = UUID.fromString(uuidString)
  final val offsetDateTimeString = "2024-02-21 16:30:07.2019148 +06:00"
  val offsetDateTime = OffsetDateTime.parse(offsetDateTimeString, formatter.datetimeoffset)
  final val localDateTimeString = "2024-02-21 16:30:07.2019148"
  val localDateTime = LocalDateTime.parse(localDateTimeString, formatter.dateTime2)

  override def afterContainersStart(container: Containers): Unit =
    super.afterContainersStart(container)
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
          CREATE TABLE [dbo].[meta_test_identifier](
            value UNIQUEIDENTIFIER NOT NULL
          );
          CREATE TABLE [dbo].[meta_test_identifier_v7](
            value UNIQUEIDENTIFIER NOT NULL
          );
          CREATE TABLE [dbo].[meta_test_identifier_v4](
            value UNIQUEIDENTIFIER NOT NULL
          );
          CREATE TABLE [dbo].[meta_test_identifier_predicate](
            value UNIQUEIDENTIFIER NOT NULL
          );
          CREATE TABLE [dbo].[meta_test_identifier_nullable](
            value UNIQUEIDENTIFIER NULL,
            dt DATETIME2 NULL,
            dto DATETIMEOFFSET NULL
          );
         """.update.run.map(_ => ()).transact(transactor(container)).unsafeRunSync()
  end afterContainersStart

  override def beforeContainersStop(container: Containers): Unit =
    sql"""
          DROP TABLE [dbo].[meta_test_local_date_time];
          DROP TABLE [dbo].[meta_test_offset_date_time];
              DROP TABLE [dbo].[meta_test_identifier_v7];
              DROP TABLE [dbo].[meta_test_identifier_v4];
              DROP TABLE [dbo].[meta_test_identifier_predicate];
              DROP TABLE [dbo].[meta_test_identifier_nullable];
              DROP TABLE [dbo].[meta_test_identifier];
              DROP TABLE [dbo].[meta_test_uuid];
          """.update.run.map(_ => ()).transact(transactor(container)).unsafeRunSync()
    super.beforeContainersStop(container)

  test("Encode and decode between java UUID instances and SQL Server UNIQUEIDENTIFIER records.") {
    import ashtray.mssql.given
    withContainers { container =>
      assertMeta(
        sql"INSERT INTO [dbo].[meta_test_uuid] (value) OUTPUT (inserted.value) VALUES ($uuid)".query[UUID],
        uuid,
        container
      )
    }
  }

  test("Encode and decode between java LocalDateTime instances and SQL Server DATETIME2 records.") {
    import ashtray.mssql.given
    withContainers { container =>
      assertMeta(
        sql"INSERT INTO [dbo].[meta_test_local_date_time] (value) OUTPUT (inserted.value) VALUES ($localDateTime)"
          .query[LocalDateTime],
        localDateTime,
        container
      )
    }
  }

  test("Encode and decode between java OffsetDateTime instances and SQL Server DATETIMEOFFSET records.") {
    import ashtray.mssql.given
    withContainers { container =>
      assertMeta(
        sql"INSERT INTO [dbo].[meta_test_offset_date_time] (value) OUTPUT (inserted.value) VALUES ($offsetDateTime)"
          .query[OffsetDateTime],
        offsetDateTime,
        container
      )
    }
  }

  test("Encode and decode Identifier via UNIQUEIDENTIFIER round trip") {
    import ashtray.mssql.given
    val id = Identifier.parseUnsafe(uuidString.toLowerCase)
    withContainers { container =>
      assertMeta(
        sql"INSERT INTO [dbo].[meta_test_identifier] (value) OUTPUT (inserted.value) VALUES ($id)".query[Identifier],
        id,
        container
      )
    }
  }

  test("Encode and decode typed V7 Identifier via UNIQUEIDENTIFIER round trip") {
    import ashtray.mssql.given
    val id: IdentifierV7 = Identifier.Versioned.unsafeWrap[VersionV7](Identifier.parseUnsafe(uuidString.toLowerCase))
    withContainers { container =>
      assertMeta(
        sql"INSERT INTO [dbo].[meta_test_identifier_v7] (value) OUTPUT (inserted.value) VALUES ($id)"
          .query[IdentifierV7],
        id,
        container
      )
    }
  }

  test("Encode and decode typed V4 Identifier via UNIQUEIDENTIFIER round trip") {
    import ashtray.mssql.given
    val id: IdentifierV4 =
      Identifier.Versioned.unsafeWrap[VersionV4](Identifier.parseUnsafe("00112233-4455-4677-8899-aabbccddeeff"))
    withContainers { container =>
      assertMeta(
        sql"INSERT INTO [dbo].[meta_test_identifier_v4] (value) OUTPUT (inserted.value) VALUES ($id)"
          .query[IdentifierV4],
        id,
        container
      )
    }
  }

  test("UNIQUEIDENTIFIER bytes match SQL Server mixed-endian storage") {
    import ashtray.mssql.given
    val id = Identifier.parseUnsafe("00112233-4455-6677-8899-aabbccddeeff")
    withContainers { container =>
      val xa = transactor(container)
      sql"DELETE FROM [dbo].[meta_test_identifier]".update.run.transact(xa).map(_ => ()).unsafeRunSync()

      val roundTrip =
        sql"INSERT INTO [dbo].[meta_test_identifier] (value) OUTPUT (inserted.value) VALUES ($id)"
          .query[Identifier]
          .unique
          .transact(xa)
          .unsafeRunSync()
      assertEquals(roundTrip, id)

      val storedBytes =
        sql"SELECT CONVERT(binary(16), value) FROM [dbo].[meta_test_identifier]"
          .query[Array[Byte]]
          .unique
          .transact(xa)
          .unsafeRunSync()
      assertEquals(storedBytes.toSeq, id.toSqlServerBytes.toSeq)
      assertEquals(Identifier.fromSqlServerBytes(storedBytes).map(Identifier.render), Right(Identifier.render(id)))
    }
  }

  test("Identifier can be used in WHERE predicates") {
    import ashtray.mssql.given
    val id = Identifier.parseUnsafe("f228e288-c5d0-ee11-b29c-ac198e6e1c53")
    withContainers { container =>
      val xa = transactor(container)
      sql"DELETE FROM [dbo].[meta_test_identifier_predicate]".update.run.transact(xa).map(_ => ()).unsafeRunSync()

      sql"INSERT INTO [dbo].[meta_test_identifier_predicate] (value) VALUES ($id)".update.run
        .transact(xa)
        .map(_ => ())
        .unsafeRunSync()
      val count = sql"SELECT COUNT(*) FROM [dbo].[meta_test_identifier_predicate] WHERE value = $id"
        .query[Int]
        .unique
        .transact(xa)
        .unsafeRunSync()
      assertEquals(count, 1)
    }
  }

  test("Nullable Identifier and datetime Meta handle NULL values") {
    import ashtray.mssql.given
    withContainers { container =>
      val xa = transactor(container)
      sql"DELETE FROM [dbo].[meta_test_identifier_nullable]".update.run.transact(xa).map(_ => ()).unsafeRunSync()

      val result =
        sql"INSERT INTO [dbo].[meta_test_identifier_nullable] (value, dt, dto) OUTPUT inserted.value, inserted.dt, inserted.dto VALUES (NULL, NULL, NULL)"
          .query[(Option[Identifier], Option[LocalDateTime], Option[OffsetDateTime])]
          .unique
          .transact(xa)
          .unsafeRunSync()
      assertEquals(result, (None, None, None))
    }
  }

  test("Typed and untyped literals survive database round trips") {
    import ashtray.mssql.given
    import ashtray.mssql.{id, idv}
    val untyped = id"00112233-4455-6677-8899-aabbccddeeff"
    val typedV7: IdentifierV7 =
      Identifier.Versioned.unsafeWrap[VersionV7](idv"019012f3-a456-7def-8901-234567890abc".untyped)

    withContainers { container =>
      val xa = transactor(container)
      val roundUntyped =
        sql"INSERT INTO [dbo].[meta_test_identifier] (value) OUTPUT (inserted.value) VALUES ($untyped)"
          .query[Identifier]
          .unique
          .transact(xa)
          .unsafeRunSync()
      assertEquals(roundUntyped, untyped)

      val roundTyped =
        sql"INSERT INTO [dbo].[meta_test_identifier_v7] (value) OUTPUT (inserted.value) VALUES ($typedV7)"
          .query[IdentifierV7]
          .unique
          .transact(xa)
          .unsafeRunSync()
      assertEquals(roundTyped, typedV7)
    }
  }

  def assertMeta[A](q: Query0[A], value: A, container: MSSQLServerContainer): Unit =
    assertEquals(q.nel.transact(transactor(container)).unsafeRunSync().head, value)
end MetaTests
