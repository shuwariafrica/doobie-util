# ashtray

Lean utilities for [doobie](https://tpolecat.github.io/doobie/), the functional JDBC layer for Scala.

## Modules

| Module | Package | Description |
|--------|---------|-------------|
| `ashtray-mssql` | `ashtray.mssql` | SQL Server `UNIQUEIDENTIFIER`, datetime codecs, and UUID generation |
| `ashtray-zio-prelude` | `ashtray.prelude` | `Meta` derivation for zio-prelude `Newtype` |

---

## `ashtray-mssql`

Type-safe `UNIQUEIDENTIFIER` handling with phantom-typed versioned identifiers, compile-time literals, effectful generation, and doobie `Meta` instances.

### Installation

```scala
libraryDependencies += "africa.shuwari" %% "ashtray-mssql" % "<version>"
```

### Import

```scala
import ashtray.mssql.*
```

This brings into scope:
- `Identifier` opaque type and `Identifier.Versioned[V]` phantom wrapper
- Type aliases: `VersionV1`, `VersionV4`, `VersionV7`, `IdentifierV1`, `IdentifierV4`, `IdentifierV7`
- Top-level `generate` / `generateBatch` functions
- Compile-time literals: `id"..."`, `idv1"..."`, `idv4"..."`, `idv7"..."`
- Doobie `Meta` instances for `Identifier`, `Versioned[V]`, `UUID`, `OffsetDateTime`, `LocalDateTime`

### Compile-time literals

```scala
val untyped: Identifier   = id"550e8400-e29b-41d4-a716-446655440000"
val v7: IdentifierV7      = idv7"019012f3-a456-7def-8901-234567890abc"
val v4: IdentifierV4      = idv4"550e8400-e29b-41d4-a716-446655440000"
```

Invalid input fails at compile time.

### Effectful generation

Requires `SecureRandom[F]` (and `Clock[F]` for V7) in scope.

```scala
import cats.effect.IO
import cats.effect.std.SecureRandom

given SecureRandom[IO] = SecureRandom.javaSecuritySecureRandom[IO](64).unsafeRunSync()

val one: IO[IdentifierV7]         = generate[IO, VersionV7]
val batch: IO[Vector[IdentifierV4]] = generateBatch[IO, VersionV4](32)
```

`generateBatch` raises `IdentifierError.InvalidBatchCount` for non-positive counts.

### Version narrowing

Parsed identifiers are untyped; narrow to access version-specific extensions:

```scala
val parsed: Either[IdentifierError, Identifier] =
  Identifier.parse("019012f3-a456-7def-8901-234567890abc")

parsed.toOption.flatMap(_.asV7).foreach { v7 =>
  println(v7.timestampMillis)
  println(v7.instant)
}
```

V1 extensions: `timestamp100Nanos`, `clockSequence`, `node`.

### Interop

```scala
val uuid: java.util.UUID = untyped.toJava
val back: Identifier     = Identifier.fromJava(uuid)

val bytes: Array[Byte]         = untyped.toBytes          // big-endian
val sqlBytes: Array[Byte]      = untyped.toSqlServerBytes // mixed-endian
val fromSql: Either[?, Identifier] = Identifier.fromSqlServerBytes(sqlBytes)
```

### doobie integration

`Meta` instances encode `Identifier` as `UNIQUEIDENTIFIER` using the correct SQL Server mixed-endian byte layout.

```scala
import doobie.*, doobie.implicits.*

def insert(id: Identifier): ConnectionIO[Int] =
  sql"INSERT INTO items(id) VALUES ($id)".update.run

def selectV7s: ConnectionIO[List[IdentifierV7]] =
  sql"SELECT id FROM items".query[Identifier].to[List].map(_.flatMap(_.asV7))
```

Also provided: `Meta[UUID]`, `Meta[OffsetDateTime]` (`DATETIMEOFFSET`), `Meta[LocalDateTime]` (`DATETIME2`).

### Time formatters

```scala
import ashtray.mssql.time.formatter

val dt  = LocalDateTime.parse("2024-02-21 16:30:07.2019148", formatter.dateTime2)
val dto = OffsetDateTime.parse("2024-02-21 16:30:07.2019148 +06:00", formatter.datetimeoffset)
```

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
