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

import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID

import cats.data.NonEmptyList

import doobie.enumerated.JdbcType
import doobie.util.meta.Meta

import microsoft.sql.DateTimeOffset

import ashtray.mssql.Identifier.*

/** Doobie `Meta` instances for SQL Server types used by this module. */
transparent trait MetaInstances:

  // Identifier and versioned wrappers
  given Meta[Identifier] =
    Meta.Advanced.one(
      JdbcType.VarBinary,
      NonEmptyList.one("UNIQUEIDENTIFIER"),
      (rs, idx) =>
        val bytes = rs.getBytes(idx)
        if bytes == null then null.asInstanceOf[Identifier] // scalafix:ok
        else
          Identifier.fromSqlServerBytes(bytes) match
            case Right(id) => id
            case Left(err) => throw err, // scalafix:ok
      (ps, idx, value) => ps.setBytes(idx, value.toSqlServerBytes),
      (rs, idx, value) => rs.updateBytes(idx, value.toSqlServerBytes)
    )

  given [V <: Version]: Meta[Identifier.Versioned[V]] =
    summon[Meta[Identifier]].imap(Identifier.Versioned.unsafeWrap[V])(_.untyped)

  given Meta[UUID] =
    Meta.Advanced.one(
      JdbcType.VarBinary,
      NonEmptyList.one("UNIQUEIDENTIFIER"),
      (resultSet, index) =>
        Identifier.fromSqlServerBytes(resultSet.getBytes(index)) match
          case Right(id) => id.toJava
          case Left(err) => throw err,
      (statement, index, value) => statement.setBytes(index, Identifier.fromJava(value).toSqlServerBytes),
      (resultSet, index, value) => resultSet.updateBytes(index, Identifier.fromJava(value).toSqlServerBytes)
    ) // scalafix:ok

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
