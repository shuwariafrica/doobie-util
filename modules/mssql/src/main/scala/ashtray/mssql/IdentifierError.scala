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

/** Error algebra for identifier parsing and validation. */
sealed abstract class IdentifierError(message: String) extends Exception(message) with NoStackTrace:
  final override def getMessage: String = message

/** Companion for [[IdentifierError]], listing all error cases. */
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
