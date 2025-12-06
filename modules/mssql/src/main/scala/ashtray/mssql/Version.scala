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

import cats.Eq
import cats.Show

/** UUID version marker used for phantom typing and display. */
sealed trait Version:
  /** Numeric version value. */
  def value: Int

/** Companion for [[Version]] providing standard versions and instances. */
object Version:
  /** Version 1 (time-based). */
  case object V1 extends Version:
    override val value: Int = 1

  /** Version 4 (random). */
  case object V4 extends Version:
    override val value: Int = 4

  /** Version 7 (time-ordered). */
  case object V7 extends Version:
    override val value: Int = 7

  /** Unrecognised version nibble. */
  final case class Unknown(value: Int) extends Version

  given Eq[Version] = Eq.fromUniversalEquals
  given Show[Version] = Show.show {
    case V1         => "v1"
    case V4         => "v4"
    case V7         => "v7"
    case Unknown(v) => s"v$v"
  }

  given CanEqual[Version, Version] = CanEqual.derived
end Version
