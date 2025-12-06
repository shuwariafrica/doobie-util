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
import java.time.ZoneOffset

import scala.concurrent.duration.*

import cats.syntax.all.*

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import doobie.*
import doobie.implicits.*

import ashtray.mssql.*
import ashtray.mssql.given
import ashtray.test.MSSQLContainerSuite

/** Integration tests for temporal tables using real SQL Server database via testcontainers.
  *
  * These tests verify the complete temporal tables functionality against an actual SQL Server
  * instance, testing:
  *   - System-versioned table creation and configuration
  *   - All FOR SYSTEM_TIME query modes (AS OF, FROM...TO, BETWEEN, CONTAINED IN, ALL)
  *   - Temporal query operations through TemporalRepo
  *   - Period tracking and history retention
  *   - Time-travel queries and historical state reconstruction
  */
class TemporalIntegrationTests extends MSSQLContainerSuite:

  // Test entity representing an employee
  case class Employee(id: Long, name: String, salary: BigDecimal, department: String)

  given Read[Employee] =
    Read[(Long, String, BigDecimal, String)].map { case (id, name, salary, dept) =>
      Employee(id, name, salary, dept)
    }

  given Write[Employee] =
    Write[(Long, String, BigDecimal, String)].contramap { emp =>
      (emp.id, emp.name, emp.salary, emp.department)
    }

  given TemporalSchema[Long, Employee] = TemporalSchema[Long, Employee](
    table = "dbo.Employee",
    history = "dbo.EmployeeHistory",
    id = "EmployeeID",
    validFrom = "ValidFrom",
    validTo = "ValidTo",
    cols = fr"EmployeeID, Name, Salary, Department"
  )

  /** Creates the system-versioned temporal table for testing. */
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
        Department NVARCHAR(50) NOT NULL,
        ValidFrom DATETIME2 GENERATED ALWAYS AS ROW START NOT NULL,
        ValidTo DATETIME2 GENERATED ALWAYS AS ROW END NOT NULL,
        PERIOD FOR SYSTEM_TIME (ValidFrom, ValidTo)
      )
      WITH (SYSTEM_VERSIONING = ON (HISTORY_TABLE = dbo.EmployeeHistory));
    """.update.run.void

    createTableSql.transact(xa)

  /** Inserts an employee record. */
  def insertEmployee(emp: Employee)(xa: Transactor[IO]): IO[Unit] =
    sql"""
      INSERT INTO dbo.Employee (EmployeeID, Name, Salary, Department)
      VALUES (${emp.id}, ${emp.name}, ${emp.salary}, ${emp.department})
    """.update.run.void.transact(xa)

  /** Updates an employee's salary. */
  def updateSalary(id: Long, newSalary: BigDecimal)(xa: Transactor[IO]): IO[Unit] =
    sql"""
      UPDATE dbo.Employee 
      SET Salary = $newSalary 
      WHERE EmployeeID = $id
    """.update.run.void.transact(xa)

  /** Updates an employee's department. */
  def updateDepartment(id: Long, newDept: String)(xa: Transactor[IO]): IO[Unit] =
    sql"""
      UPDATE dbo.Employee 
      SET Department = $newDept 
      WHERE EmployeeID = $id
    """.update.run.void.transact(xa)

  /** Deletes an employee. */
  def deleteEmployee(id: Long)(xa: Transactor[IO]): IO[Unit] =
    sql"""
      DELETE FROM dbo.Employee WHERE EmployeeID = $id
    """.update.run.void.transact(xa)

  /** Gets current timestamp from SQL Server. */
  def getCurrentServerTime(xa: Transactor[IO]): IO[LocalDateTime] =
    sql"SELECT SYSUTCDATETIME()".query[LocalDateTime].unique.transact(xa)

  test("System-versioned table tracks period columns automatically") {
    withContainers { container =>
      val xa = transactor(container)

      val test = for
        _ <- setupTemporalTable(xa)
        emp = Employee(1, "Alice", BigDecimal(75000), "Engineering")
        _ <- insertEmployee(emp)(xa)

        // Query current version
        result <- sql"""
          SELECT EmployeeID, Name, Salary, Department, ValidFrom, ValidTo
          FROM dbo.Employee
          WHERE EmployeeID = 1
        """.query[(Long, String, BigDecimal, String, LocalDateTime, LocalDateTime)].unique.transact(xa)

        (id, name, salary, dept, validFrom, validTo) = result
      yield
        assertEquals(id, 1L)
        assertEquals(name, "Alice")
        assertEquals(salary, BigDecimal(75000))
        assertEquals(dept, "Engineering")
        // ValidTo should be max datetime for current records
        assertEquals(validTo, Period.MaxDateTime2)
        // ValidFrom should be recent
        assert(validFrom.isBefore(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1)))

      test.unsafeRunSync()
    }
  }

  test("FOR SYSTEM_TIME AS OF returns point-in-time snapshot") {
    withContainers { container =>
      val xa = transactor(container)
      given Transactor[IO] = xa

      val repo = TemporalRepo.derived[IO, Long, Employee]

      val test = for
        _ <- setupTemporalTable(xa)

        // Capture time before any inserts
        t0 <- getCurrentServerTime(xa)

        // Insert initial record
        emp1 = Employee(1, "Bob", BigDecimal(80000), "Sales")
        _ <- insertEmployee(emp1)(xa)
        _ <- IO.sleep(100.millis)
        t1 <- getCurrentServerTime(xa) // Capture after insert

        // Update salary
        _ <- updateSalary(1, BigDecimal(85000))(xa)
        _ <- IO.sleep(100.millis)
        t2 <- getCurrentServerTime(xa) // Capture after first update

        // Update department
        _ <- updateDepartment(1, "Marketing")(xa)
        _ <- IO.sleep(100.millis)
        t3 <- getCurrentServerTime(xa) // Capture after second update

        // Query at different points in time
        beforeInsert <- repo.asOf(1, t0.toInstant(ZoneOffset.UTC))
        atInsert <- repo.asOf(1, t1.toInstant(ZoneOffset.UTC))
        afterFirstUpdate <- repo.asOf(1, t2.toInstant(ZoneOffset.UTC))
        afterSecondUpdate <- repo.asOf(1, t3.toInstant(ZoneOffset.UTC))
      yield
        // Before insert - no record
        assertEquals(beforeInsert, None)

        // At insert - original values
        assert(atInsert.isDefined)
        assertEquals(atInsert.get.entity.name, "Bob")
        assertEquals(atInsert.get.entity.salary, BigDecimal(80000))
        assertEquals(atInsert.get.entity.department, "Sales")

        // After first update - salary changed
        assert(afterFirstUpdate.isDefined)
        assertEquals(afterFirstUpdate.get.entity.salary, BigDecimal(85000))
        assertEquals(afterFirstUpdate.get.entity.department, "Sales")

        // After second update - department changed
        assert(afterSecondUpdate.isDefined)
        assertEquals(afterSecondUpdate.get.entity.salary, BigDecimal(85000))
        assertEquals(afterSecondUpdate.get.entity.department, "Marketing")

      test.unsafeRunSync()
    }
  }

  test("FOR SYSTEM_TIME FROM...TO returns overlapping records (exclusive bounds)") {
    withContainers { container =>
      val xa = transactor(container)
      given Transactor[IO] = xa

      val repo = TemporalRepo.derived[IO, Long, Employee]

      val test = for
        _ <- setupTemporalTable(xa)

        emp = Employee(2, "Charlie", BigDecimal(90000), "IT")
        t1 <- getCurrentServerTime(xa)
        _ <- insertEmployee(emp)(xa)
        _ <- IO.sleep(100.millis)

        t2 <- getCurrentServerTime(xa)
        _ <- updateSalary(2, BigDecimal(95000))(xa)
        _ <- IO.sleep(100.millis)

        t3 <- getCurrentServerTime(xa)
        _ <- updateSalary(2, BigDecimal(100000))(xa)

        // Query FROM t1 TO t3 (should include versions at t1 and t2, exclude t3)
        history <- repo.historyBetween(
          2,
          t1.minusSeconds(1).toInstant(ZoneOffset.UTC),
          t3.toInstant(ZoneOffset.UTC)
        )
      yield
        // Should have at least 2 versions (initial + first update)
        assert(history.length >= 2)

        // Verify we have both salary values
        val salaries = history.map(_.entity.salary).toSet
        assert(salaries.contains(BigDecimal(90000)))
        assert(salaries.contains(BigDecimal(95000)))

      test.unsafeRunSync()
    }
  }

  test("FOR SYSTEM_TIME BETWEEN returns overlapping records (inclusive end)") {
    withContainers { container =>
      val xa = transactor(container)

      val schema = summon[TemporalSchema[Long, Employee]]

      val test = for
        _ <- setupTemporalTable(xa)

        emp = Employee(3, "Diana", BigDecimal(85000), "HR")
        t1 <- getCurrentServerTime(xa)
        _ <- insertEmployee(emp)(xa)
        _ <- IO.sleep(100.millis)

        t2 <- getCurrentServerTime(xa)
        _ <- updateDepartment(3, "Legal")(xa)
        _ <- IO.sleep(100.millis)

        t3 <- getCurrentServerTime(xa)

        // Query BETWEEN t1 AND t3 (inclusive end)
        systemTime = SystemTime.between(
          t1.minusSeconds(1).toInstant(ZoneOffset.UTC),
          t3.toInstant(ZoneOffset.UTC)
        )

        history <- (fr"SELECT" ++ schema.allColumns ++
          fr"FROM" ++ schema.forSystemTime(systemTime) ++
          fr"WHERE EmployeeID = 3")
          .query[Temporal.Versioned[Employee]]
          .to[List]
          .transact(xa)
      yield
        assert(history.nonEmpty)
        assert(history.exists(_.entity.department == "HR"))
        assert(history.exists(_.entity.department == "Legal"))

      test.unsafeRunSync()
    }
  }

  test("FOR SYSTEM_TIME CONTAINED IN returns records entirely within period") {
    withContainers { container =>
      val xa = transactor(container)
      given Transactor[IO] = xa

      val repo = TemporalRepo.derived[IO, Long, Employee]

      val test = for
        _ <- setupTemporalTable(xa)

        t1 <- getCurrentServerTime(xa)
        emp = Employee(4, "Eve", BigDecimal(70000), "Finance")
        _ <- insertEmployee(emp)(xa)
        _ <- IO.sleep(100.millis)

        t2 <- getCurrentServerTime(xa)
        _ <- updateSalary(4, BigDecimal(75000))(xa)
        _ <- IO.sleep(100.millis)

        t3 <- getCurrentServerTime(xa)
        _ <- updateSalary(4, BigDecimal(80000))(xa)
        _ <- IO.sleep(100.millis)

        t4 <- getCurrentServerTime(xa)

        // Query CONTAINED IN (t1, t4) - should get versions that started AND ended within range
        contained <- repo.containedIn(
          4,
          t1.toInstant(ZoneOffset.UTC),
          t4.toInstant(ZoneOffset.UTC)
        )
      yield
        // Should have intermediate versions (not including current)
        assert(contained.nonEmpty)
        assert(contained.forall(!_.isCurrent))

      test.unsafeRunSync()
    }
  }

  test("FOR SYSTEM_TIME ALL returns complete history") {
    withContainers { container =>
      val xa = transactor(container)
      given Transactor[IO] = xa

      val repo = TemporalRepo.derived[IO, Long, Employee]

      val test = for
        _ <- setupTemporalTable(xa)

        emp = Employee(5, "Frank", BigDecimal(95000), "Operations")
        _ <- insertEmployee(emp)(xa)
        _ <- IO.sleep(50.millis)

        _ <- updateSalary(5, BigDecimal(100000))(xa)
        _ <- IO.sleep(50.millis)

        _ <- updateDepartment(5, "Strategy")(xa)
        _ <- IO.sleep(50.millis)

        // Get all history
        allHistory <- repo.history(5)
      yield
        // Should have at least 3 versions (initial + 2 updates)
        assert(allHistory.length >= 3)

        // Verify chronological order (oldest to newest)
        val periods = allHistory.map(_.period)
        val sorted = periods.sortBy(_.validFrom)
        assertEquals(periods, sorted)

        // Last version should be current
        assert(allHistory.last.isCurrent)

        // Previous versions should not be current
        allHistory.init.foreach(v => assert(!v.isCurrent))

      test.unsafeRunSync()
    }
  }

  test("TemporalRepo.current returns only current version") {
    withContainers { container =>
      val xa = transactor(container)
      given Transactor[IO] = xa

      val repo = TemporalRepo.derived[IO, Long, Employee]

      val test = for
        _ <- setupTemporalTable(xa)

        emp = Employee(6, "Grace", BigDecimal(105000), "Research")
        _ <- insertEmployee(emp)(xa)
        _ <- IO.sleep(50.millis)

        _ <- updateSalary(6, BigDecimal(110000))(xa)
        _ <- IO.sleep(50.millis)

        // Get current version
        currentOpt <- repo.current(6)
      yield
        assert(currentOpt.isDefined)
        val curr: Employee = currentOpt.get

        // Should have latest values
        assertEquals(curr.name, "Grace")
        assertEquals(curr.salary, BigDecimal(110000))
        assertEquals(curr.department, "Research")

      test.unsafeRunSync()
    }
  }

  test("TemporalRepo.diff compares two versions at different times") {
    withContainers { container =>
      val xa = transactor(container)
      given Transactor[IO] = xa

      val repo = TemporalRepo.derived[IO, Long, Employee]

      val test = for
        _ <- setupTemporalTable(xa)

        emp = Employee(7, "Henry", BigDecimal(88000), "Product")
        _ <- insertEmployee(emp)(xa)
        _ <- IO.sleep(100.millis)
        t1 <- getCurrentServerTime(xa) // After insert

        _ <- updateSalary(7, BigDecimal(92000))(xa)
        _ <- IO.sleep(100.millis)
        t2 <- getCurrentServerTime(xa) // After first update

        _ <- updateDepartment(7, "Engineering")(xa)
        _ <- IO.sleep(100.millis)
        t3 <- getCurrentServerTime(xa) // After second update

        // Compare versions at t1 and t3
        diffResult <- repo.diff(
          7,
          t1.toInstant(ZoneOffset.UTC),
          t3.toInstant(ZoneOffset.UTC)
        )
      yield
        // Both versions should exist
        assert(diffResult.isDefined, "Diff should return both versions")
        val (before, after) = diffResult.get

        // Verify changes
        assertEquals(before.entity.salary, BigDecimal(88000))
        assertEquals(before.entity.department, "Product")

        assertEquals(after.entity.salary, BigDecimal(92000))
        assertEquals(after.entity.department, "Engineering")

      test.unsafeRunSync()
    }
  }

  test("Deleted records remain in history") {
    withContainers { container =>
      val xa = transactor(container)
      given Transactor[IO] = xa
      val repo = TemporalRepo.derived[IO, Long, Employee]

      val test = for
        _ <- setupTemporalTable(xa)

        emp = Employee(8, "Iris", BigDecimal(78000), "Support")
        _ <- insertEmployee(emp)(xa)
        _ <- IO.sleep(100.millis)
        tAfterInsert <- getCurrentServerTime(xa) // Capture after insert completes

        // Delete the record
        _ <- deleteEmployee(8)(xa)
        _ <- IO.sleep(100.millis)
        tAfterDelete <- getCurrentServerTime(xa)

        // Current should be None
        current <- repo.current(8)

        // But history should exist
        atInsert <- repo.asOf(8, tAfterInsert.toInstant(ZoneOffset.UTC))
        allHistory <- repo.history(8)
      yield
        // No current version
        assertEquals(current, None)

        // Can retrieve historical version
        assert(atInsert.isDefined)
        assertEquals(atInsert.get.entity.name, "Iris")
        assertEquals(atInsert.get.entity.salary, BigDecimal(78000))

        // History exists
        assert(allHistory.nonEmpty)

        // All historical versions should not be current
        allHistory.foreach(v => assert(!v.isCurrent))

      test.unsafeRunSync()
    }
  }

  test("Extension syntax with Long ID works end-to-end") {
    withContainers { container =>
      val xa = transactor(container)
      val test = for
        _ <- setupTemporalTable(xa)

        emp = Employee(9, "Jack", BigDecimal(82000), "Analytics")
        t1 <- getCurrentServerTime(xa)
        _ <- insertEmployee(emp)(xa)
        _ <- IO.sleep(100.millis)

        t2 <- getCurrentServerTime(xa)

        // Use repository derived from transactor extension
        repo = xa.temporal[Long, Employee]
        result <- repo.asOf(9L, t2.toInstant(ZoneOffset.UTC))
      yield
        assert(result.isDefined, "Employee should be found at t2")
        assertEquals(result.get.entity.name, "Jack")
        assertEquals(result.get.entity.salary, BigDecimal(82000))

      test.unsafeRunSync()
    }
  }

  test("TemporalRepo.allAsOf returns snapshot of all entities") {
    withContainers { container =>
      val xa = transactor(container)
      given Transactor[IO] = xa

      val repo = TemporalRepo.derived[IO, Long, Employee]

      val test = for
        _ <- setupTemporalTable(xa)

        // Insert multiple employees
        _ <- insertEmployee(Employee(10, "Kate", BigDecimal(90000), "Sales"))(xa)
        _ <- insertEmployee(Employee(11, "Leo", BigDecimal(85000), "Marketing"))(xa)
        _ <- insertEmployee(Employee(12, "Mia", BigDecimal(95000), "IT"))(xa)
        _ <- IO.sleep(100.millis)

        t1 <- getCurrentServerTime(xa)
        _ <- IO.sleep(100.millis)

        // Update one employee
        _ <- updateSalary(11, BigDecimal(88000))(xa)
        _ <- IO.sleep(100.millis)

        t2 <- getCurrentServerTime(xa)

        // Snapshot at t1 (before update)
        snapshotT1 <- repo.allAsOf(t1.toInstant(ZoneOffset.UTC))

        // Snapshot at t2 (after update)
        snapshotT2 <- repo.allAsOf(t2.toInstant(ZoneOffset.UTC))
      yield
        // Both snapshots should have 3 employees
        assertEquals(snapshotT1.length, 3)
        assertEquals(snapshotT2.length, 3)

        // Leo's salary should differ between snapshots
        val leoT1 = snapshotT1.find(_.entity.id == 11).map(_.entity.salary)
        val leoT2 = snapshotT2.find(_.entity.id == 11).map(_.entity.salary)

        assertEquals(leoT1, Some(BigDecimal(85000)))
        assertEquals(leoT2, Some(BigDecimal(88000)))

      test.unsafeRunSync()
    }
  }

end TemporalIntegrationTests
