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

import cats.MonadThrow
import cats.syntax.all.*

import cats.effect.kernel.Clock
import cats.effect.std.SecureRandom

/** Generate a single identifier for the requested version using the in-scope [[IdentifierGen]]. */
inline def generate[F[_], V <: Version](using G: IdentifierGen[F, V]): F[Identifier.Versioned[V]] =
  G.generate

/** Generate a batch of identifiers for the requested version using the in-scope [[IdentifierGen]]. */
inline def generateBatch[F[_]: MonadThrow, V <: Version](count: Int)(using
  G: IdentifierGen[F, V]): F[Vector[Identifier.Versioned[V]]] =
  G.generateBatch(count)

/** Effectful generator for identifiers, parameterised by UUID version. */
trait IdentifierGen[F[_], V <: Version]:
  /** Generate a single identifier. */
  def generate: F[Identifier.Versioned[V]]

  /** Generate a batch of identifiers; fails when `count` is non-positive. */
  def generateBatch(count: Int)(using M: MonadThrow[F]): F[Vector[Identifier.Versioned[V]]] =
    if count <= 0 then M.raiseError(IdentifierError.InvalidBatchCount(count))
    else Vector.fill(count)(generate).sequence

object IdentifierGen:

  /** Summon and use an [[IdentifierGen]] to create one identifier. */
  inline def generate[F[_], V <: Version](using G: IdentifierGen[F, V]): F[Identifier.Versioned[V]] = G.generate

  /** Summon and use an [[IdentifierGen]] to create a batch of identifiers. */
  inline def generateBatch[F[_]: MonadThrow, V <: Version](count: Int)(using
    G: IdentifierGen[F, V]): F[Vector[Identifier.Versioned[V]]] =
    G.generateBatch(count)

  /** Default version 7 generator using `Clock[F]` and `SecureRandom[F]`. */
  given v7[F[_]: Clock: SecureRandom: MonadThrow]: IdentifierGen[F, Version.V7.type] =
    new IdentifierGen[F, Version.V7.type]:
      private val VersionBits: Long = 0x7000L
      private val VariantMask: Long = 0x3fffffffffffffffL
      private val VariantBits: Long = 0x8000000000000000L
      private val RandomMask12: Long = 0xfffL

      override def generate: F[Identifier.Versioned[Version.V7.type]] =
        for
          now <- Clock[F].realTime
          r1 <- SecureRandom[F].nextLong
          r2 <- SecureRandom[F].nextLong
        yield
          val millis = now.toMillis & 0xffffffffffffL
          val msb = (millis << 16) | VersionBits | (r1 & RandomMask12)
          val lsb = (r2 & VariantMask) | VariantBits
          Identifier.Versioned.unsafeWrap(Identifier(msb, lsb))

  /** Default version 4 generator using `SecureRandom[F]`. */
  given v4[F[_]: SecureRandom: MonadThrow]: IdentifierGen[F, Version.V4.type] = new IdentifierGen[F, Version.V4.type]:
    private val VersionBits: Long = 0x0000000000004000L
    private val VariantMask: Long = 0x3fffffffffffffffL
    private val VariantBits: Long = 0x8000000000000000L
    private val ClearMask: Long = 0xffffffffffff0fffL

    override def generate: F[Identifier.Versioned[Version.V4.type]] =
      for
        r1 <- SecureRandom[F].nextLong
        r2 <- SecureRandom[F].nextLong
      yield
        val msb = (r1 & ClearMask) | VersionBits
        val lsb = (r2 & VariantMask) | VariantBits
        Identifier.Versioned.unsafeWrap(Identifier(msb, lsb))
end IdentifierGen
