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

import scala.annotation.unused
import scala.quoted.*
import scala.util.boundary
import scala.util.boundary.break

/** Compile-time validated identifier literals.
  *
  * Usage: val i = id"00112233-4455-6677-8899-aabbccddeeff" // Identifier val v7 =
  * idv"019012f3-a456-7def-8901-234567890abc" // Identifier.Versioned[V7]
  */
extension (inline sc: StringContext)
  inline def id(@unused inline args: Any*): Identifier = ${ IdentifierLiteral.implIdentifier('sc) }
  inline def idv(@unused inline args: Any*): Identifier.Versioned[Version.V1.type | Version.V4.type | Version.V7.type] =
    ${ IdentifierLiteral.implVersioned('sc) }
  inline def idv1(@unused inline args: Any*): IdentifierV1 = ${ IdentifierLiteral.implVersion1('sc) }
  inline def idv4(@unused inline args: Any*): IdentifierV4 = ${ IdentifierLiteral.implVersion4('sc) }
  inline def idv7(@unused inline args: Any*): IdentifierV7 = ${ IdentifierLiteral.implVersion7('sc) }

private object IdentifierLiteral:

  def implIdentifier(sc: Expr[StringContext])(using Quotes): Expr[Identifier] =
    import quotes.reflect.*

    val parts = sc.valueOrAbort.parts
    if parts.size != 1 then report.errorAndAbort("id interpolator does not support arguments")

    val raw = parts.head
    validate(raw) match
      case Left(msg) => report.errorAndAbort(msg)
      case Right(_)  => buildIdentifierExpr(raw)

  def implVersioned(sc: Expr[StringContext])(using
    Quotes): Expr[Identifier.Versioned[Version.V1.type | Version.V4.type | Version.V7.type]] =
    import quotes.reflect.*

    val parts = sc.valueOrAbort.parts
    if parts.size != 1 then report.errorAndAbort("id interpolator does not support arguments")

    val raw = parts.head
    validate(raw) match
      case Left(msg) => report.errorAndAbort(msg)
      case Right(_)  =>
        val lower = raw.toLowerCase
        val msb = parseHexSegment(lower, 0, 18, skipHyphens = true)
        val lsb = parseHexSegment(lower, 19, 36, skipHyphens = true)
        val ver = ((msb >>> 12) & 0xfL).toInt
        ver match
          case 1 =>
            '{
              Identifier.Versioned
                .unsafeWrap[Version.V1.type](Identifier(${ Expr(msb) }, ${ Expr(lsb) }))
                .asInstanceOf[Identifier.Versioned[Version.V1.type | Version.V4.type | Version.V7.type]]
            }
          case 4 =>
            '{
              Identifier.Versioned
                .unsafeWrap[Version.V4.type](Identifier(${ Expr(msb) }, ${ Expr(lsb) }))
                .asInstanceOf[Identifier.Versioned[Version.V1.type | Version.V4.type | Version.V7.type]]
            }
          case 7 =>
            '{
              Identifier.Versioned
                .unsafeWrap[Version.V7.type](Identifier(${ Expr(msb) }, ${ Expr(lsb) }))
                .asInstanceOf[Identifier.Versioned[Version.V1.type | Version.V4.type | Version.V7.type]]
            }
          case other =>
            report.errorAndAbort(
              s"Identifier literal has unsupported version nibble v$other for typed literal; use id\"...\" instead"
            ) // scalafix:ok
    end match
  end implVersioned

  def implVersion1(sc: Expr[StringContext])(using Quotes): Expr[IdentifierV1] =
    buildSpecific(
      sc,
      expected = 1,
      wrap =
        (msb, lsb) => '{ Identifier.Versioned.unsafeWrap[Version.V1.type](Identifier(${ Expr(msb) }, ${ Expr(lsb) })) })

  def implVersion4(sc: Expr[StringContext])(using Quotes): Expr[IdentifierV4] =
    buildSpecific(
      sc,
      expected = 4,
      wrap =
        (msb, lsb) => '{ Identifier.Versioned.unsafeWrap[Version.V4.type](Identifier(${ Expr(msb) }, ${ Expr(lsb) })) })

  def implVersion7(sc: Expr[StringContext])(using Quotes): Expr[IdentifierV7] =
    buildSpecific(
      sc,
      expected = 7,
      wrap =
        (msb, lsb) => '{ Identifier.Versioned.unsafeWrap[Version.V7.type](Identifier(${ Expr(msb) }, ${ Expr(lsb) })) })

  private def buildSpecific[A](sc: Expr[StringContext], expected: Int, wrap: (Long, Long) => Expr[A])(using
    Quotes): Expr[A] =
    import quotes.reflect.*
    val parts = sc.valueOrAbort.parts
    if parts.size != 1 then report.errorAndAbort("id interpolator does not support arguments")
    val raw = parts.head
    validate(raw) match
      case Left(msg) => report.errorAndAbort(msg)
      case Right(_)  =>
        val lower = raw.toLowerCase
        val msb = parseHexSegment(lower, 0, 18, skipHyphens = true)
        val lsb = parseHexSegment(lower, 19, 36, skipHyphens = true)
        val ver = ((msb >>> 12) & 0xfL).toInt
        if ver != expected then
          report.errorAndAbort(s"Identifier literal has unsupported version nibble v$ver; expected v$expected")
        else wrap(msb, lsb)

  private def validate(s: String): Either[String, Unit] =
    if s == null then Left("Identifier literal was null")
    else if s.length != 36 then Left(s"Invalid length: expected 36, got ${s.length}")
    else if s.charAt(8) != '-' || s.charAt(13) != '-' || s.charAt(18) != '-' || s.charAt(23) != '-' then
      Left("Missing or misplaced hyphens in identifier literal")
    else
      boundary:
        var i = 0
        while i < 36 do
          val c = s.charAt(i)
          val ok =
            c == '-' ||
              (c >= '0' && c <= '9') ||
              (c >= 'a' && c <= 'f') ||
              (c >= 'A' && c <= 'F')
          if !ok then break(Left(s"Invalid character '$c' at position $i in identifier literal"))
          i += 1
        Right(()) // scalafix:ok

  private def buildIdentifierExpr(s: String)(using Quotes): Expr[Identifier] =
    val lower = s.toLowerCase
    val msb = parseHexSegment(lower, 0, 18, skipHyphens = true)
    val lsb = parseHexSegment(lower, 19, 36, skipHyphens = true)
    '{ Identifier(${ Expr(msb) }, ${ Expr(lsb) }) }

  private def parseHexSegment(s: String, start: Int, end: Int, skipHyphens: Boolean): Long =
    var acc = 0L
    var i = start
    while i < end do
      val c = s.charAt(i)
      if skipHyphens && c == '-' then ()
      else
        val n = hexToNibble(c)
        acc = (acc << 4) | n
      i += 1
    acc // scalafix:ok

  private inline def hexToNibble(c: Char): Int =
    if c >= '0' && c <= '9' then c - '0'
    else if c >= 'a' && c <= 'f' then c - 'a' + 10
    else if c >= 'A' && c <= 'F' then c - 'A' + 10
    else -1
end IdentifierLiteral
