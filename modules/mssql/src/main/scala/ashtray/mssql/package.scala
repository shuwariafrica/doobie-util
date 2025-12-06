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
package ashtray

/** Public entrypoint; mixing in [[ashtray.mssql.MetaInstances]] for simple importing.
  *
  * Exposes convenient aliases:
  *   - [[VersionV1]], [[VersionV4]], [[VersionV7]] for version singletons.
  *   - [[IdentifierV1]], [[IdentifierV4]], [[IdentifierV7]] for phantom-typed identifiers.
  *   - Literal helpers [[identifier_literal.idv1]], [[identifier_literal.idv4]],
  *     [[identifier_literal.idv7]] yield narrowed types at compile time.
  */
package object mssql extends MetaInstances:

  // Version type aliases for convenience
  type VersionV1 = Version.V1.type
  type VersionV4 = Version.V4.type
  type VersionV7 = Version.V7.type

  // Identifier aliases for phantom-typed identifiers
  type IdentifierV1 = Identifier.Versioned[VersionV1]
  type IdentifierV4 = Identifier.Versioned[VersionV4]
  type IdentifierV7 = Identifier.Versioned[VersionV7]
