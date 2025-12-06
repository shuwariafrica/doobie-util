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
package ashtray.mssql

import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField

/** Date-time utilities for SQL Server compatibility. */
object time:

  /** Predefined formatters matching SQL Server string formats. */
  object formatter:
    /** `DATETIME2` formatter using strict parsing. */
    val dateTime2: DateTimeFormatter =
      new DateTimeFormatterBuilder().parseStrict
        .append(DateTimeFormatter.ISO_LOCAL_DATE)
        .appendLiteral(' ')
        .appendPattern("HH:mm:ss")
        .appendFraction(ChronoField.NANO_OF_SECOND, 0, 7, true)
        .toFormatter

    /** `DATETIMEOFFSET` formatter using strict parsing with offset. */
    val datetimeoffset: DateTimeFormatter =
      new DateTimeFormatterBuilder().parseStrict
        .append(DateTimeFormatter.ISO_LOCAL_DATE)
        .appendLiteral(' ')
        .appendPattern("HH:mm:ss")
        .appendFraction(ChronoField.NANO_OF_SECOND, 0, 7, true)
        .appendLiteral(' ')
        .appendOffset("+HH:MM", "+00:00")
        .toFormatter
