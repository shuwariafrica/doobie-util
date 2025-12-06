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

/** Marker for temporal capability. Sealed to prevent user extension.
  *
  * This trait provides compile-time discrimination between standard (non-temporal) entities and
  * system-versioned temporal entities. By encoding the temporal mode as a phantom type parameter,
  * we enable mode-specific extension methods while maintaining zero runtime cost.
  *
  * ==Design rationale==
  * Temporal tables in SQL Server have fundamentally different query capabilities:
  *   - '''Standard''' entities support only current-state queries
  *   - '''SystemVersioned''' entities support historical queries via `FOR SYSTEM_TIME` clauses
  *
  * This distinction is encoded in the type system, preventing:
  *   - Temporal queries on non-temporal tables (compile-time error)
  *   - Accidental mixing of temporal and non-temporal query logic
  *   - Runtime overhead from capability checks
  *
  * @see [[TemporalMode.Standard]] for non-temporal entities
  * @see [[TemporalMode.SystemVersioned]] for temporal entities
  * @see [[Temporal]] for the wrapper that uses this mode
  */
sealed trait TemporalMode

/** Companion providing standard mode markers. */
object TemporalMode:
  /** Standard (non-temporal) entity. Represents a regular table without system versioning.
    *
    * Entities marked with this mode:
    *   - Store only current state
    *   - Do not track historical changes
    *   - Cannot use `FOR SYSTEM_TIME` query clauses
    */
  sealed trait Standard extends TemporalMode

  /** System-versioned temporal entity. Represents a table with `SYSTEM_VERSIONING = ON`.
    *
    * Entities marked with this mode:
    *   - Track complete history of all changes
    *   - Support temporal query clauses (`AS OF`, `BETWEEN`, `CONTAINED IN`, etc.)
    *   - Automatically maintain history in a companion table
    *   - Provide period columns (`ValidFrom`, `ValidTo`) managed by the database
    *
    * @see [[SystemTime]] for available temporal query modes
    */
  sealed trait SystemVersioned extends TemporalMode
end TemporalMode
