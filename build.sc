import $ivy.`de.tototec::de.tobiasroeser.mill.vcs.version::0.1.4`
import $ivy.`io.github.alexarchambault.mill::mill-native-image::0.1.19`
import $ivy.`io.github.alexarchambault.mill::mill-native-image-upload:0.1.19`

import de.tobiasroeser.mill.vcs.version._
import io.github.alexarchambault.millnativeimage.NativeImage
import io.github.alexarchambault.millnativeimage.upload.Upload
import mill._
import mill.scalalib._
import coursier.core.Version

def scalaJsCliVersion = "1.1.1-sc6"
def scalaJsVersions = Seq("1.9.0", "1.10.0", "1.10.1")

class ScalaJsCliNativeImage(val scalaJsVersion0: String) extends ScalaModule with NativeImage {
  def scalaVersion = "2.13.8"
  def scalaJsVersion = scalaJsVersion0

  def nativeImageClassPath = T{
    runClasspath()
  }
  def nativeImageOptions = T{
    super.nativeImageOptions() ++ Seq(
      "--no-fallback",
      "-H:IncludeResources=org/scalajs/linker/backend/emitter/.*.sjsir",
      "-H:IncludeResources=com/google/javascript/jscomp/js/polyfills.txt",
      "-H:IncludeResourceBundles=com.google.javascript.jscomp.parsing.ParserConfig",
    )
  }
  def nativeImagePersist = System.getenv("CI") != null
  def graalVmVersion = "22.1.0"
  def nativeImageGraalVmJvmId = s"graalvm-java17:$graalVmVersion"
  def nativeImageName = "scala-js-ld"
  def ivyDeps = super.ivyDeps() ++ Seq(
    ivy"io.github.alexarchambault.tmp::scalajs-cli:$scalaJsCliVersion"
      // so that this doesn't bump the version we pull ourselves
      .exclude(("org.scala-js", "scalajs-linker_2.13")),
    ivy"org.scala-js::scalajs-linker:$scalaJsVersion"
  )
  def compileIvyDeps = super.compileIvyDeps() ++ Seq(
    ivy"org.graalvm.nativeimage:svm:$graalVmVersion"
  )
  def nativeImageMainClass = "org.scalajs.cli.Scalajsld"

  def nameSuffix = ""
  def copyToArtifacts(directory: String = "artifacts/") = T.command {
    val _ = Upload.copyLauncher(
      nativeImage().path,
      directory,
      s"scala-js-ld-$scalaJsVersion",
      compress = true,
      suffix = nameSuffix
    )
  }
}

object native extends Cross[ScalaJsCliNativeImage](scalaJsVersions: _*)

def csDockerVersion = "2.1.0-M5-18-gfebf9838c"

class ScalaJsCliStaticNativeImage(scalaJsVersion0: String) extends ScalaJsCliNativeImage(scalaJsVersion0) {
  def nameSuffix = "-static"
  def buildHelperImage = T {
    os.proc("docker", "build", "-t", "scala-cli-base-musl:latest", ".")
      .call(cwd = os.pwd / "musl-image", stdout = os.Inherit)
    ()
  }
  def nativeImageDockerParams = T{
    buildHelperImage()
    Some(
      NativeImage.linuxStaticParams(
        "scala-cli-base-musl:latest",
        s"https://github.com/coursier/coursier/releases/download/v$csDockerVersion/cs-x86_64-pc-linux.gz"
      )
    )
  }
  def writeNativeImageScript(scriptDest: String, imageDest: String = "") = T.command {
    buildHelperImage()
    super.writeNativeImageScript(scriptDest, imageDest)()
  }
}
object `native-static` extends Cross[ScalaJsCliStaticNativeImage](scalaJsVersions: _*)

class ScalaJsCliMostlyStaticNativeImage(scalaJsVersion0: String) extends ScalaJsCliNativeImage(scalaJsVersion0) {
  def nameSuffix = "-mostly-static"
  def nativeImageDockerParams = Some(
    NativeImage.linuxMostlyStaticParams(
      "ubuntu:18.04", // TODO Pin that?
      s"https://github.com/coursier/coursier/releases/download/v$csDockerVersion/cs-x86_64-pc-linux.gz"
    )
  )
}
object `native-mostly-static` extends Cross[ScalaJsCliMostlyStaticNativeImage](scalaJsVersions: _*)


def publishVersion = T{
  val state = VcsVersion.vcsState()
  if (state.commitsSinceLastTag > 0) {
    val versionOrEmpty = state.lastTag
      .filter(_ != "latest")
      .map(_.stripPrefix("v"))
      .flatMap { tag =>
        val idx = tag.lastIndexOf(".")
        if (idx >= 0) Some(tag.take(idx + 1) + (tag.drop(idx + 1).toInt + 1).toString + "-SNAPSHOT")
        else None
      }
      .getOrElse("0.0.1-SNAPSHOT")
    Some(versionOrEmpty)
      .filter(_.nonEmpty)
      .getOrElse(state.format())
  } else
    state
      .lastTag
      .getOrElse(state.format())
      .stripPrefix("v")
}

def upload(directory: String = "artifacts/") = T.command {
  val version = publishVersion()

  val path = os.Path(directory, os.pwd)
  val launchers = os.list(path).filter(os.isFile(_)).map { path =>
    path.toNIO -> path.last
  }
  val ghToken = Option(System.getenv("UPLOAD_GH_TOKEN")).getOrElse {
    sys.error("UPLOAD_GH_TOKEN not set")
  }
  val (tag, overwriteAssets) =
    if (version.endsWith("-SNAPSHOT")) ("launchers", true)
    else ("v" + version, false)

  Upload.upload("scala-cli", "scala-js-cli-native-image", ghToken, tag, dryRun = false, overwrite = overwriteAssets)(launchers: _*)
}
