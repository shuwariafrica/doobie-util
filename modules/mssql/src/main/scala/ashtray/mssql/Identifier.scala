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

import java.time.Instant
import java.util.UUID

import cats.Eq
import cats.Hash
import cats.Order
import cats.Show

/** A zero-cost wrapper for SQL Server `UNIQUEIDENTIFIER` values using standard UUID bit layout.
  *
  * ==Overview==
  * `Identifier` is an opaque type over `(Long, Long)` representing the 128 bits of a UUID.
  * Internally, bits are stored in big-endian (RFC 9562) order; byte-swapping to SQL Server's
  * mixed-endian wire format occurs only at the database boundary via
  * [[Identifier.toSqlServerBytes]] and [[Identifier.fromSqlServerBytes]].
  *
  * This design enables:
  *   - Zero allocation for storage and simple accessors
  *   - Interoperability with `java.util.UUID` via [[Identifier.toJava]] / [[Identifier.fromJava]]
  *   - Correct semantics for version and variant bit extraction
  *
  * ==Versioned identifiers==
  * `Identifier` is ''untyped''—the UUID version is unknown at compile time. When the version
  * matters (e.g. extracting a V7 timestamp), narrow to [[Identifier.Versioned Versioned]][V] using
  * [[Identifier.asV1 asV1]], [[Identifier.asV4 asV4]], or [[Identifier.asV7 asV7]].
  *
  * See [[Identifier$.Versioned$ Versioned]] companion for architecture details and usage examples.
  *
  * ==Quick usage==
  * {{{
  * import ashtray.mssql.*
  *
  * // Parse from string
  * val parsed: Either[AshtrayError, Identifier] =
  *   Identifier.parse("550e8400-e29b-41d4-a716-446655440000")
  *
  * // Compile-time literal
  * val literal: Identifier = id"550e8400-e29b-41d4-a716-446655440000"
  *
  * // Narrow to typed V7 for timestamp access
  * literal.asV7.foreach { v7 =>
  *   println(s"Timestamp: \${v7.timestampMillis}")
  * }
  *
  * // Convert to/from java.util.UUID
  * val uuid: java.util.UUID = literal.toJava
  * val back: Identifier     = Identifier.fromJava(uuid)
  * }}}
  *
  * @see [[Identifier$]] companion for construction, parsing, and type class instances
  * @see [[Identifier$.Versioned Versioned]] for phantom-typed identifiers with version proof
  * @see [[IdentifierGen]] for effectful generation
  */
opaque type Identifier = (Long, Long)

/** Companion for [[Identifier]], providing construction, parsing, typed views, and type class
  * instances.
  *
  * ==Construction==
  * {{{
  * // From bit components
  * val id = Identifier(msbLong, lsbLong)
  *
  * // From java.util.UUID (zero-cost)
  * val fromUuid = Identifier.fromJava(uuid)
  *
  * // From bytes (validates length)
  * val fromBytes: Either[AshtrayError, Identifier] = Identifier.fromBytes(array)
  *
  * // From SQL Server wire bytes (swaps endianness)
  * val fromWire: Either[AshtrayError, Identifier] = Identifier.fromSqlServerBytes(array)
  * }}}
  *
  * ==Parsing==
  * {{{
  * // Safe parse returning Either
  * Identifier.parse("550e8400-e29b-41d4-a716-446655440000")
  *
  * // Option variant
  * Identifier.parseOption("550e8400-e29b-41d4-a716-446655440000")
  *
  * // Unsafe (throws on invalid input)
  * Identifier.parseUnsafe("550e8400-e29b-41d4-a716-446655440000")
  * }}}
  *
  * ==Type class instances==
  * Provides `Eq`, `Hash`, `Order`, `Show`, and `CanEqual` for [[Identifier]]. Instances for
  * [[Versioned]] are available via [[Versioned$]].
  *
  * ==Sentinel values==
  *   - [[nil]] – all zero bits
  *   - [[maximum]] – all one bits
  *
  * @see [[Identifier]] opaque type for overview and usage
  * @see [[Versioned$]] for phantom-typed identifiers
  */
object Identifier:

  /** Nil identifier (all zero bits). Useful as a sentinel or placeholder value. */
  val nil: Identifier = (0L, 0L)

  /** Maximum identifier (all one bits). Useful for range upper bounds or max comparisons. */
  val maximum: Identifier = (-1L, -1L)

  /** Phantom-typed identifier carrying a compile-time version witness.
    *
    * `Versioned[V]` is an opaque type over [[Identifier]] that statically encodes the UUID version
    * as a type parameter. This enables version-specific extension methods (e.g. `timestampMillis`
    * for V7) to be available only when the version is proven at compile time.
    *
    * At runtime, `Versioned[V]` erases to the same `(Long, Long)` representation—zero overhead.
    *
    * @tparam V the UUID version singleton type (e.g. `Version.V7.type`)
    * @see [[Versioned$]] companion for construction, unwrapping, and type class instances
    */
  opaque type Versioned[V <: Version] = Identifier

  /** Companion for [[Versioned]], providing construction, unwrapping, and type class instances.
    *
    * ==Overview==
    * A `Versioned[V]` is a phantom-typed wrapper over [[Identifier]] that carries compile-time
    * proof of the UUID version. This enables version-specific operations (e.g. timestamp extraction
    * for V7) to be available ''only'' when the version is statically known, eliminating runtime
    * checks and preventing misuse.
    *
    * ==Architecture==
    * {{{
    *   Identifier              // Untyped – version unknown at compile time
    *       │
    *       └─► Versioned[V]    // Phantom-typed – version V proven at compile time
    *               │
    *               ├─► Versioned[Version.V1.type]   // V1-specific extensions available
    *               ├─► Versioned[Version.V4.type]   // (no additional extensions)
    *               └─► Versioned[Version.V7.type]   // V7-specific extensions available
    * }}}
    *
    * At runtime `Versioned[V]` erases to the same `(Long, Long)` representation as
    * `Identifier`—there is zero overhead.
    *
    * ==Obtaining a Versioned==
    * '''From generation''' (statically typed):
    * {{{
    * import ashtray.mssql.*
    * import cats.effect.IO
    *
    * val v7: IO[IdentifierV7] = generate[IO, VersionV7]
    * v7.map(_.timestampMillis)   // compiles – V7 has timestamp
    * }}}
    *
    * '''From parsing''' (runtime narrowing):
    * {{{
    * val parsed: Either[IdentifierError, Identifier] =
    *   Identifier.parse("019012f3-a456-7def-8901-234567890abc")
    *
    * parsed.toOption.flatMap(_.asV7) match
    *   case Some(v7) => println(s"V7 timestamp: ${v7.timestampMillis}")
    *   case None     => println("Not a V7 identifier")
    * }}}
    *
    * '''From literals''' (compile-time validated):
    * {{{
    * val v7: IdentifierV7 = idv7"019012f3-a456-7def-8901-234567890abc"
    * val v4: IdentifierV4 = idv4"550e8400-e29b-41d4-a716-446655440000"
    * }}}
    *
    * ==Extracting the untyped Identifier==
    * Use the `.untyped` extension when interoperating with APIs that expect `Identifier`:
    * {{{
    * val typed: IdentifierV7 = ???
    * val untyped: Identifier = typed.untyped
    * }}}
    *
    * @see [[Identifier.asV1]], [[Identifier.asV4]], [[Identifier.asV7]] for runtime narrowing
    * @see [[IdentifierGen]] for effectful generation of versioned identifiers
    */
  object Versioned:
    /** Unsafely wrap an [[Identifier]] with a version phantom. Caller must ensure the bits match
      * the version.
      */
    private[mssql] inline def unsafeWrap[V <: Version](id: Identifier): Versioned[V] = id

    extension [V <: Version](id: Versioned[V]) inline def untyped: Identifier = id

    // scalafix:off
    given [V <: Version]: Order[Versioned[V]] = Identifier.given_Order_Identifier.asInstanceOf[Order[Versioned[V]]]
    given [V <: Version]: Hash[Versioned[V]] = Identifier.given_Hash_Identifier.asInstanceOf[Hash[Versioned[V]]]
    given [V <: Version]: Eq[Versioned[V]] = Identifier.given_Eq_Identifier.asInstanceOf[Eq[Versioned[V]]]
    // scalafix:on
    given [V <: Version]: Show[Versioned[V]] = Show.show(v => formatIdentifier(v))
    given [V <: Version]: CanEqual[Versioned[V], Versioned[V]] = CanEqual.derived
  end Versioned

  // === Helpers ===
  private inline def versionBits(msb: Long): Int = ((msb >>> 12) & 0x0fL).toInt
  private inline def variantBits(lsb: Long): Int = ((lsb >>> 62) & 0x03L).toInt
  private inline def v7Timestamp(msb: Long): Long = (msb >>> 16) & 0xffffffffffffL

  private val HexChars: Array[Char] = "0123456789abcdef".toCharArray

  // === Construction ===
  /** Construct an [[Identifier]] from UUID bit components (standard UUID layout). */
  inline def apply(mostSignificant: Long, leastSignificant: Long): Identifier = (mostSignificant, leastSignificant)

  /** Wrap a `java.util.UUID` as an [[Identifier]] without allocation. */
  def fromJava(uuid: UUID): Identifier = (uuid.getMostSignificantBits, uuid.getLeastSignificantBits)

  /** Build an [[Identifier]] from a 16-byte big-endian array, validating length. */
  def fromBytes(bytes: Array[Byte]): Either[AshtrayError, Identifier] =
    if bytes == null then Left(AshtrayError.IdentifierError.NullInput)
    else if bytes.length != 16 then Left(AshtrayError.IdentifierError.InvalidByteArrayLength(bytes.length, 16))
    else
      val msb = readLong(bytes, 0)
      val lsb = readLong(bytes, 8)
      Right((msb, lsb)) // scalafix:ok

  /** Unsafely build from bytes, throwing on invalid input. */
  def fromBytesUnsafe(bytes: Array[Byte]): Identifier = fromBytes(bytes) match
    case Right(id) => id
    case Left(err) => throw err // scalafix:ok

  /** Build from SQL Server mixed-endian bytes, validating length. */
  def fromSqlServerBytes(bytes: Array[Byte]): Either[AshtrayError, Identifier] =
    if bytes == null then Left(AshtrayError.IdentifierError.NullInput)
    else if bytes.length != 16 then Left(AshtrayError.IdentifierError.InvalidByteArrayLength(bytes.length, 16))
    else
      val swapped = swapSqlServerBytes(bytes.clone())
      val msb = readLong(swapped, 0)
      val lsb = readLong(swapped, 8)
      Right((msb, lsb)) // scalafix:ok

  /** Unsafely build from SQL Server bytes, throwing on invalid input. */
  def fromSqlServerBytesUnsafe(bytes: Array[Byte]): Identifier = fromSqlServerBytes(bytes) match
    case Right(id) => id
    case Left(err) => throw err // scalafix:ok

  /** Parse a canonical UUID string into an [[Identifier]], returning a validation error on failure. */
  def parse(value: String): Either[AshtrayError, Identifier] =
    validateAndParseBits(value)

  /** Parse a canonical UUID string into an [[Identifier]], returning `None` on failure. */
  inline def parseOption(value: String): Option[Identifier] = parse(value).toOption

  /** Parse unsafely, throwing on invalid input. */
  def parseUnsafe(value: String): Identifier = parse(value) match
    case Right(id) => id
    case Left(err) => throw err // scalafix:ok

  given Eq[Identifier] = Eq.fromUniversalEquals
  given Hash[Identifier] = Hash.fromUniversalHashCode
  given Order[Identifier] with
    override def compare(x: Identifier, y: Identifier): Int =
      val first = java.lang.Long.compare(x._1, y._1)
      if first != 0 then first else java.lang.Long.compare(x._2, y._2)
  given Show[Identifier] = Show.show(formatIdentifier)
  given CanEqual[Identifier, Identifier] = CanEqual.derived

  /** Render a canonical lowercase UUID string. */
  def render(id: Identifier): String = formatIdentifier(id)

  // === Extensions ===
  extension (id: Identifier)
    /** Most significant 64 bits of the UUID (time fields and version). */
    inline def mostSignificant: Long = id._1

    /** Least significant 64 bits of the UUID (variant, clock sequence, node). */
    inline def leastSignificant: Long = id._2

    /** Variant field as defined by RFC 9562. */
    def variant: Int = variantBits(id._2)

    /** UUID version decoded from bits. */
    def version: Version =
      versionBits(id._1) match
        case 1     => Version.V1
        case 4     => Version.V4
        case 7     => Version.V7
        case other => Version.Unknown(other)

    /** Attempt to narrow to a version 1 identifier when bits match. */
    def asV1: Option[Versioned[Version.V1.type]] =
      if version == Version.V1 then Some(Versioned.unsafeWrap(id)) else None

    /** Attempt to narrow to a version 4 identifier when bits match. */
    def asV4: Option[Versioned[Version.V4.type]] =
      if version == Version.V4 then Some(Versioned.unsafeWrap(id)) else None

    /** Attempt to narrow to a version 7 identifier when bits match. */
    def asV7: Option[Versioned[Version.V7.type]] =
      if version == Version.V7 then Some(Versioned.unsafeWrap(id)) else None

    /** Convert to `java.util.UUID` for interop. */
    def toJava: UUID = new UUID(id._1, id._2)

    /** Encode as a 16-byte big-endian array. */
    def toBytes: Array[Byte] = writeLongs(id._1, id._2)

    /** Encode as SQL Server mixed-endian bytes suitable for `UNIQUEIDENTIFIER`. */
    def toSqlServerBytes: Array[Byte] = encodeSqlServer(id)
  end extension

  extension (id: Versioned[Version.V7.type])
    /** Timestamp in milliseconds since Unix epoch encoded in a version 7 identifier. */
    def timestampMillis: Long = v7Timestamp(id._1)

    /** Timestamp as `Instant` for a version 7 identifier. */
    def instant: Instant = Instant.ofEpochMilli(timestampMillis)

  extension (id: Versioned[Version.V1.type])
    /** Timestamp for a version 1 identifier (100-nanosecond intervals since 1582-10-15). */
    def timestamp: Long =
      val msb = id._1
      val low = (msb >>> 32) & 0xffffffffL
      val mid = (msb >>> 16) & 0xffffL
      val high = msb & 0x0fffL
      (high << 48) | (mid << 32) | low

    /** Clock sequence component of a version 1 identifier. */
    def clockSequence: Int = ((id._2 >>> 48) & 0x3fffL).toInt

    /** Node component of a version 1 identifier. */
    def node: Long = id._2 & 0xffffffffffffL

  // === Internal: validation and formatting ===
  private def validateAndParseBits(s: String): Either[AshtrayError, Identifier] =
    if s == null then Left(AshtrayError.IdentifierError.NullInput)
    else if s.length != 36 then Left(AshtrayError.IdentifierError.InvalidLength(s.length, 36))
    else if s.charAt(8) != '-' || s.charAt(13) != '-' || s.charAt(18) != '-' || s.charAt(23) != '-' then
      Left(AshtrayError.IdentifierError.InvalidFormat(s, "Missing or misplaced hyphens"))
    else
      var msb = 0L
      var lsb = 0L
      var error: AshtrayError.IdentifierError | Null = null

      val (msb1, err1) = hexToBits(s, 0, 8, 0L, null)
      msb = msb1
      error = err1

      if error eq null then
        val (msb2, err2) = hexToBits(s, 9, 13, msb, null)
        msb = msb2
        error = err2

      if error eq null then
        val (msb3, err3) = hexToBits(s, 14, 18, msb, null)
        msb = msb3
        error = err3

      if error eq null then
        val (lsb1, err4) = hexToBits(s, 19, 23, 0L, null)
        lsb = lsb1
        error = err4

      if error eq null then
        val (lsb2, err5) = hexToBits(s, 24, 36, lsb, null)
        lsb = lsb2
        error = err5

      if error ne null then Left(error.nn) else Right((msb, lsb)) // scalafix:ok

  private inline def hexToBits(
    s: String,
    startIdx: Int,
    endIdx: Int,
    acc: Long,
    error: AshtrayError.IdentifierError | Null): (Long, AshtrayError.IdentifierError | Null) =
    var result = acc
    var err = error
    var i = startIdx
    while i < endIdx && (err eq null) do
      val n = hexToNibble(s.charAt(i))
      if n < 0 then err = AshtrayError.IdentifierError.InvalidCharacter(s.charAt(i), i, s)
      else result = (result << 4) | n
      i += 1
    (result, err) // scalafix:ok

  private inline def hexToNibble(c: Char): Int =
    if c >= '0' && c <= '9' then c - '0'
    else if c >= 'a' && c <= 'f' then c - 'a' + 10
    else if c >= 'A' && c <= 'F' then c - 'A' + 10
    else -1

  private def formatIdentifier(id: Identifier): String = bitsToString(id._1, id._2)

  private def bitsToString(msb: Long, lsb: Long): String =
    val chars = new Array[Char](36)
    bitsToHex(chars, 0, 8, msb, 60)
    chars(8) = '-'
    bitsToHex(chars, 9, 13, msb, 28)
    chars(13) = '-'
    bitsToHex(chars, 14, 18, msb, 12)
    chars(18) = '-'
    bitsToHex(chars, 19, 23, lsb, 60)
    chars(23) = '-'
    bitsToHex(chars, 24, 36, lsb, 44)
    new String(chars)

  private inline def bitsToHex(chars: Array[Char], startIdx: Int, endIdx: Int, bits: Long, initialShift: Int): Unit =
    var i = startIdx
    var shift = initialShift
    while i < endIdx do
      chars(i) = HexChars(((bits >>> shift) & 0xf).toInt)
      shift -= 4
      i += 1 // scalafix:ok

  private inline def writeLongs(msb: Long, lsb: Long): Array[Byte] =
    val bytes = new Array[Byte](16)
    writeLong(msb, bytes, 0)
    writeLong(lsb, bytes, 8)
    bytes

  private inline def writeLong(value: Long, array: Array[Byte], offset: Int): Unit =
    var i = 0
    while i < 8 do
      array(offset + i) = (value >>> (56 - i * 8)).toByte
      i += 1 // scalafix:ok

  private inline def readLong(bytes: Array[Byte], offset: Int): Long =
    var result = 0L
    var i = 0
    while i < 8 do
      result = (result << 8) | (bytes(offset + i) & 0xffL)
      i += 1
    result // scalafix:ok

  private inline def encodeSqlServer(id: Identifier): Array[Byte] =
    val bytes = writeLongs(id._1, id._2)
    swapSqlServerBytes(bytes)

  private inline def swapSqlServerBytes(bytes: Array[Byte]): Array[Byte] =
    def swap(a: Int, b: Int): Unit =
      val tmp = bytes(a)
      bytes(a) = bytes(b)
      bytes(b) = tmp
    swap(0, 3)
    swap(1, 2)
    swap(4, 5)
    swap(6, 7)
    bytes
end Identifier
