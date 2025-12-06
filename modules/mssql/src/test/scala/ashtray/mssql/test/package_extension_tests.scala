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

import cats.syntax.all.*

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import doobie.*
import doobie.implicits.*

import ashtray.mssql.*
import ashtray.mssql.given
import ashtray.test.MSSQLContainerSuite

/** Integration tests for package.scala extension methods using real SQL Server database.
  *
  * These tests verify that the xa.temporal[ID, A] extension method works correctly with:
  *   - Single import principle (no additional imports needed)
  *   - Multiple ID types (Long, Identifier)
  *   - Real database queries and temporal operations
  */
class PackageExtensionTests extends MSSQLContainerSuite:

  // === Test entities with different ID types ===

  case class Employee(id: Long, name: String, salary: BigDecimal)
  case class Product(id: Identifier, name: String, price: BigDecimal)

  given Read[Employee] =
    Read[(Long, String, BigDecimal)].map { case (id, name, salary) =>
      Employee(id, name, salary)
    }

  given Write[Employee] =
    Write[(Long, String, BigDecimal)].contramap { emp =>
      (emp.id, emp.name, emp.salary)
    }

  given Read[Product] =
    Read[(Identifier, String, BigDecimal)].map { case (id, name, price) =>
      Product(id, name, price)
    }

  given Write[Product] =
    Write[(Identifier, String, BigDecimal)].contramap { prod =>
      (prod.id, prod.name, prod.price)
    }

  // === Schemas ===

  given TemporalSchema[Long, Employee] = TemporalSchema[Long, Employee](
    table = "dbo.Employee",
    history = "dbo.EmployeeHistory",
    id = "EmployeeID",
    validFrom = "ValidFrom",
    validTo = "ValidTo",
    cols = fr"EmployeeID, Name, Salary"
  )

  given TemporalSchema[Identifier, Product] = TemporalSchema[Identifier, Product](
    table = "dbo.Product",
    history = "dbo.ProductHistory",
    id = "ProductID",
    validFrom = "ValidFrom",
    validTo = "ValidTo",
    cols = fr"ProductID, Name, Price"
  )

  /** Setup temporal table for Employee with Long ID. */
  def setupEmployeeTable(xa: Transactor[IO]): IO[Unit] =
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

  /** Setup temporal table for Product with Identifier (UNIQUEIDENTIFIER) ID. */
  def setupProductTable(xa: Transactor[IO]): IO[Unit] =
    val createTableSql = sql"""
      IF OBJECT_ID('dbo.Product', 'U') IS NOT NULL
      BEGIN
        IF OBJECTPROPERTY(OBJECT_ID('dbo.Product'), 'TableTemporalType') = 2
          ALTER TABLE dbo.Product SET (SYSTEM_VERSIONING = OFF);
        DROP TABLE IF EXISTS dbo.ProductHistory;
        DROP TABLE dbo.Product;
      END
      
      CREATE TABLE dbo.Product (
        ProductID UNIQUEIDENTIFIER NOT NULL PRIMARY KEY,
        Name NVARCHAR(100) NOT NULL,
        Price DECIMAL(18, 2) NOT NULL,
        ValidFrom DATETIME2 GENERATED ALWAYS AS ROW START NOT NULL,
        ValidTo DATETIME2 GENERATED ALWAYS AS ROW END NOT NULL,
        PERIOD FOR SYSTEM_TIME (ValidFrom, ValidTo)
      )
      WITH (SYSTEM_VERSIONING = ON (HISTORY_TABLE = dbo.ProductHistory));
    """.update.run.void

    createTableSql.transact(xa)

  test("xa.temporal[Long, A] extension derives repository with Long ID type") {
    withContainers { container =>
      val xa = transactor(container)
      val result = for
        _ <- setupEmployeeTable(xa)
        _ <- sql"INSERT INTO dbo.Employee (EmployeeID, Name, Salary) VALUES (1, 'Alice', 75000)".update.run.transact(xa)

        // Use extension method to derive repository
        repo = xa.temporal[Long, Employee]

        // Query using repository
        employee <- repo.current(1L)
      yield employee

      val employee = result.unsafeRunSync()
      assert(employee.isDefined)
      assertEquals(employee.get.name, "Alice")
      assertEquals(employee.get.salary, BigDecimal(75000))
    }
  }

  test("xa.temporal[Identifier, A] extension derives repository with Identifier ID type") {
    withContainers { container =>
      val xa = transactor(container)
      val productId = Identifier.parseUnsafe("550e8400-e29b-41d4-a716-446655440000")

      val result = for
        _ <- setupProductTable(xa)
        _ <- sql"INSERT INTO dbo.Product (ProductID, Name, Price) VALUES ($productId, 'Widget', 29.99)".update.run
          .transact(xa)

        // Use extension method to derive repository with Identifier ID
        repo = xa.temporal[Identifier, Product]

        // Query using repository
        product <- repo.current(productId)
      yield product

      val product = result.unsafeRunSync()
      assert(product.isDefined)
      assertEquals(product.get.name, "Widget")
      assertEquals(product.get.price, BigDecimal(29.99))
    }
  }

  test("Extension method works with only single import (no TemporalRepo.* import needed)") {
    withContainers { container =>
      val xa = transactor(container)
      // This entire test file has only `import ashtray.mssql.*`
      // No `import ashtray.mssql.TemporalRepo.*` is present
      // If this compiles and runs, single import principle is verified

      val result = for
        _ <- setupEmployeeTable(xa)
        _ <- sql"INSERT INTO dbo.Employee (EmployeeID, Name, Salary) VALUES (2, 'Bob', 85000)".update.run.transact(xa)

        repo = xa.temporal[Long, Employee] // Extension available via package object
        employee <- repo.current(2L)
      yield employee

      val employee = result.unsafeRunSync()
      assert(employee.isDefined, "Extension method worked with single import")
    }
  }

  test("Repository derived via extension supports all temporal operations") {
    withContainers { container =>
      val xa = transactor(container)
      val result = for
        _ <- setupEmployeeTable(xa)
        _ <- sql"INSERT INTO dbo.Employee (EmployeeID, Name, Salary) VALUES (3, 'Charlie', 95000)".update.run
          .transact(xa)
        _ <- IO.sleep(scala.concurrent.duration.Duration(1, "second"))
        _ <- sql"UPDATE dbo.Employee SET Salary = 100000 WHERE EmployeeID = 3".update.run.transact(xa)

        repo = xa.temporal[Long, Employee]

        // Test various temporal operations
        current <- repo.current(3L)
        history <- repo.history(3L)
      yield (current, history)

      val (current, history) = result.unsafeRunSync()

      // Current salary should be updated value
      assert(current.isDefined)
      assertEquals(current.get.salary, BigDecimal(100000))

      // History should have 2 versions
      assertEquals(history.length, 2)
      assertEquals(history.head.entity.salary, BigDecimal(95000)) // Original
      assertEquals(history.last.entity.salary, BigDecimal(100000)) // Updated
    }
  }

end PackageExtensionTests
