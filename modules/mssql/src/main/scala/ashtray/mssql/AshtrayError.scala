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

import scala.util.control.NoStackTrace

/** Root error type for all errors in the ashtray.mssql library.
  *
  * This sealed trait provides a unified error hierarchy covering all possible failure modes:
  *   - Identifier parsing and validation errors
  *   - Temporal schema validation errors
  *   - Database interaction errors (Meta instances, JDBC)
  *
  * ==Notes==
  * "Errors are values" - No operation in this library throws exceptions as part of its normal API
  * contract. All errors are returned via `Either[AshtrayError, A]` or propagated through effect
  * types using `MonadError[F, AshtrayError]`.
  *
  * The only exceptions are:
  *   - Methods explicitly marked `Unsafe` (parseUnsafe, etc.) for interop with throwing APIs
  *   - Meta instances where doobie's contract expects exceptions
  *   - Compile-time macros where errors are reported via compiler errors
  */
sealed abstract class AshtrayError(message: String) extends Exception(message) with NoStackTrace:
  final override def getMessage: String = message

/** Companion for [[AshtrayError]], organizing error cases by domain. */
object AshtrayError:

  // === Identifier errors ===

  /** Errors related to identifier parsing, validation, and construction. */
  sealed abstract class IdentifierError(message: String) extends AshtrayError(message)

  object IdentifierError:
    /** Null value was supplied where input is required. */
    case object NullInput extends IdentifierError("Input was null")

    /** String length differs from the canonical UUID format. */
    final case class InvalidLength(actual: Int, expected: Int)
        extends IdentifierError(s"Invalid length: expected $expected characters, got $actual")

    /** String structure is malformed (e.g., missing hyphens). */
    final case class InvalidFormat(input: String, reason: String)
        extends IdentifierError(s"Invalid UUID format: $reason (input: '$input')")

    /** Non-hex character encountered in a UUID literal. */
    final case class InvalidCharacter(char: Char, position: Int, input: String)
        extends IdentifierError(s"Invalid character '$char' at position $position (input: '$input')")

    /** Byte array length is not 16 bytes. */
    final case class InvalidByteArrayLength(actual: Int, expected: Int)
        extends IdentifierError(s"Invalid byte array length: expected $expected bytes, got $actual")

    /** Batch size requested for generation was non-positive. */
    final case class InvalidBatchCount(count: Int) extends IdentifierError(s"Batch count must be positive, got $count")

    // CanEqual instances for strict equality checking
    given CanEqual[IdentifierError, IdentifierError] = CanEqual.derived
  end IdentifierError

  // === Temporal schema errors ===

  /** Errors related to temporal schema validation. */
  sealed abstract class TemporalSchemaError(message: String) extends AshtrayError(message)

  object TemporalSchemaError:
    /** Column fragment contains SQL line comment (`--`) or block comment (`/* */`). */
    final case class SqlComment(fragment: String)
        extends TemporalSchemaError(s"Column fragment appears to contain SQL comments: $fragment")

    /** Column fragment contains string literals (single or double quotes). */
    final case class StringLiteral(fragment: String)
        extends TemporalSchemaError(s"Column fragment appears to contain string literals: $fragment")

    /** Column name is empty (consecutive commas or trailing comma). */
    final case class EmptyColumnName(fragment: String)
        extends TemporalSchemaError(s"Empty column name in fragment: $fragment")

    /** Column name contains SQL keywords or complex expressions. */
    final case class ComplexExpression(columnName: String, fragment: String)
        extends TemporalSchemaError(s"Column fragment appears to contain SQL expression: $columnName in $fragment")

    // CanEqual instances for strict equality checking
    given CanEqual[TemporalSchemaError, TemporalSchemaError] = CanEqual.derived
  end TemporalSchemaError

  // === Database interaction errors ===

  /** Errors related to database type mapping (Meta instances). */
  sealed abstract class MetaError(message: String) extends AshtrayError(message)

  object MetaError:
    /** Failed to decode bytes from database into an Identifier. */
    final case class IdentifierDecodeFailure(cause: IdentifierError, columnIndex: Int)
        extends MetaError(s"Failed to decode UNIQUEIDENTIFIER from column $columnIndex: ${cause.getMessage}")

    /** NULL value encountered where identifier was expected (non-nullable column). */
    final case class UnexpectedNull(columnIndex: Int, columnType: String)
        extends MetaError(s"Unexpected NULL in non-nullable $columnType column at index $columnIndex")

    /** Wrapped JDBC exception from database interaction. */
    final case class JdbcFailure(operation: String, cause: Throwable)
        extends MetaError(s"JDBC operation '$operation' failed: ${cause.getMessage}")

    // CanEqual instances for strict equality checking
    given CanEqual[MetaError, MetaError] = CanEqual.derived
  end MetaError

  // Root CanEqual instance for the entire hierarchy
  given CanEqual[AshtrayError, AshtrayError] = CanEqual.derived

end AshtrayError
