import sbt.*
import sbt.Keys.*
import sbtdynver.DynVerPlugin.autoImport.*

object VersionPlugin extends AutoPlugin {

  //  def pgpSettings = List(
  //    PgpKeys.pgpSelectPassphrase :=
  //      sys.props
  //        .get("SIGNING_KEY_PASSPHRASE")
  //        .map(_.toCharArray),
  //    usePgpKeyHex(System.getenv("SIGNING_KEY_ID"))
  //  )

  def baseVersionSetting(appendMetadata: Boolean): Def.Initialize[String] = {
    def baseVersionFormatter(in: sbtdynver.GitDescribeOutput) = {
      def meta =
        if (appendMetadata) s"+${in.commitSuffix.distance}.${in.commitSuffix.sha}"
        else ""

      if (!in.isSnapshot()) in.ref.dropPrefix
      else {
        val parts = {
          def current = in.ref.dropPrefix.split("\\.").map(_.toInt)

          current.updated(current.length - 1, current.last + 1)
        }
        s"${parts.mkString(".")}-SNAPSHOT$meta"
      }
    }

    Def.setting(
      dynverGitDescribeOutput.value.mkVersion(
        baseVersionFormatter,
        "SNAPSHOT"
      )
    )
  }

  def versionSetting = baseVersionSetting(appendMetadata = false)

  def implementationVersionSetting = baseVersionSetting(appendMetadata = true)

}
