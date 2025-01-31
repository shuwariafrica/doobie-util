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
package africa.shuwari.doobie.mssql

import cats.data.NonEmptyList
import doobie.enumerated.JdbcType
import doobie.util.meta.Meta
import microsoft.sql.DateTimeOffset

import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID

trait MetaInstances:

  given Meta[UUID] =
    inline def toBytes(v: Long): Array[Byte] =
      val bytes = new Array[Byte](8)
      bytes(0) = (v >>> 56).toByte
      bytes(1) = (v >>> 48).toByte
      bytes(2) = (v >>> 40).toByte
      bytes(3) = (v >>> 32).toByte
      bytes(4) = (v >>> 24).toByte
      bytes(5) = (v >>> 16).toByte
      bytes(6) = (v >>> 8).toByte
      bytes(7) = v.toByte
      bytes

    inline def fromBytes(bytes: Array[Byte]): Long =
      (bytes(0) & 0xffL) << 56 |
        (bytes(1) & 0xffL) << 48 |
        (bytes(2) & 0xffL) << 40 |
        (bytes(3) & 0xffL) << 32 |
        (bytes(4) & 0xffL) << 24 |
        (bytes(5) & 0xffL) << 16 |
        (bytes(6) & 0xffL) << 8 |
        (bytes(7) & 0xffL)

    inline def convertMostSignificantBits(array: Array[Byte]): Array[Byte] =
      def swap(a: Int, b: Int): Unit =
        val ab = array(a)
        array.update(a, array(b))
        array.update(b, ab)
      swap(0, 3)
      swap(1, 2)
      swap(4, 5)
      swap(6, 7)
      array

    inline def toByteArray(v: UUID): Array[Byte] =
      convertMostSignificantBits(toBytes(v.getMostSignificantBits)) ++ toBytes(v.getLeastSignificantBits)

    inline def toUUID(v: Array[Byte]): UUID =
      new UUID(fromBytes(convertMostSignificantBits(v.slice(0, 8))), fromBytes(v.slice(8, 16)))

    Meta.Advanced.one[UUID](
      JdbcType.VarBinary,
      NonEmptyList.one("UNIQUEIDENTIFIER"),
      (resultSet, index) => toUUID(resultSet.getBytes(index)),
      (statement, index, value) => statement.setBytes(index, toByteArray(value)),
      (resultSet, index, value) => resultSet.updateBytes(index, toByteArray(value))
    )
  end given

  given Meta[DateTimeOffset] = Meta.Advanced.other[DateTimeOffset]("DATETIMEOFFSET")

  given Meta[OffsetDateTime] =
    Meta.Advanced.one(
      JdbcType.MsSqlDateTimeOffset,
      NonEmptyList.one("DATETIMEOFFSET"),
      (resultSet: ResultSet, index: Int) => resultSet.getObject(index, classOf[OffsetDateTime]),
      (statement: PreparedStatement, index: Int, value: OffsetDateTime) => statement.setObject(index, value),
      (resultSet: ResultSet, index: Int, value: OffsetDateTime) => resultSet.updateObject(index, value)
    )

  given Meta[LocalDateTime] =
    Meta.Advanced.one(
      JdbcType.Timestamp,
      NonEmptyList.one("DATETIME2"),
      (resultSet: ResultSet, index: Int) => resultSet.getObject(index, classOf[LocalDateTime]),
      (statement: PreparedStatement, index: Int, value: LocalDateTime) => statement.setObject(index, value),
      (resultSet: ResultSet, index: Int, value: LocalDateTime) => resultSet.updateObject(index, value)
    )
end MetaInstances
