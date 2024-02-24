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
package africa.shuwari.doobie.mssql

import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder

object time:

  object formatter:
    val dateTime2: DateTimeFormatter =
      new DateTimeFormatterBuilder().parseStrict
        .append(DateTimeFormatter.ISO_LOCAL_DATE)
        .appendLiteral(' ')
        .append(DateTimeFormatter.ISO_LOCAL_TIME)
        .toFormatter

    val datetimeoffset: DateTimeFormatter =
      new DateTimeFormatterBuilder()
        .append(dateTime2)
        .appendLiteral(' ')
        .appendOffset("+HH:MM", "+00:00")
        .toFormatter
