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
package africa.shuwari.doobie.mssql.test.time

import java.time.LocalDateTime
import java.time.Month
import java.time.OffsetDateTime
import java.time.ZoneOffset

import africa.shuwari.doobie.mssql.time.formatter

class test_formatters extends munit.FunSuite:

  val datetime2String = "2024-02-23 23:33:01.7864013"
  val localDateTime = LocalDateTime.of(2024, Month.FEBRUARY, 23, 23, 33, 1, 786401300)

  val datetimeoffsetString = datetime2String + " +03:00"
  val offsetDateTime = localDateTime.atOffset(ZoneOffset.ofHoursMinutes(3, 0))

  test("'formatter.datetime2' can correctly parse SQL Server DATETIME2 formatted values") {
    assertEquals(LocalDateTime.parse(datetime2String, formatter.dateTime2), localDateTime)
  }
  test("'formatter.datetime2' can correctly show LocalDateTime values") {
    assertEquals(localDateTime.format(formatter.dateTime2), datetime2String)
  }
  test("'formatter.datetimeoffset' can correctly parse SQL Server DATETIMEOFFSET formatted values") {
    assertEquals(OffsetDateTime.parse(datetimeoffsetString, formatter.datetimeoffset), offsetDateTime)
  }
  test("'formatter.datetimeoffset' can correctly show OffsetDateTime values") {
    assertEquals(offsetDateTime.format(formatter.datetimeoffset), datetimeoffsetString)
  }
