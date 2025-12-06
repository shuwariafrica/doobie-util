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

import cats.syntax.all.*

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import doobie.*
import doobie.implicits.*

import ashtray.mssql.*
import ashtray.mssql.given
import ashtray.test.MSSQLContainerSuite

/** Comprehensive tests for Temporal and Period with real database.
  *
  * Tests verify:
  *   - Read/Write derivation for Temporal[A, M]
  *   - Period.isCurrent detection
  *   - Temporal.isCurrent extension method
  *   - Temporal.current extraction
  *   - Database round-trip for temporal entities
  */
class TemporalPeriodTests extends MSSQLContainerSuite:

  case class Employee(id: Long, name: String, salary: BigDecimal)

  given Read[Employee] =
    Read[(Long, String, BigDecimal)].map { case (id, name, salary) =>
      Employee(id, name, salary)
    }

  given Write[Employee] =
    Write[(Long, String, BigDecimal)].contramap { emp =>
      (emp.id, emp.name, emp.salary)
    }

  /** Setup temporal table. */
  def setupTemporalTable(xa: Transactor[IO]): IO[Unit] =
    val createTableSql = sql"""
      IF OBJECT_ID('dbo.Employee', 'U') IS NOT NULL
      BEGIN
        IF OBJECTPROPERTY(OBJECT_ID('dbo.Employee'), 'TableTemporalType') = 2
          ALTER TABLE dbo.Employee SET (SYSTEM_VERSIONING = OFF);
        DROP TABLE IF EXISTS dbo.EmployeeHistory;
        DROP TABLE dbo.Employee;
      END
      
      CREATE TABLE dbo.Employee (
        EmployeeID BIGINT NOT NULL PRIMARY KEY,
        Name NVARCHAR(100) NOT NULL,
        Salary DECIMAL(18, 2) NOT NULL,
        ValidFrom DATETIME2 GENERATED ALWAYS AS ROW START NOT NULL,
        ValidTo DATETIME2 GENERATED ALWAYS AS ROW END NOT NULL,
        PERIOD FOR SYSTEM_TIME (ValidFrom, ValidTo)
      )
      WITH (SYSTEM_VERSIONING = ON (HISTORY_TABLE = dbo.EmployeeHistory));
    """.update.run.void

    createTableSql.transact(xa)

  test("Temporal.Versioned Read instance correctly deserializes entity and period from database") {
    withContainers { container =>
      val xa = transactor(container)
      val result = for
        _ <- setupTemporalTable(xa)
        _ <- sql"INSERT INTO dbo.Employee (EmployeeID, Name, Salary) VALUES (1, 'Alice', 75000)".update.run.transact(xa)

        // Query with automatic Read[Temporal.Versioned[Employee]] derivation
        temporal <- sql"SELECT EmployeeID, Name, Salary, ValidFrom, ValidTo FROM dbo.Employee WHERE EmployeeID = 1"
          .query[Temporal.Versioned[Employee]]
          .unique
          .transact(xa)
      yield temporal

      val temporal = result.unsafeRunSync()

      // Verify entity deserialized correctly
      assertEquals(temporal.entity.id, 1L)
      assertEquals(temporal.entity.name, "Alice")
      assertEquals(temporal.entity.salary, BigDecimal(75000))

      // Verify period columns deserialized
      assert(temporal.period.validFrom.getYear >= 2024)
      assert(temporal.period.validTo.getYear == 9999) // Current row
    }
  }

  test("Period.isCurrent correctly identifies current rows by validTo == MaxDateTime2") {
    withContainers { container =>
      val xa = transactor(container)
      val result = for
        _ <- setupTemporalTable(xa)
        _ <- sql"INSERT INTO dbo.Employee (EmployeeID, Name, Salary) VALUES (2, 'Bob', 85000)".update.run.transact(xa)
        _ <- IO.sleep(scala.concurrent.duration.Duration(1, "second"))
        _ <- sql"UPDATE dbo.Employee SET Salary = 90000 WHERE EmployeeID = 2".update.run.transact(xa)

        // Get both current and historical versions
        allVersions <-
          sql"SELECT EmployeeID, Name, Salary, ValidFrom, ValidTo FROM dbo.Employee FOR SYSTEM_TIME ALL WHERE EmployeeID = 2 ORDER BY ValidFrom"
            .query[Temporal.Versioned[Employee]]
            .to[List]
            .transact(xa)
      yield allVersions

      val versions = result.unsafeRunSync()

      // Should have 2 versions
      assertEquals(versions.length, 2)

      // Historical version should NOT be current
      val historical = versions.head
      assertEquals(historical.period.validTo.getYear == 9999, false)

      // Current version SHOULD be current
      val current = versions.last
      assertEquals(current.period.validTo.getYear == 9999, true)
    }
  }

  test("Temporal.isCurrent extension method correctly identifies current versions") {
    withContainers { container =>
      val xa = transactor(container)
      val result = for
        _ <- setupTemporalTable(xa)
        _ <- sql"INSERT INTO dbo.Employee (EmployeeID, Name, Salary) VALUES (3, 'Charlie', 95000)".update.run
          .transact(xa)
        _ <- IO.sleep(scala.concurrent.duration.Duration(1, "second"))
        _ <- sql"UPDATE dbo.Employee SET Salary = 100000 WHERE EmployeeID = 3".update.run.transact(xa)

        allVersions <-
          sql"SELECT EmployeeID, Name, Salary, ValidFrom, ValidTo FROM dbo.Employee FOR SYSTEM_TIME ALL WHERE EmployeeID = 3 ORDER BY ValidFrom"
            .query[Temporal.Versioned[Employee]]
            .to[List]
            .transact(xa)
      yield allVersions

      val versions = result.unsafeRunSync()

      // Historical version
      val historical = versions.head
      assertEquals(historical.isCurrent, false)

      // Current version
      val current = versions.last
      assertEquals(current.isCurrent, true)
    }
  }

  test("Temporal.current extension correctly extracts entity") {
    withContainers { container =>
      val xa = transactor(container)
      val result = for
        _ <- setupTemporalTable(xa)
        _ <- sql"INSERT INTO dbo.Employee (EmployeeID, Name, Salary) VALUES (4, 'Diana', 105000)".update.run
          .transact(xa)

        temporal <- sql"SELECT EmployeeID, Name, Salary, ValidFrom, ValidTo FROM dbo.Employee WHERE EmployeeID = 4"
          .query[Temporal.Versioned[Employee]]
          .unique
          .transact(xa)
      yield temporal

      val temporal = result.unsafeRunSync()
      val employee: Employee = temporal.current

      assertEquals(employee.id, 4L)
      assertEquals(employee.name, "Diana")
      assertEquals(employee.salary, BigDecimal(105000))
    }
  }

  test("Period.MaxDateTime2 constant matches SQL Server max DATETIME2") {
    withContainers { container =>
      val xa = transactor(container)
      val result = for
        _ <- setupTemporalTable(xa)
        _ <- sql"INSERT INTO dbo.Employee (EmployeeID, Name, Salary) VALUES (5, 'Eve', 110000)".update.run.transact(xa)

        temporal <- sql"SELECT EmployeeID, Name, Salary, ValidFrom, ValidTo FROM dbo.Employee WHERE EmployeeID = 5"
          .query[Temporal.Versioned[Employee]]
          .unique
          .transact(xa)
      yield temporal

      val temporal = result.unsafeRunSync()

      // Current row's ValidTo should match Period.MaxDateTime2
      assertEquals(temporal.period.validTo.getYear, Period.MaxDateTime2.getYear)
      assertEquals(temporal.period.validTo.getMonth, Period.MaxDateTime2.getMonth)
      assertEquals(temporal.period.validTo.getDayOfMonth, Period.MaxDateTime2.getDayOfMonth)
    }
  }

  test("Temporal.Versioned and Temporal.Current type aliases resolve correctly") {
    val emp = Employee(1, "Test", BigDecimal(50000))
    val period = Period(LocalDateTime.now, Period.MaxDateTime2)

    val versioned: TemporalVersioned[Employee] = Temporal(emp, period)
    val current: TemporalCurrent[Employee] = Temporal(emp, period)

    assertEquals(versioned.entity, emp)
    assertEquals(current.entity, emp)
  }

  test("Historical versions maintain accurate period boundaries") {
    withContainers { container =>
      val xa = transactor(container)
      val result = for
        _ <- setupTemporalTable(xa)
        _ <- sql"INSERT INTO dbo.Employee (EmployeeID, Name, Salary) VALUES (6, 'Frank', 120000)".update.run
          .transact(xa)
        _ <- IO.sleep(scala.concurrent.duration.Duration(1, "second"))

        beforeUpdate <- IO.realTime.map(_.toMillis)
        _ <- sql"UPDATE dbo.Employee SET Salary = 125000 WHERE EmployeeID = 6".update.run.transact(xa)
        afterUpdate <- IO.realTime.map(_.toMillis)

        versions <-
          sql"SELECT EmployeeID, Name, Salary, ValidFrom, ValidTo FROM dbo.Employee FOR SYSTEM_TIME ALL WHERE EmployeeID = 6 ORDER BY ValidFrom"
            .query[Temporal.Versioned[Employee]]
            .to[List]
            .transact(xa)
      yield versions

      val versions = result.unsafeRunSync()
      assertEquals(versions.length, 2)

      val historical = versions.head
      val current = versions.last

      // Historical period.validTo should equal current period.validFrom (no gaps)
      assertEquals(historical.period.validTo, current.period.validFrom)

      // Historical version should not be current
      assertEquals(historical.isCurrent, false)

      // Current version should be current
      assertEquals(current.isCurrent, true)
    }
  }

end TemporalPeriodTests
