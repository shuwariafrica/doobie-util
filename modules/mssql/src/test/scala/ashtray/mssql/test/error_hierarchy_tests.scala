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

import scala.util.control.NoStackTrace

import cats.syntax.all.*

import cats.effect.unsafe.implicits.global

import doobie.*
import doobie.implicits.*

import ashtray.mssql.*
import ashtray.test.MSSQLContainerSuite

import munit.FunSuite

/** Tests for the unified AshtrayError hierarchy and error handling across the library. */
class ErrorHierarchyTests extends FunSuite:

  // === Null input handling ===

  // scalafix:off DisableSyntax.null
  test("Identifier.parse rejects null input with NullInput error") {
    val parsed = Identifier.parse(null)
    assertEquals(parsed, Left(AshtrayError.IdentifierError.NullInput))
  }

  test("Identifier.fromBytes rejects null input with NullInput error") {
    val parsed = Identifier.fromBytes(null)
    assertEquals(parsed, Left(AshtrayError.IdentifierError.NullInput))
  }

  test("Identifier.fromSqlServerBytes rejects null input with NullInput error") {
    val parsed = Identifier.fromSqlServerBytes(null)
    assertEquals(parsed, Left(AshtrayError.IdentifierError.NullInput))
  }
  // scalafix:on DisableSyntax.null

  // === Error hierarchy verification ===

  // scalafix:off DisableSyntax.isInstanceOf
  test("All IdentifierError instances are AshtrayError") {
    assert(AshtrayError.IdentifierError.NullInput.isInstanceOf[AshtrayError])
    assert(AshtrayError.IdentifierError.InvalidLength(1, 36).isInstanceOf[AshtrayError])
    assert(AshtrayError.IdentifierError.InvalidFormat("test", "reason").isInstanceOf[AshtrayError])
    assert(AshtrayError.IdentifierError.InvalidCharacter('x', 0, "test").isInstanceOf[AshtrayError])
    assert(AshtrayError.IdentifierError.InvalidByteArrayLength(15, 16).isInstanceOf[AshtrayError])
    assert(AshtrayError.IdentifierError.InvalidBatchCount(0).isInstanceOf[AshtrayError])
  }

  test("All TemporalSchemaError instances are AshtrayError") {
    assert(AshtrayError.TemporalSchemaError.SqlComment("test").isInstanceOf[AshtrayError])
    assert(AshtrayError.TemporalSchemaError.StringLiteral("test").isInstanceOf[AshtrayError])
    assert(AshtrayError.TemporalSchemaError.EmptyColumnName("test").isInstanceOf[AshtrayError])
    assert(AshtrayError.TemporalSchemaError.ComplexExpression("col", "test").isInstanceOf[AshtrayError])
  }

  test("All MetaError instances are AshtrayError") {
    val idErr = AshtrayError.IdentifierError.NullInput
    assert(AshtrayError.MetaError.IdentifierDecodeFailure(idErr, 1).isInstanceOf[AshtrayError])
    assert(AshtrayError.MetaError.UnexpectedNull(1, "UNIQUEIDENTIFIER").isInstanceOf[AshtrayError])
    assert(AshtrayError.MetaError.JdbcFailure("test", new Exception("test")).isInstanceOf[AshtrayError])
  }

  test("All errors extend Exception with NoStackTrace") {
    val errors: List[AshtrayError] = List(
      AshtrayError.IdentifierError.NullInput,
      AshtrayError.TemporalSchemaError.SqlComment("test"),
      AshtrayError.MetaError.IdentifierDecodeFailure(AshtrayError.IdentifierError.NullInput, 1)
    )
    errors.foreach { err =>
      assert(err.isInstanceOf[Exception])
      assert(err.isInstanceOf[NoStackTrace])
    }
  }
  // scalafix:on DisableSyntax.isInstanceOf

  // === Pattern matching on unified ADT ===

  test("Pattern matching on AshtrayError discriminates IdentifierError") {
    val error: AshtrayError = AshtrayError.IdentifierError.NullInput
    error match
      case _: AshtrayError.IdentifierError => ()
      case other                           => fail(s"Expected IdentifierError, got $other")
  }

  test("Pattern matching on AshtrayError discriminates TemporalSchemaError") {
    val error: AshtrayError = AshtrayError.TemporalSchemaError.SqlComment("test")
    error match
      case _: AshtrayError.TemporalSchemaError => ()
      case other                               => fail(s"Expected TemporalSchemaError, got $other")
  }

  test("Pattern matching on AshtrayError discriminates MetaError") {
    val error: AshtrayError = AshtrayError.MetaError.IdentifierDecodeFailure(
      AshtrayError.IdentifierError.NullInput,
      1
    )
    error match
      case _: AshtrayError.MetaError => ()
      case other                     => fail(s"Expected MetaError, got $other")
  }

  test("Pattern matching on IdentifierError discriminates specific cases") {
    // Test each error type can be pattern matched
    val nullInput: AshtrayError.IdentifierError = AshtrayError.IdentifierError.NullInput
    val invalidLength: AshtrayError.IdentifierError = AshtrayError.IdentifierError.InvalidLength(1, 36)
    val invalidFormat: AshtrayError.IdentifierError = AshtrayError.IdentifierError.InvalidFormat("test", "reason")
    val invalidChar: AshtrayError.IdentifierError = AshtrayError.IdentifierError.InvalidCharacter('x', 0, "test")
    val invalidBytes: AshtrayError.IdentifierError = AshtrayError.IdentifierError.InvalidByteArrayLength(15, 16)
    val invalidBatch: AshtrayError.IdentifierError = AshtrayError.IdentifierError.InvalidBatchCount(0)

    // Verify pattern matching works for each
    assert(nullInput match
      case AshtrayError.IdentifierError.NullInput => true;
      case _                                      => false)
    assert(invalidLength match
      case AshtrayError.IdentifierError.InvalidLength(_, _) => true;
      case _                                                => false)
    assert(invalidFormat match
      case AshtrayError.IdentifierError.InvalidFormat(_, _) => true;
      case _                                                => false)
    assert(invalidChar match
      case AshtrayError.IdentifierError.InvalidCharacter(_, _, _) => true;
      case _                                                      => false)
    assert(invalidBytes match
      case AshtrayError.IdentifierError.InvalidByteArrayLength(_, _) => true;
      case _                                                         => false)
    assert(invalidBatch match
      case AshtrayError.IdentifierError.InvalidBatchCount(_) => true;
      case _                                                 => false)
  }

  test("Pattern matching on TemporalSchemaError discriminates specific cases") {
    val errors: List[AshtrayError.TemporalSchemaError] = List(
      AshtrayError.TemporalSchemaError.SqlComment("test"),
      AshtrayError.TemporalSchemaError.StringLiteral("test"),
      AshtrayError.TemporalSchemaError.EmptyColumnName("test"),
      AshtrayError.TemporalSchemaError.ComplexExpression("col", "test")
    )

    errors.foreach {
      case AshtrayError.TemporalSchemaError.SqlComment(_)           => ()
      case AshtrayError.TemporalSchemaError.StringLiteral(_)        => ()
      case AshtrayError.TemporalSchemaError.EmptyColumnName(_)      => ()
      case AshtrayError.TemporalSchemaError.ComplexExpression(_, _) => ()
    }
  }

  test("Pattern matching on MetaError discriminates specific cases") {
    val metaErrors: List[AshtrayError.MetaError] = List(
      AshtrayError.MetaError.IdentifierDecodeFailure(AshtrayError.IdentifierError.NullInput, 1),
      AshtrayError.MetaError.UnexpectedNull(1, "UNIQUEIDENTIFIER"),
      AshtrayError.MetaError.JdbcFailure("test", new Exception("test"))
    )

    metaErrors.foreach {
      case AshtrayError.MetaError.IdentifierDecodeFailure(_, _) => ()
      case AshtrayError.MetaError.UnexpectedNull(_, _)          => ()
      case AshtrayError.MetaError.JdbcFailure(_, _)             => ()
    }
  }

  // === Error message verification ===

  test("IdentifierError.NullInput has descriptive message") {
    assertEquals(AshtrayError.IdentifierError.NullInput.getMessage, "Input was null")
  }

  test("IdentifierError.InvalidLength has descriptive message with values") {
    val err = AshtrayError.IdentifierError.InvalidLength(10, 36)
    assert(err.getMessage.contains("10"))
    assert(err.getMessage.contains("36"))
  }

  test("IdentifierError.InvalidCharacter has descriptive message with position") {
    val err = AshtrayError.IdentifierError.InvalidCharacter('z', 34, "00112233-4455-6677-8899-aabbccddeezz")
    assert(err.getMessage.contains("'z'"))
    assert(err.getMessage.contains("34"))
  }

  test("TemporalSchemaError messages include fragment for debugging") {
    val fragment = "Col1, Col2 -- comment"
    val err = AshtrayError.TemporalSchemaError.SqlComment(fragment)
    assert(err.getMessage.contains(fragment))
  }

  test("MetaError.IdentifierDecodeFailure includes column index and cause") {
    val cause = AshtrayError.IdentifierError.InvalidLength(10, 36)
    val err = AshtrayError.MetaError.IdentifierDecodeFailure(cause, 3)
    assert(err.getMessage.contains("3"))
    assert(err.getMessage.contains(cause.getMessage))
  }

  // === Type aliases work correctly ===

  // scalafix:off DisableSyntax.isInstanceOf
  test("IdentifierError type alias resolves correctly") {
    val err: IdentifierError = IdentifierError.NullInput
    assert(err.isInstanceOf[AshtrayError.IdentifierError])
  }

  test("TemporalSchemaError type alias resolves correctly") {
    val err: TemporalSchemaError = TemporalSchemaError.SqlComment("test")
    assert(err.isInstanceOf[AshtrayError.TemporalSchemaError])
  }

  test("MetaError type alias resolves correctly") {
    val err: MetaError = MetaError.UnexpectedNull(1, "UNIQUEIDENTIFIER")
    assert(err.isInstanceOf[AshtrayError.MetaError])
  }
  // scalafix:on DisableSyntax.isInstanceOf

end ErrorHierarchyTests

/** Tests for error propagation through database operations using real SQL Server container. */
class MetaErrorIntegrationTests extends MSSQLContainerSuite:

  override def afterContainersStart(container: Containers): Unit =
    super.afterContainersStart(container)
    sql"""
          CREATE TABLE [dbo].[meta_error_test](
            id INT IDENTITY(1,1) PRIMARY KEY,
            valid_identifier UNIQUEIDENTIFIER NOT NULL,
            nullable_identifier UNIQUEIDENTIFIER NULL
          );
          
          -- Insert test data with valid identifier
          INSERT INTO [dbo].[meta_error_test] (valid_identifier, nullable_identifier)
          VALUES (NEWID(), NEWID());
          
          -- Insert test data with NULL nullable identifier
          INSERT INTO [dbo].[meta_error_test] (valid_identifier, nullable_identifier)
          VALUES (NEWID(), NULL);
         """.update.run.map(_ => ()).transact(transactor(container)).unsafeRunSync()
  end afterContainersStart

  override def beforeContainersStop(container: Containers): Unit =
    sql"DROP TABLE [dbo].[meta_error_test]".update.run
      .map(_ => ())
      .transact(transactor(container))
      .unsafeRunSync()
    super.beforeContainersStop(container)

  test("Meta[Identifier] successfully decodes valid UNIQUEIDENTIFIER from database") {
    import ashtray.mssql.given
    withContainers { container =>
      val result = sql"SELECT valid_identifier FROM [dbo].[meta_error_test] WHERE id = 1"
        .query[Identifier]
        .unique
        .transact(transactor(container))
        .unsafeRunSync()

      // Should successfully decode
      assert(result.mostSignificant != 0L || result.leastSignificant != 0L)
    }
  }

  test("Meta[Identifier] handles NULL in nullable column correctly") {
    import ashtray.mssql.given
    withContainers { container =>
      val result = sql"SELECT nullable_identifier FROM [dbo].[meta_error_test] WHERE id = 2"
        .query[Option[Identifier]]
        .unique
        .transact(transactor(container))
        .unsafeRunSync()

      assertEquals(result, None)
    }
  }

  test("Meta[UUID] successfully round-trips java.util.UUID through database") {
    import ashtray.mssql.given
    import java.util.UUID

    withContainers { container =>
      val testUuid = UUID.randomUUID()
      val result = sql"SELECT $testUuid AS uuid"
        .query[UUID]
        .unique
        .transact(transactor(container))
        .unsafeRunSync()

      assertEquals(result, testUuid)
    }
  }

  test("Meta instances preserve identifier through INSERT/SELECT round trip") {
    import ashtray.mssql.given
    withContainers { container =>
      val testId = Identifier.parseUnsafe("01234567-89ab-cdef-0123-456789abcdef")

      // Insert and immediately select back
      val result = sql"""
        INSERT INTO [dbo].[meta_error_test] (valid_identifier, nullable_identifier)
        OUTPUT inserted.valid_identifier
        VALUES ($testId, $testId)
      """
        .query[Identifier]
        .unique
        .transact(transactor(container))
        .unsafeRunSync()

      assertEquals(result, testId)
    }
  }

  test("Meta[Identifier.Versioned[V7]] preserves version through round trip") {
    import ashtray.mssql.given
    withContainers { container =>
      // Use a V7 identifier
      val testId: IdentifierV7 = Identifier.Versioned.unsafeWrap(
        Identifier.parseUnsafe("01234567-89ab-7def-8123-456789abcdef")
      )

      val result = sql"""
        INSERT INTO [dbo].[meta_error_test] (valid_identifier, nullable_identifier)
        OUTPUT inserted.valid_identifier
        VALUES ($testId, NULL)
      """
        .query[IdentifierV7]
        .unique
        .transact(transactor(container))
        .unsafeRunSync()

      assertEquals(result.untyped, testId.untyped)
      assertEquals(result.untyped.version, Version.V7)
    }
  }

  test("Meta[LocalDateTime] handles DATETIME2 with full precision") {
    import ashtray.mssql.given
    import java.time.LocalDateTime

    withContainers { container =>
      val dateTime = LocalDateTime.of(2024, 12, 6, 23, 45, 30, 123456700)

      val result = sql"SELECT $dateTime AS dt"
        .query[LocalDateTime]
        .unique
        .transact(transactor(container))
        .unsafeRunSync()

      // SQL Server DATETIME2 has 7 decimal places (100ns precision)
      assertEquals(result, dateTime)
    }
  }

  test("Meta[OffsetDateTime] handles DATETIMEOFFSET with timezone") {
    import ashtray.mssql.given
    import java.time.OffsetDateTime
    import java.time.ZoneOffset

    withContainers { container =>
      val dateTime = OffsetDateTime.of(2024, 12, 6, 23, 45, 30, 123456700, ZoneOffset.ofHours(5))

      val result = sql"SELECT $dateTime AS dto"
        .query[OffsetDateTime]
        .unique
        .transact(transactor(container))
        .unsafeRunSync()

      assertEquals(result, dateTime)
    }
  }

  test("Multiple identifier operations in single transaction preserve values") {
    import ashtray.mssql.given
    withContainers { container =>
      val ids = List(
        Identifier.parseUnsafe("11111111-1111-1111-1111-111111111111"),
        Identifier.parseUnsafe("22222222-2222-2222-2222-222222222222"),
        Identifier.parseUnsafe("33333333-3333-3333-3333-333333333333")
      )

      val result = (for
        _ <- ids.traverse(id =>
          sql"INSERT INTO [dbo].[meta_error_test] (valid_identifier, nullable_identifier) VALUES ($id, NULL)".update.run)
        retrieved <-
          sql"SELECT valid_identifier FROM [dbo].[meta_error_test] WHERE valid_identifier IN (${ids(0)}, ${ids(1)}, ${ids(2)})"
            .query[Identifier]
            .to[List]
      yield retrieved).transact(transactor(container)).unsafeRunSync()

      assertEquals(result.toSet, ids.toSet)
    }
  }

end MetaErrorIntegrationTests
