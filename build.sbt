inThisBuild(
  List(
    scalaVersion := crossScalaVersions.value.head,
    crossScalaVersions := List(libraries.scalaVersion),
    organization := "africa.shuwari",
    description := "Simple utilities for use with Doobie JDBC layer for Scala.",
    homepage := Some(url("https://github.com/shuwarifrica/ashtray")),
    startYear := Some(2023),
    semanticdbEnabled := true,
    sonatypeCredentialHost := Sonatype.sonatypeCentralHost,
    publishCredentials,
    scmInfo := ScmInfo(
      url("https://github.com/shuwariafrica/ashtray"),
      "scm:git:https://github.com/shuwariafrica/ashtray.git",
      Some("scm:git:git@github.com:shuwariafrica/ashtray.git")
    ).some
  ) ++ formattingSettings
)

val libraries = new {
  final val scalaVersion = "3.7.4"
  val `doobie-core` = "org.tpolecat" %% "doobie-core" % "1.0.0-RC11"
  val `doobie-munit` = `doobie-core`(_.withName("doobie-munit"))
  val munit = "org.scalameta" %% "munit" % "1.2.1"
  val `mssql-jdbc` = "com.microsoft.sqlserver" % "mssql-jdbc" % "12.10.2.jre11"
  val `scribe-slf4j` = "com.outr" %% "scribe-slf4j" % "3.17.0"
  val zio = "dev.zio" %% "zio" % "2.1.24"
  val `zio-prelude` = "dev.zio" %% "zio-prelude" % "1.0.0-RC44"
  val `testcontainers-scala-munit` = "com.dimafeng" %% "testcontainers-scala-munit" % "0.44.0"
  val `testcontainers-scala-mssqlserver` = `testcontainers-scala-munit`.withName("testcontainers-scala-mssqlserver")
}

val `ashtray-test` =
  project
    .in(file("modules/test"))
    .notPublished
    .settings(
      libraryDependencies ++= List(
        libraries.`doobie-core`,
        libraries.munit,
        libraries.`testcontainers-scala-munit`,
        libraries.`testcontainers-scala-mssqlserver`,
        libraries.`mssql-jdbc`
      )
    )

val `ashtray-mssql` =
  project
    .in(file("modules/mssql"))
    .settings(unitTestSettings)
    .settings(publishSettings)
    .settings(ScalaCompiler.compilerOptions := ScalaCompiler.compilerOptions.value.filterNot(
      _ == africa.shuwari.sbt.ScalaCompilerOptions.explicitNulls))
    .settings(ScalaCompiler.compilerOptions +=
      africa.shuwari.sbt.ScalaCompilerOptions.options.advancedOption("check-macros"))
    .dependsOn(`ashtray-test` % Test)
    .settings(
      libraryDependencies ++= List(
        libraries.`doobie-core`,
        libraries.`mssql-jdbc`,
        libraries.`scribe-slf4j` % Test
      ))

val `ashtray-zio-prelude` =
  project
    .in(file("modules/zio-prelude"))
    .dependsOn(`ashtray-test` % Test)
    .settings(unitTestSettings)
    .settings(publishSettings)
    .settings(
      libraryDependencies ++= List(
        libraries.`doobie-core`,
        libraries.zio,
        libraries.`zio-prelude`,
        libraries.`mssql-jdbc` % Test,
        libraries.`scribe-slf4j` % Test
      ))
    .dependsOn(`ashtray-mssql` % Test)

val `ashtray` =
  project
    .in(file("."))
    .shuwariProject
    .notPublished
    .apacheLicensed
    .aggregate(`ashtray-zio-prelude`, `ashtray-mssql`)
    .settings(sonatypeProfileSetting)

def unitTestSettings: List[Setting[?]] = List(
  Test / fork := true, // Required for testcontainers graceful shutdown
  libraryDependencies ++= List(libraries.munit).map(_ % Test),
  testFrameworks += new TestFramework("munit.Framework"),
  Test / javaOptions += "-Djava.util.logging.config.file=" +
    ((ThisBuild / baseDirectory).value / "modules" / "test" / "src" / "main" / "resources" / "logging.properties").absolutePath
)

def formattingSettings: List[Setting[?]] =
  List(
    scalafmtDetailedError := true,
    scalafmtPrintDiff := true
  )

def publishCredentials: Setting[Task[Seq[Credentials]]] = credentials := List(
  Credentials(
    "Sonatype Nexus Repository Manager",
    sonatypeCredentialHost.value,
    System.getenv("PUBLISH_USER"),
    System.getenv("PUBLISH_USER_PASSPHRASE")
  )
)

def publishSettings: List[Setting[?]] = publishCredentials +: pgpSettings ++: List(
  packageOptions += Package.ManifestAttributes(
    "Created-By" -> "Simple Build Tool",
    "Built-By" -> System.getProperty("user.name"),
    "Build-Jdk" -> System.getProperty("java.version"),
    "Specification-Title" -> name.value,
    "Specification-Version" -> Keys.version.value,
    "Specification-Vendor" -> organizationName.value,
    "Implementation-Title" -> name.value,
    "Implementation-Version" -> fullVersion.value,
    "Implementation-Vendor-Id" -> organization.value,
    "Implementation-Vendor" -> organizationName.value
  ),
  publishTo := sonatypePublishToBundle.value,
  pomIncludeRepository := (_ => false),
  publishMavenStyle := true,
  sonatypeProfileSetting
)

def sonatypeProfileSetting: Setting[?] = sonatypeProfileName := "africa.shuwari"

def pgpSettings: List[Setting[?]] = List(
  PgpKeys.pgpSelectPassphrase :=
    sys.props
      .get("SIGNING_KEY_PASSPHRASE")
      .map(_.toCharArray),
  usePgpKeyHex(System.getenv("SIGNING_KEY_ID"))
)

addCommandAlias("format", "scalafmtAll; scalafmtSbt; scalafixAll; headerCreateAll")

addCommandAlias("staticCheck", "scalafmtCheckAll; scalafmtSbtCheck; scalafixAll --check; headerCheckAll")
