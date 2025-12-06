# ashtray

Lean utilities for [doobie](https://tpolecat.github.io/doobie/), the functional JDBC layer for Scala.

## Modules

| Module | Package | Description |
|--------|---------|-------------|
| `ashtray-mssql` | `ashtray.mssql` | SQL Server temporal tables, `UNIQUEIDENTIFIER` with phantom types, datetime codecs, UUID generation |
| `ashtray-zio-prelude` | `ashtray.prelude` | `Meta` derivation for zio-prelude `Newtype` |

---

## `ashtray-mssql`

Zero-cost abstractions for SQL Server: system-versioned temporal tables with generic ID types, type-safe `UNIQUEIDENTIFIER` handling with phantom-typed versions, datetime formatters, opaque wrapper utilities, and automatic `Meta` derivation.

### Quick start

Domain-specific opaque identifiers with temporal table support:

```scala
import ashtray.mssql.*
import cats.effect.IO
import doobie.*, doobie.implicits.*

// Domain-specific opaque identifier using IdentifierOps
opaque type EmployeeId = Identifier

object EmployeeId:
  object ops extends IdentifierOps[EmployeeId]
  
  // Selectively export operations (or use `export ops.*` for everything)
  export ops.{given, toJava, toBytes, version, asV7}
  
  // Construction
  def apply(id: Identifier): EmployeeId = id
  def parse(s: String): Either[AshtrayError, EmployeeId] = 
    Identifier.parse(s)

// Entity with system-versioned temporal support
case class Employee(id: EmployeeId, name: String, salary: BigDecimal)

// Schema metadata with generic ID type
given TemporalSchema[EmployeeId, Employee] = TemporalSchema(
  table     = "dbo.Employee",
  history   = "dbo.EmployeeHistory",
  id        = "EmployeeID",
  validFrom = "ValidFrom",
  validTo   = "ValidTo",
  cols      = fr"EmployeeID, Name, Salary"
)

given Transactor[IO] = ???

// Extension method for repository derivation
val repo = summon[Transactor[IO]].temporal[EmployeeId, Employee]

// Query with typed identifiers
val empId = EmployeeId(idv7"01933b5e-8f4a-7890-9abc-def012345678")
val yesterday = java.time.Instant.now.minusSeconds(86400)
val snapshot: IO[Option[Temporal.Versioned[Employee]]] =
  repo.asOf(id = empId, instant = yesterday)

// Complete history
val history: IO[List[Temporal.Versioned[Employee]]] =
  repo.history(id = empId)
```

### Installation

```scala
libraryDependencies += "africa.shuwari" %% "ashtray-mssql" % "<version>"
```

### Import

```scala
import ashtray.mssql.*
```

Single import provides complete API surface:
- `Identifier` opaque type with zero-cost `(Long, Long)` representation
- `Identifier.Versioned[V]` phantom-typed wrapper for compile-time version proof
- Type aliases: `IdentifierV1`, `IdentifierV4`, `IdentifierV7`, `VersionV1`, `VersionV4`, `VersionV7`
- Temporal table types: `Temporal[A, M]`, `TemporalVersioned[A]`, `TemporalCurrent[A]`
- Extension methods: `Transactor[F].temporal[ID, A]` for repository derivation
- Compile-time literals: `id"..."`, `idv1"..."`, `idv4"..."`, `idv7"..."`
- `Meta` instances for `Identifier`, `UUID`, `LocalDateTime`, `OffsetDateTime`

---

### Error Handling

Any error encountered during operations will be returned via a unified `AshtrayError` sealed trait hierarchy. No operations throw exceptions as part of their normal API contract (except `Unsafe` variants, and Meta instances where doobie's contract expects exceptions).

#### Unified Error ADT

```scala
sealed abstract class AshtrayError extends Exception with NoStackTrace

// Three semantic branches:
AshtrayError.IdentifierError    // Identifier parsing, validation, generation
AshtrayError.TemporalSchemaError // Temporal schema validation  
AshtrayError.MetaError          // Database interaction errors
```

**Type aliases** for convenience:
```scala
type IdentifierError = AshtrayError.IdentifierError
type TemporalSchemaError = AshtrayError.TemporalSchemaError
type MetaError = AshtrayError.MetaError
```

#### Error Cases

**IdentifierError** (identifier parsing and validation):
- `NullInput` — null value supplied where input required
- `InvalidLength(actual, expected)` — string length mismatch
- `InvalidFormat(input, reason)` — malformed UUID structure
- `InvalidCharacter(char, position, input)` — non-hex character found
- `InvalidByteArrayLength(actual, expected)` — byte array not 16 bytes
- `InvalidBatchCount(count)` — non-positive batch size for generation

**TemporalSchemaError** (schema validation):
- `SqlComment(fragment)` — SQL comment detected in column list
- `StringLiteral(fragment)` — string literal detected in column list
- `EmptyColumnName(fragment)` — empty column name (consecutive commas)
- `ComplexExpression(columnName, fragment)` — SQL expression detected instead of simple column name

**MetaError** (database interaction):
- `IdentifierDecodeFailure(cause: IdentifierError, columnIndex)` — failed to decode identifier from database with column context
- `UnexpectedNull(columnIndex, columnType)` — NULL in non-nullable column
- `JdbcFailure(operation, cause)` — wrapped JDBC exception

#### Usage

All parsing and validation operations return `Either[AshtrayError, A]`:

```scala
import ashtray.mssql.*

// Parse returns unified error type
val result: Either[AshtrayError, Identifier] = 
  Identifier.parse("invalid-uuid")

// Pattern match on specific error types
result match
  case Left(AshtrayError.IdentifierError.InvalidLength(actual, expected)) =>
    println(s"Wrong length: expected $expected, got $actual")
  case Left(AshtrayError.IdentifierError.InvalidFormat(input, reason)) =>
    println(s"Malformed: $reason")
  case Left(error) => 
    println(s"Error: ${error.getMessage}")
  case Right(id) => 
    println(s"Parsed: $id")

// Or use type alias for brevity
result match
  case Left(err: IdentifierError) => println(s"Identifier error: $err")
  case Left(err: TemporalSchemaError) => println(s"Schema error: $err")
  case Left(err: MetaError) => println(s"Database error: $err")
  case Right(id) => println(s"Success: $id")
```

**Effect propagation** with cats-effect:
```scala
import cats.effect.IO
import cats.syntax.all.*

def processId(s: String): IO[Unit] = 
  Identifier.parse(s).liftTo[IO].flatMap { id =>
    // Work with validated identifier
    IO.println(s"Valid ID: $id")
  }
```

**CanEqual instances** provided for strict equality checking in Scala 3, enabling proper pattern matching and comparison of error values.

---

### Identifiers

Zero-allocation `UNIQUEIDENTIFIER` wrapper storing UUID bits as `(Long, Long)`. Internally maintains RFC 9562 big-endian layout; byte-swapping to SQL Server's mixed-endian format occurs only at database boundaries.

#### Compile-time literals

Validated at compile time with no runtime overhead:

```scala
// Untyped identifier (version unknown at compile time)
val untyped: Identifier = id"550e8400-e29b-41d4-a716-446655440000"

// Version-typed literals (narrowed at compile time)
val v7: IdentifierV7 = idv7"019012f3-a456-7def-8901-234567890abc"
val v4: IdentifierV4 = idv4"550e8400-e29b-41d4-a716-446655440000"
val v1: IdentifierV1 = idv1"c232ab00-9414-11ec-b3c8-9f6bdeced846"

// Polymorphic versioned (auto-narrows to V1|V4|V7 union)
val typed: Identifier.Versioned[Version.V1.type | Version.V4.type | Version.V7.type] =
  idv"019012f3-a456-7def-8901-234567890abc"

// Type error if version doesn't match
// val wrong: IdentifierV7 = idv7"550e8400-e29b-41d4-a716-446655440000"
//                                 ^ compile error: expected v7, got v4
```

**Available interpolators**:
- `id"..."` — untyped `Identifier`
- `idv"..."` — automatically narrowed to `Identifier.Versioned[V1.type | V4.type | V7.type]`
- `idv1"..."`, `idv4"..."`, `idv7"..."` — version-specific with compile-time validation

#### Effectful generation

Generate identifiers using `Clock[F]` and `SecureRandom[F]`:

```scala
import ashtray.mssql.*
import cats.effect.IO

// V7 (time-ordered) — recommended for most use cases
val v7: IO[IdentifierV7] = generate[IO, VersionV7]

// V4 (random)
val v4: IO[IdentifierV4] = generate[IO, VersionV4]

// Batch generation
val batch: IO[Vector[IdentifierV7]] = generateBatch[IO, VersionV7](100)
```

**Requirements**:
- V7: `Clock[F]` and `SecureRandom[F]` (timestamp + random bits)
- V4: `SecureRandom[F]` only (fully random)

`generateBatch` fails when count is non-positive, returning `F.raiseError(AshtrayError.IdentifierError.InvalidBatchCount(count))`.

#### Parsing and version narrowing

Runtime parsing returns untyped `Identifier`. Narrow to `Versioned[V]` to access version-specific extensions:

```scala
val parsed: Either[AshtrayError, Identifier] =
  Identifier.parse("019012f3-a456-7def-8901-234567890abc")

// Safe narrowing with Option
parsed.toOption.flatMap(_.asV7).foreach { v7 =>
  println(v7.timestampMillis)  // Extract 48-bit millisecond timestamp
  println(v7.instant)          // As java.time.Instant
}

// Alternative: parseOption, parseUnsafe
val opt: Option[Identifier] = Identifier.parseOption("...")
val unsafe: Identifier = Identifier.parseUnsafe("...")  // Throws on invalid input
```

**Error cases** (`AshtrayError` unified ADT):
- `AshtrayError.IdentifierError.NullInput` — null value supplied
- `AshtrayError.IdentifierError.InvalidLength(actual, expected)` — string length mismatch
- `AshtrayError.IdentifierError.InvalidFormat(input, reason)` — malformed structure
- `AshtrayError.IdentifierError.InvalidCharacter(char, position, input)` — non-hex character
- `AshtrayError.IdentifierError.InvalidByteArrayLength(actual, expected)` — byte array not 16 bytes
- `AshtrayError.IdentifierError.InvalidBatchCount(count)` — non-positive batch size

Type aliases available for convenience: `IdentifierError`, `TemporalSchemaError`, `MetaError`

**Version-specific extensions**:
- **V7**: `timestampMillis` (as `Long`), `instant` (as `java.time.Instant`)
- **V1**: `timestamp` (as `Long`, 100-nanosecond intervals since 1582-10-15), `clockSequence`, `node`
- **V4**: No extensions (fully random, no extractable structure)

#### Interop and byte encoding

```scala
// java.util.UUID conversion (zero-cost)
val uuid: java.util.UUID = untyped.toJava
val back: Identifier     = Identifier.fromJava(uuid)

// Byte arrays
val bytes: Array[Byte]    = untyped.toBytes          // RFC 9562 big-endian (16 bytes)
val sqlBytes: Array[Byte] = untyped.toSqlServerBytes // SQL Server mixed-endian (16 bytes)

// Parse from bytes (validates length)
val fromBytes: Either[AshtrayError, Identifier] = Identifier.fromBytes(bytes)
val fromSql: Either[AshtrayError, Identifier]   = Identifier.fromSqlServerBytes(sqlBytes)

// Bit accessors
val msb: Long = untyped.mostSignificant  // High 64 bits
val lsb: Long = untyped.leastSignificant // Low 64 bits

// Version and variant extraction
val version: Version = untyped.version    // V1, V4, V7, or Unknown(n)
val variant: Int = untyped.variant         // RFC variant bits
```

**Unsafe variants** (throw on error):
- `Identifier.fromBytesUnsafe(bytes)`
- `Identifier.fromSqlServerBytesUnsafe(bytes)`

**Sentinel values**:
- `Identifier.nil` — all zero bits (00000000-0000-0000-0000-000000000000)
- `Identifier.maximum` — all one bits (ffffffff-ffff-ffff-ffff-ffffffffffff)

**SQL Server byte layout**: UNIQUEIDENTIFIER uses mixed-endian ordering (bytes 0-3 little-endian, 4-5 little-endian, 6-7 little-endian, 8-15 big-endian). Conversion handled automatically by `Meta` instances and explicit `toSqlServerBytes`/`fromSqlServerBytes`.

#### Doobie integration

Automatic `Meta[Identifier]` and `Meta[Identifier.Versioned[V]]` instances encode as `UNIQUEIDENTIFIER`:

```scala
import doobie.*, doobie.implicits.*

def insert(id: IdentifierV7): ConnectionIO[Int] =
  sql"INSERT INTO items(id) VALUES ($id)".update.run

def select: ConnectionIO[List[Identifier]] =
  sql"SELECT id FROM items".query[Identifier].to[List]

// Narrow results after retrieval
def selectV7s: ConnectionIO[List[IdentifierV7]] =
  select.map(_.flatMap(_.asV7))
```

#### Type class instances

`Eq`, `Hash`, `Order`, `Show`, and `CanEqual` instances available for `Identifier` and `Identifier.Versioned[V]`:

```scala
import cats.syntax.all.*

id1 === id2                  // Eq
id1.compare(id2)             // Order (lexicographic on bytes)
id1.show                     // Show (lowercase hyphenated format)
id1.hash                     // Hash
```

`Show` instance produces lowercase hyphenated format: `"550e8400-e29b-41d4-a716-446655440000"`

#### Opaque wrapper utilities

`IdentifierOps[T]` eliminates boilerplate when wrapping `Identifier` in domain-specific opaque types:

```scala
import ashtray.mssql.*

opaque type UserId = Identifier

object UserId:
  // Create ops object
  object ops extends IdentifierOps[UserId]
  
  // Selectively export what you need
  export ops.{given, toJava, toBytes, version}
  
  // Or export everything: export ops.*
  
  // Add domain-specific methods
  def apply(id: Identifier): UserId = id
  def parse(s: String): Either[AshtrayError, UserId] = 
    Identifier.parse(s)

// Usage preserves type safety
val userId: UserId = UserId(id"550e8400-e29b-41d4-a716-446655440000")
val uuid = userId.toJava          // Available via export
val version = userId.version      // Available via export
```

**Available operations in IdentifierOps**:

*Type class instances* (export with `given`):
- `Eq[T]`, `Hash[T]`, `Order[T]`, `Show[T]`, `CanEqual[T, T]`

*Core conversions*:
- `toJava`, `toBytes`, `toSqlServerBytes`
- `mostSignificant`, `leastSignificant`

*Version operations*:
- `version`, `variant`, `asV1`, `asV4`, `asV7`

For V7-specific operations (timestamp extraction), use `IdentifierV7Ops` instead. For V1 operations, use `IdentifierV1Ops`.

**Design rationale**: This pattern uses transparent inline methods for zero runtime cost whilst maintaining opaque type boundaries. The `export` clause provides selective API surface control without manual delegation of 15+ methods.

---

### Temporal tables

System-versioned temporal tables track complete change history with automatic period management by SQL Server. Supports any primary key type with available `Put` instance.

#### Schema definition

Define metadata once per entity. Generic over both primary key type `ID` and entity type `A`:

```scala
case class Employee(id: Identifier, name: String, salary: BigDecimal)

given TemporalSchema[Identifier, Employee] = TemporalSchema(
  table     = "dbo.Employee",
  history   = "dbo.EmployeeHistory",
  id        = "EmployeeID",
  validFrom = "ValidFrom",
  validTo   = "ValidTo",
  cols      = fr"EmployeeID, Name, Salary"
)
```

**Type parameters**:
- `ID` — Primary key type (e.g., `Long`, `Identifier`, `UUID`). Requires `Put[ID]` for WHERE clause parameter encoding
- `A` — Entity type. Requires `Read[A]` and `Write[A]` (automatic for case classes)

**Field requirements**:
- `table` — Current table name, optionally schema-qualified (e.g., `"dbo.Employee"`)
- `history` — History table name where SQL Server stores previous versions
- `id` — Primary key column name for filtering queries by entity ID
- `validFrom` — Period start column (declared `GENERATED ALWAYS AS ROW START`)
- `validTo` — Period end column (declared `GENERATED ALWAYS AS ROW END`)
- `cols` — Doobie fragment listing entity columns in case class field order. **Excludes period columns**

No naming conventions enforced. Use your existing schema.

**Schema validation**: Column lists are validated to prevent SQL injection. The library rejects fragments containing:
- SQL comments (`--` or `/* */`)
- String literals (single or double quotes)
- SQL keywords (SELECT, FROM, WHERE, etc.)
- Function calls or complex expressions
- Empty column names

Validation occurs at query execution time and returns `Left(AshtrayError.TemporalSchemaError._)` on detection of suspicious patterns.

#### Repository derivation

Two equivalent approaches:

```scala
import cats.effect.IO
import java.time.Instant

given Transactor[IO] = ???

// Approach 1: Direct derivation
val repo: TemporalRepo[IO, Identifier, Employee] =
  TemporalRepo.derived[IO, Identifier, Employee]

// Approach 2: Extension syntax (recommended)
val repo2: TemporalRepo[IO, Identifier, Employee] =
  summon[Transactor[IO]].temporal[Identifier, Employee]

// Point-in-time query
val empId = idv7"01933b5e-8f4a-7890-9abc-def012345678"
val snapshot: IO[Option[Temporal.Versioned[Employee]]] =
  repo.asOf(id = empId, instant = Instant.now.minusSeconds(3600))

// Complete history (ordered oldest → newest)
val history: IO[List[Temporal.Versioned[Employee]]] =
  repo.history(id = empId)

// Compare versions
val yesterday = Instant.now.minusSeconds(86400)
val today = Instant.now
val diff: IO[Option[(Temporal.Versioned[Employee], Temporal.Versioned[Employee])]] =
  repo.diff(id = empId, instant1 = yesterday, instant2 = today)
```

All query methods generated inline at compile time for zero abstraction overhead.

#### Available operations

**Point-in-time queries**:
- `asOf(id: ID, instant: Instant): F[Option[Temporal.Versioned[A]]]` — Entity version active at instant (SQL: `FOR SYSTEM_TIME AS OF`)
- `allAsOf(instant: Instant): F[List[Temporal.Versioned[A]]]` — All entities at instant

**Range queries**:
- `history(id: ID): F[List[Temporal.Versioned[A]]]` — Complete history ordered chronologically (SQL: `FOR SYSTEM_TIME ALL`)
- `historyBetween(id: ID, from: Instant, to: Instant): F[List[Temporal.Versioned[A]]]` — Versions overlapping period (SQL: `FOR SYSTEM_TIME FROM...TO`, exclusive bounds)
- `containedIn(id: ID, from: Instant, to: Instant): F[List[Temporal.Versioned[A]]]` — Versions entirely within period (SQL: `FOR SYSTEM_TIME CONTAINED IN`)

**Comparison and rollback**:
- `diff(id: ID, instant1: Instant, instant2: Instant): F[Option[(Temporal.Versioned[A], Temporal.Versioned[A])]]` — Retrieve versions at two time points for comparison
- `restoreTo(id: ID, instant: Instant): F[Int]` — Rollback entity to historical state (updates current table with past version)
- `current(id: ID): F[Option[A]]` — Current state without temporal clause (standard SELECT)

#### Extension syntax

Single import provides extension method on `Transactor[F]`:

```scala
import ashtray.mssql.*
import cats.effect.IO

given Transactor[IO] = ???

// Extension method derives repository
val repo: TemporalRepo[IO, Identifier, Employee] =
  summon[Transactor[IO]].temporal[Identifier, Employee]
```

No additional imports required. The `.temporal[ID, A]` extension eliminates boilerplate:

```scala
// Before (explicit derivation)
val repo1 = TemporalRepo.derived[IO, Identifier, Employee](
  using schema, read, write, put, xa, monadCancel
)

// After (extension method)
val repo2 = xa.temporal[Identifier, Employee]
```

#### Temporal wrappers

`Temporal[A, M]` pairs an entity with its validity period. Phantom type `M <: TemporalMode` enables mode-specific operations:

- `Temporal.Versioned[A]` — System-versioned entity (alias: `TemporalVersioned[A]`)
- `Temporal.Current[A]` — Standard entity with period (alias: `TemporalCurrent[A]`)

```scala
val versioned: Temporal.Versioned[Employee] = ???

// Check if current version (validTo == 9999-12-31 23:59:59.9999999)
val isCurrent: Boolean = versioned.isCurrent

// Extract entity (discards period)
val employee: Employee = versioned.current

// Access period
import java.time.LocalDateTime
val validFrom: LocalDateTime = versioned.period.validFrom
val validTo: LocalDateTime   = versioned.period.validTo
```

**Automatic derivation**:
- `Read[Temporal[A, M]]` requires `Read[A]` — reads entity columns followed by period columns
- `Write[Temporal[A, M]]` requires `Write[A]` — writes entity columns followed by period columns

**Period semantics**:
- Current rows: `validTo = LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999999900)`
- Historical rows: `validTo` reflects when the version was superseded
- Period constant available as `Period.MaxDateTime2`

#### Manual query composition

Inline fragment builders enable zero-cost custom temporal queries:

```scala
import java.time.Instant

val schema = summon[TemporalSchema[Identifier, Employee]]
val systemTime = SystemTime.asOf(Instant.now.minusSeconds(3600))

val query: Query0[Temporal.Versioned[Employee]] =
  (fr"SELECT" ++ schema.allColumns ++
   fr"FROM" ++ schema.forSystemTime(systemTime) ++
   fr"WHERE Salary >" ++ fr"50000")
    .query[Temporal.Versioned[Employee]]
```

**Schema fragment builders** (all inline):
- `schema.columns: Fragment` — Entity columns only (excludes period)
- `schema.periodColumns: Fragment` — `ValidFrom, ValidTo`
- `schema.allColumns: Fragment` — Entity + period columns combined
- `schema.forSystemTime(mode: SystemTime): Fragment` — Table name + temporal clause

**SystemTime modes**:
- `SystemTime.asOf(instant: Instant)` — Point-in-time (`FOR SYSTEM_TIME AS OF`)
- `SystemTime.fromTo(from: Instant, to: Instant)` — Overlapping range, exclusive upper bound (`FOR SYSTEM_TIME FROM...TO`)
- `SystemTime.between(from: Instant, to: Instant)` — Overlapping range, inclusive upper bound (`FOR SYSTEM_TIME BETWEEN...AND`)
- `SystemTime.containedIn(from: Instant, to: Instant)` — Fully within range (`FOR SYSTEM_TIME CONTAINED IN`)
- `SystemTime.All` — All history (`FOR SYSTEM_TIME ALL`)

All constructors accept `java.time.Instant` and convert to UTC `LocalDateTime` for SQL Server `DATETIME2` compatibility.

---

### Time formatters

Strict `DateTimeFormatter` instances matching SQL Server string representations:

```scala
import ashtray.mssql.time.formatter
import java.time.{LocalDateTime, OffsetDateTime}

// DATETIME2 (supports 0-7 fractional digits)
val dt: LocalDateTime = LocalDateTime.parse(
  "2024-02-21 16:30:07.2019148",
  formatter.dateTime2
)

// DATETIMEOFFSET (with timezone offset)
val dto: OffsetDateTime = OffsetDateTime.parse(
  "2024-02-21 16:30:07.2019148 +06:00",
  formatter.datetimeoffset
)
```

**Format specifications**:
- `dateTime2`: `yyyy-MM-dd HH:mm:ss[.nnnnnnn]` (0-7 fractional seconds)
- `datetimeoffset`: `yyyy-MM-dd HH:mm:ss[.nnnnnnn] ±HH:mm`

Both use `DateTimeFormatterBuilder` with strict parsing — invalid formats or out-of-range values raise exceptions.

---

### Meta instances

Automatic `Meta` derivation for SQL Server types. All available via `import ashtray.mssql.*`:

| Scala type | SQL Server type | Notes |
|------------|-----------------|-------|
| `Identifier` | `UNIQUEIDENTIFIER` | Mixed-endian byte encoding |
| `Identifier.Versioned[V]` | `UNIQUEIDENTIFIER` | Phantom type erased at runtime |
| `java.util.UUID` | `UNIQUEIDENTIFIER` | Via standard `java.util.UUID` conversion |
| `LocalDateTime` | `DATETIME2` | UTC assumed (no timezone) |
| `OffsetDateTime` | `DATETIMEOFFSET` | Preserves timezone offset |

Nullable variants supported via `Option[T]`.

---

## `ashtray-zio-prelude`

Derive doobie `Meta` for zio-prelude `Newtype` wrappers.

### Installation

```scala
libraryDependencies += "africa.shuwari" %% "ashtray-zio-prelude" % "<version>"
```

### Usage

```scala
import ashtray.prelude.newTypeMeta
import doobie.Meta
import zio.prelude.Newtype

object UserId extends Newtype[Long]
type UserId = UserId.Type

given Meta[UserId] = newTypeMeta(UserId)
```

---

## Testing

Tests use munit with Testcontainers for SQL Server integration. This requires a container runtime environment available (Docker / Podman).

```bash
sbt test
```

Docker must be running for container-backed suites.

---

## Licence

Licensed under the Apache License (Version 2.0). See the [license text for clarification](LICENSE).
