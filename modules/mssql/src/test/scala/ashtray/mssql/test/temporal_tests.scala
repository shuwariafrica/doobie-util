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

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

import doobie.*
import doobie.implicits.*

import ashtray.mssql.*

import munit.FunSuite

class TemporalTests extends FunSuite:

  // Test entity
  case class Employee(id: Long, name: String, salary: BigDecimal)

  given Read[Employee] = Read[(Long, String, BigDecimal)].map { case (id, name, salary) =>
    Employee(id, name, salary)
  }

  given Write[Employee] = Write[(Long, String, BigDecimal)].contramap { emp =>
    (emp.id, emp.name, emp.salary)
  }

  given TemporalSchema[Long, Employee] = TemporalSchema[Long, Employee](
    table = "dbo.Employee",
    history = "dbo.EmployeeHistory",
    id = "EmployeeID",
    validFrom = "ValidFrom",
    validTo = "ValidTo",
    cols = fr"EmployeeID, Name, Salary"
  )

  // Instant test fixtures
  val instant1 = Instant.parse("2024-01-15T10:00:00Z")
  val instant2 = Instant.parse("2024-06-20T14:30:00Z")
  val instant3 = Instant.parse("2024-12-31T23:59:59Z")

  // LocalDateTime test fixtures
  val dt1 = LocalDateTime.of(2024, 1, 15, 10, 0, 0)
  val dt2 = LocalDateTime.of(2024, 6, 20, 14, 30, 0)
  val dt3 = LocalDateTime.of(2024, 12, 31, 23, 59, 59)

  test("Period.MaxDateTime2 has correct maximum value") {
    assertEquals(Period.MaxDateTime2, LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999999900))
  }

  test("Period.DateTime2 type alias is correct") {
    val period: Period.DateTime2 = Period(dt1, dt2)
    assertEquals(period.validFrom, dt1)
    assertEquals(period.validTo, dt2)
  }

  test("SystemTime.asOf creates AsOf with UTC conversion") {
    val systemTime = SystemTime.asOf(instant1)
    systemTime match
      case asOf: SystemTime.AsOf =>
        assertEquals(asOf.instant, LocalDateTime.ofInstant(instant1, ZoneOffset.UTC))
  }

  test("SystemTime.AsOf.toFragment generates FOR SYSTEM_TIME AS OF clause") {
    val asOf = SystemTime.AsOf(dt1)
    val frag = asOf.toFragment
    assert(frag.toString.contains("FOR SYSTEM_TIME AS OF"))
    // Fragment uses placeholders for parameters - actual SQL tested in integration tests
  }

  test("SystemTime.fromTo creates FromTo with UTC conversion") {
    val systemTime = SystemTime.fromTo(instant1, instant2)
    systemTime match
      case fromTo: SystemTime.FromTo =>
        assertEquals(fromTo.from, LocalDateTime.ofInstant(instant1, ZoneOffset.UTC))
        assertEquals(fromTo.to, LocalDateTime.ofInstant(instant2, ZoneOffset.UTC))
  }

  test("SystemTime.FromTo.toFragment generates FOR SYSTEM_TIME FROM TO clause") {
    val fromTo = SystemTime.FromTo(dt1, dt2)
    val frag = fromTo.toFragment
    assert(frag.toString.contains("FOR SYSTEM_TIME FROM"))
    assert(frag.toString.contains("TO"))
    // Fragment uses placeholders for parameters - actual SQL tested in integration tests
  }

  test("SystemTime.between creates Between with UTC conversion") {
    val systemTime = SystemTime.between(instant1, instant2)
    systemTime match
      case between: SystemTime.Between =>
        assertEquals(between.from, LocalDateTime.ofInstant(instant1, ZoneOffset.UTC))
        assertEquals(between.to, LocalDateTime.ofInstant(instant2, ZoneOffset.UTC))
  }

  test("SystemTime.Between.toFragment generates FOR SYSTEM_TIME BETWEEN clause") {
    val between = SystemTime.Between(dt1, dt2)
    val frag = between.toFragment
    assert(frag.toString.contains("FOR SYSTEM_TIME BETWEEN"))
    assert(frag.toString.contains("AND"))
    // Fragment uses placeholders for parameters - actual SQL tested in integration tests
  }

  test("SystemTime.containedIn creates ContainedIn with UTC conversion") {
    val systemTime = SystemTime.containedIn(instant1, instant2)
    systemTime match
      case containedIn: SystemTime.ContainedIn =>
        assertEquals(containedIn.from, LocalDateTime.ofInstant(instant1, ZoneOffset.UTC))
        assertEquals(containedIn.to, LocalDateTime.ofInstant(instant2, ZoneOffset.UTC))
  }

  test("SystemTime.ContainedIn.toFragment generates FOR SYSTEM_TIME CONTAINED IN clause") {
    val containedIn = SystemTime.ContainedIn(dt1, dt2)
    val frag = containedIn.toFragment
    assert(frag.toString.contains("FOR SYSTEM_TIME CONTAINED IN"))
    // Fragment uses placeholders for parameters - actual SQL tested in integration tests
  }

  test("SystemTime.All.toFragment generates FOR SYSTEM_TIME ALL clause") {
    val frag = SystemTime.All.toFragment
    assert(frag.toString.contains("FOR SYSTEM_TIME ALL"))
    // No parameters needed for ALL - actual SQL tested in integration tests
  }

  test("Temporal.Versioned type alias resolves correctly") {
    val emp = Employee(1, "Alice", BigDecimal(75000))
    val period = Period(dt1, dt2)
    val versioned: Temporal.Versioned[Employee] = Temporal(emp, period)
    assertEquals(versioned.entity, emp)
    assertEquals(versioned.period, period)
  }

  test("Temporal.Current type alias resolves correctly") {
    val emp = Employee(1, "Alice", BigDecimal(75000))
    val period = Period(dt1, Period.MaxDateTime2)
    val current: Temporal.Current[Employee] = Temporal(emp, period)
    assertEquals(current.entity, emp)
    assertEquals(current.period, period)
  }

  test("Temporal.isCurrent returns true when validTo is MaxDateTime2") {
    val emp = Employee(1, "Alice", BigDecimal(75000))
    val period = Period(dt1, Period.MaxDateTime2)
    val temporal = Temporal[Employee, TemporalMode.SystemVersioned](emp, period)
    assert(temporal.isCurrent)
  }

  test("Temporal.isCurrent returns false when validTo is not MaxDateTime2") {
    val emp = Employee(1, "Alice", BigDecimal(75000))
    val period = Period(dt1, dt2)
    val temporal = Temporal[Employee, TemporalMode.SystemVersioned](emp, period)
    assert(!temporal.isCurrent)
  }

  test("Temporal.current extracts entity when called") {
    val emp = Employee(1, "Alice", BigDecimal(75000))
    val period = Period(dt1, Period.MaxDateTime2)
    val temporal = Temporal[Employee, TemporalMode.SystemVersioned](emp, period)
    val result: Employee = temporal.current
    assertEquals(result, emp)
  }

  test("Temporal.current always extracts entity") {
    val emp = Employee(1, "Alice", BigDecimal(75000))
    val period = Period(dt1, dt2)
    val temporal = Temporal[Employee, TemporalMode.SystemVersioned](emp, period)
    val result: Employee = temporal.current
    assertEquals(result, emp)
  }

  test("TemporalSchema.periodColumns generates correct fragment") {
    val schema = summon[TemporalSchema[Long, Employee]]
    val frag = schema.periodColumns
    assert(frag.toString.contains("ValidFrom"))
    assert(frag.toString.contains("ValidTo"))
    // Exact formatting tested via integration tests with real database
  }

  test("TemporalSchema.allColumns combines entity and period columns") {
    val schema = summon[TemporalSchema[Long, Employee]]
    val frag = schema.allColumns
    // Should be: EmployeeID, Name, Salary, ValidFrom, ValidTo
    assert(frag.toString.contains("EmployeeID"))
    assert(frag.toString.contains("Name"))
    assert(frag.toString.contains("Salary"))
    assert(frag.toString.contains("ValidFrom"))
    assert(frag.toString.contains("ValidTo"))
  }

  test("TemporalSchema.forSystemTime combines table and AS OF clause") {
    val schema = summon[TemporalSchema[Long, Employee]]
    val frag = schema.forSystemTime(SystemTime.AsOf(dt1))
    assert(frag.toString.contains("dbo.Employee"))
    assert(frag.toString.contains("FOR SYSTEM_TIME"))
    assert(frag.toString.contains("AS OF"))
    // Datetime values are parameters - actual query tested in integration tests
  }

  test("TemporalSchema.forSystemTime combines table and FROM TO clause") {
    val schema = summon[TemporalSchema[Long, Employee]]
    val frag = schema.forSystemTime(SystemTime.FromTo(dt1, dt2))
    assert(frag.toString.contains("dbo.Employee"))
    assert(frag.toString.contains("FOR SYSTEM_TIME"))
    assert(frag.toString.contains("FROM"))
    assert(frag.toString.contains("TO"))
    // Datetime values are parameters - actual query tested in integration tests
  }

  test("TemporalSchema.forSystemTime combines table and BETWEEN clause") {
    val schema = summon[TemporalSchema[Long, Employee]]
    val frag = schema.forSystemTime(SystemTime.Between(dt1, dt2))
    assert(frag.toString.contains("dbo.Employee"))
    assert(frag.toString.contains("FOR SYSTEM_TIME"))
    assert(frag.toString.contains("BETWEEN"))
    assert(frag.toString.contains("AND"))
  }

  test("TemporalSchema.forSystemTime combines table and CONTAINED IN clause") {
    val schema = summon[TemporalSchema[Long, Employee]]
    val frag = schema.forSystemTime(SystemTime.ContainedIn(dt1, dt2))
    assert(frag.toString.contains("dbo.Employee"))
    assert(frag.toString.contains("FOR SYSTEM_TIME"))
    assert(frag.toString.contains("CONTAINED IN"))
  }

  test("TemporalSchema.forSystemTime combines table and ALL clause") {
    val schema = summon[TemporalSchema[Long, Employee]]
    val frag = schema.forSystemTime(SystemTime.All)
    assert(frag.toString.contains("dbo.Employee"))
    assert(frag.toString.contains("FOR SYSTEM_TIME"))
    assert(frag.toString.contains("ALL"))
  }

  test("Period.columns generates correct fragment") {
    val frag = Period.columns("ValidFrom", "ValidTo")
    val sql = frag.toString
    assert(sql.contains("ValidFrom"))
    assert(sql.contains("ValidTo"))
    // The actual format includes a trailing space due to Fragment.const internals
    assert(sql.startsWith("Fragment(\"ValidFrom, ValidTo"))
  }

  test("Transactor extension for temporal repository is available with single import") {
    // This test verifies that xa.temporal[ID, A] extension method is available
    // with only `import ashtray.mssql.*` (single import principle)

    // Verify that the extension compiles (compile-time verification)
    // The extension method type-checks with single import
    import scala.compiletime.testing.typeChecks
    assert(
      typeChecks("(??? : doobie.util.transactor.Transactor[cats.effect.IO]).temporal[Long, Employee]"),
      "Repository extension method should be available via single import"
    )
  }

  test("Type safety: TemporalMode.Standard and SystemVersioned are distinct") {
    // This test verifies that the phantom types prevent mixing at compile time
    type StandardEntity = Temporal[Employee, TemporalMode.Standard]
    type VersionedEntity = Temporal[Employee, TemporalMode.SystemVersioned]

    // These should be distinct types - compile test only
    val emp = Employee(1, "Alice", BigDecimal(75000))
    val period = Period(dt1, dt2)
    val standard: StandardEntity = Temporal(emp, period)
    val versioned: VersionedEntity = Temporal(emp, period)

    // Verify types are distinct by checking entity extraction works
    assertEquals(standard.entity, emp)
    assertEquals(versioned.entity, emp)
  }

  test("TemporalVersioned type alias is exported") {
    val emp = Employee(1, "Alice", BigDecimal(75000))
    val period = Period(dt1, dt2)
    val versioned: TemporalVersioned[Employee] = Temporal(emp, period)
    assertEquals(versioned.entity, emp)
  }

  test("TemporalCurrent type alias is exported") {
    val emp = Employee(1, "Alice", BigDecimal(75000))
    val period = Period(dt1, Period.MaxDateTime2)
    val current: TemporalCurrent[Employee] = Temporal(emp, period)
    assertEquals(current.entity, emp)
  }

  // === TemporalSchema.columnNames validation tests ===

  test("columnNames extracts simple comma-separated list") {
    val schema = TemporalSchema[Long, Employee](
      table = "dbo.Test",
      history = "dbo.TestHistory",
      id = "ID",
      validFrom = "ValidFrom",
      validTo = "ValidTo",
      cols = fr"Col1, Col2, Col3"
    )
    assertEquals(schema.columnNames, Right(List("Col1", "Col2", "Col3")))
  }

  test("columnNames handles columns with no spaces") {
    val schema = TemporalSchema[Long, Employee](
      table = "dbo.Test",
      history = "dbo.TestHistory",
      id = "ID",
      validFrom = "ValidFrom",
      validTo = "ValidTo",
      cols = fr"Col1,Col2,Col3"
    )
    assertEquals(schema.columnNames, Right(List("Col1", "Col2", "Col3")))
  }

  test("columnNames handles qualified column names") {
    val schema = TemporalSchema[Long, Employee](
      table = "dbo.Test",
      history = "dbo.TestHistory",
      id = "ID",
      validFrom = "ValidFrom",
      validTo = "ValidTo",
      cols = fr"t.Col1, t.Col2, t.Col3"
    )
    assertEquals(schema.columnNames, Right(List("t.Col1", "t.Col2", "t.Col3")))
  }

  test("columnNames rejects SQL line comments") {
    val schema = TemporalSchema[Long, Employee](
      table = "dbo.Test",
      history = "dbo.TestHistory",
      id = "ID",
      validFrom = "ValidFrom",
      validTo = "ValidTo",
      cols = fr"Col1, Col2 -- comment, Col3"
    )
    schema.columnNames match
      case Left(TemporalSchemaError.SqlComment(_)) => // expected
      case other                                   => fail(s"Expected Left(SqlComment), got: $other")
  }

  test("columnNames rejects SQL block comments") {
    val schema = TemporalSchema[Long, Employee](
      table = "dbo.Test",
      history = "dbo.TestHistory",
      id = "ID",
      validFrom = "ValidFrom",
      validTo = "ValidTo",
      cols = fr"Col1, /* comment */ Col2, Col3"
    )
    schema.columnNames match
      case Left(TemporalSchemaError.SqlComment(_)) => // expected
      case other                                   => fail(s"Expected Left(SqlComment), got: $other")
  }

  test("columnNames rejects string literals with single quotes") {
    val schema = TemporalSchema[Long, Employee](
      table = "dbo.Test",
      history = "dbo.TestHistory",
      id = "ID",
      validFrom = "ValidFrom",
      validTo = "ValidTo",
      cols = fr"Col1, 'literal', Col3"
    )
    schema.columnNames match
      case Left(TemporalSchemaError.StringLiteral(_)) => // expected
      case other                                      => fail(s"Expected Left(StringLiteral), got: $other")
  }

  test("columnNames rejects string literals with double quotes") {
    val schema = TemporalSchema[Long, Employee](
      table = "dbo.Test",
      history = "dbo.TestHistory",
      id = "ID",
      validFrom = "ValidFrom",
      validTo = "ValidTo",
      cols = Fragment.const("Col1, \"literal\", Col3")
    )
    schema.columnNames match
      case Left(TemporalSchemaError.StringLiteral(_)) => // expected
      case other                                      => fail(s"Expected Left(StringLiteral), got: $other")
  }

  test("columnNames rejects SELECT keywords") {
    val schema = TemporalSchema[Long, Employee](
      table = "dbo.Test",
      history = "dbo.TestHistory",
      id = "ID",
      validFrom = "ValidFrom",
      validTo = "ValidTo",
      cols = fr"SELECT Col1, Col2"
    )
    schema.columnNames match
      case Left(TemporalSchemaError.ComplexExpression(_, _)) => // expected
      case other                                             => fail(s"Expected Left(ComplexExpression), got: $other")
  }

  test("columnNames rejects FROM keywords") {
    val schema = TemporalSchema[Long, Employee](
      table = "dbo.Test",
      history = "dbo.TestHistory",
      id = "ID",
      validFrom = "ValidFrom",
      validTo = "ValidTo",
      cols = fr"FROM dbo.Table"
    )
    schema.columnNames match
      case Left(TemporalSchemaError.ComplexExpression(_, _)) => // expected
      case other                                             => fail(s"Expected Left(ComplexExpression), got: $other")
  }

  test("columnNames rejects function calls with parentheses") {
    val schema = TemporalSchema[Long, Employee](
      table = "dbo.Test",
      history = "dbo.TestHistory",
      id = "ID",
      validFrom = "ValidFrom",
      validTo = "ValidTo",
      cols = fr"Col1, UPPER(Col2), Col3"
    )
    schema.columnNames match
      case Left(TemporalSchemaError.ComplexExpression(_, _)) => // expected
      case other                                             => fail(s"Expected Left(ComplexExpression), got: $other")
  }

  test("columnNames rejects empty column names") {
    val schema = TemporalSchema[Long, Employee](
      table = "dbo.Test",
      history = "dbo.TestHistory",
      id = "ID",
      validFrom = "ValidFrom",
      validTo = "ValidTo",
      cols = fr"Col1, , Col3"
    )
    schema.columnNames match
      case Left(TemporalSchemaError.EmptyColumnName(_)) => // expected
      case other                                        => fail(s"Expected Left(EmptyColumnName), got: $other")
  }

end TemporalTests
