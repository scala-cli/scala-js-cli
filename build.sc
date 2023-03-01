import $ivy.`de.tototec::de.tobiasroeser.mill.vcs.version::0.3.0`
import $ivy.`io.github.alexarchambault.mill::mill-native-image::0.1.23`
import $ivy.`io.github.alexarchambault.mill::mill-native-image-upload:0.1.19`
import $ivy.`io.get-coursier::coursier-launcher:2.1.0-M2`

import de.tobiasroeser.mill.vcs.version._
import io.github.alexarchambault.millnativeimage.NativeImage
import io.github.alexarchambault.millnativeimage.upload.Upload
import mill._
import mill.scalalib._
import coursier.core.Version

import java.io.File

import scala.concurrent.duration._
import scala.util.Properties.isWin


def scalaJsCliVersion = "1.1.1-sc5"
def scala213 = "2.13.10"
def scalaJsVersion = "1.13.0"
object cli extends Cli
trait Cli extends ScalaModule with ScalaJsCliPublishModule {
  def scalaVersion = scala213
  def artifactName = "scalajs" + super.artifactName()
  def ivyDeps = super.ivyDeps() ++ Seq(
    ivy"org.scala-js::scalajs-linker:$scalaJsVersion",
    ivy"com.github.scopt::scopt:4.1.0"
  )
  def mainClass = Some("org.scalajs.cli.Scalajsld")

  def transitiveJars: T[Agg[PathRef]] = {

    def allModuleDeps(todo: List[JavaModule]): List[JavaModule] = {
      todo match {
        case Nil => Nil
        case h :: t =>
          h :: allModuleDeps(h.moduleDeps.toList ::: t)
      }
    }

    T {
      mill.define.Target.traverse(allModuleDeps(this :: Nil).distinct)(m => T.task(m.jar()))()
    }
  }

  def jarClassPath = T {
    val cp = runClasspath() ++ transitiveJars()
    cp.filter(ref => os.exists(ref.path) && !os.isDir(ref.path))
  }

  def standaloneLauncher = T {
    val cachePath = os.Path(coursier.cache.FileCache().location, os.pwd)

    def urlOf(path: os.Path): Option[String] =
      if (path.startsWith(cachePath)) {
        val segments = path.relativeTo(cachePath).segments
        val url = segments.head + "://" + segments.tail.mkString("/")
        Some(url)
      }
      else None

    import coursier.launcher.{
      BootstrapGenerator,
      ClassPathEntry,
      Parameters,
      Preamble
    }
    val cp = jarClassPath().map(_.path)
    val mainClass0 = mainClass().getOrElse(sys.error("No main class"))

    val dest = T.ctx().dest / (if (isWin) "launcher.bat" else "launcher")

    val preamble = Preamble()
      .withOsKind(isWin)
      .callsItself(isWin)
    val entries = cp.map { path =>
      urlOf(path) match {
        case None =>
          val content = os.read.bytes(path)
          val name = path.last
          ClassPathEntry.Resource(name, os.mtime(path), content)
        case Some(url) => ClassPathEntry.Url(url)
      }
    }
    val loaderContent = coursier.launcher.ClassLoaderContent(entries)
    val params = Parameters.Bootstrap(Seq(loaderContent), mainClass0)
      .withDeterministic(true)
      .withPreamble(preamble)

    BootstrapGenerator.generate(params, dest.toNIO)

    PathRef(dest)
  }
}

trait ScalaJsCliNativeImage extends ScalaModule with NativeImage {
  def scalaVersion = scala213

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
  def graalVmVersion = "22.3.1"
  def nativeImageGraalVmJvmId = s"graalvm-java17:$graalVmVersion"
  def nativeImageName = "scala-js-ld"
  def moduleDeps() = Seq(
    cli
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

object native extends ScalaJsCliNativeImage

def native0 = native

def csDockerVersion = "2.1.0-M5-18-gfebf9838c"

trait ScalaJsCliStaticNativeImage extends ScalaJsCliNativeImage {
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
object `native-static` extends ScalaJsCliStaticNativeImage

trait ScalaJsCliMostlyStaticNativeImage extends ScalaJsCliNativeImage {
  def nameSuffix = "-mostly-static"
  def nativeImageDockerParams = Some(
    NativeImage.linuxMostlyStaticParams(
      "ubuntu:18.04", // TODO Pin that?
      s"https://github.com/coursier/coursier/releases/download/v$csDockerVersion/cs-x86_64-pc-linux.gz"
    )
  )
}
object `native-mostly-static` extends ScalaJsCliMostlyStaticNativeImage

object tests extends ScalaModule {
  def scalaVersion = scala213

  object test extends Tests {
    def ivyDeps = super.ivyDeps() ++ Seq(
      ivy"org.scalameta::munit:0.7.29",
      ivy"com.lihaoyi::os-lib:0.9.0",
      ivy"com.lihaoyi::pprint:0.8.1"
    )
    def testFramework = "munit.Framework"

    private final class TestHelper(
      launcherTask: T[PathRef]
    ) {
      def test(args: String*) = {
        val argsTask = T.task {
          val launcher = launcherTask().path
          val extraArgs = Seq(
            s"-Dtest.scala-js-cli.path=$launcher",
            s"-Dtest.scala-js-cli.scala-js-version=$scalaJsVersion"
          )
          args ++ extraArgs
        }
        T.command {
          testTask(argsTask, T.task(Seq.empty[String]))()
        }
      }
    }

    def test(args: String*) =
      jvm(args: _*)
    def jvm(args: String*) =
      new TestHelper(cli.standaloneLauncher).test(args: _*)
    def native(args: String*) =
      new TestHelper(native0.nativeImage).test(args: _*)
    def nativeStatic(args: String*) =
      new TestHelper(`native-static`.nativeImage).test(args: _*)
    def nativeMostlyStatic(args: String*) =
      new TestHelper(`native-mostly-static`.nativeImage).test(args: _*)
  }
}

def ghOrg = "scala-cli"
def ghName = "scala-js-cli"
trait ScalaJsCliPublishModule extends PublishModule {
  import mill.scalalib.publish._
  def pomSettings = PomSettings(
    description = artifactName(),
    organization = "io.github.alexarchambault.tmp",
    url = s"https://github.com/$ghOrg/$ghName",
    licenses = Seq(License.`BSD-3-Clause`),
    versionControl = VersionControl.github(ghOrg, ghName),
    developers = Seq(
      Developer("alexarchambault", "Alex Archambault", "https://github.com/alexarchambault"),
      Developer("sjrd", "Sébastien Doeraene", "https://github.com/sjrd"),
      Developer("gzm0", "Tobias Schlatter", "https://github.com/gzm0"),
      Developer("nicolasstucki", "Nicolas Stucki", "https://github.com/nicolasstucki"),
    )
  )
  def publishVersion =
    finalPublishVersion()
}

private def computePublishVersion(state: VcsState, simple: Boolean): String =
  if (state.commitsSinceLastTag > 0)
    if (simple) {
      val versionOrEmpty = state.lastTag
        .filter(_ != "latest")
        .filter(_ != "nightly")
        .map(_.stripPrefix("v"))
        .map(_.takeWhile(c => c == '.' || c.isDigit))
        .flatMap { tag =>
          if (simple) {
            val idx = tag.lastIndexOf(".")
            if (idx >= 0)
              Some(tag.take(idx + 1) + (tag.drop(idx + 1).toInt + 1).toString + "-SNAPSHOT")
            else
              None
          } else {
            val idx = tag.indexOf("-")
            if (idx >= 0) Some(tag.take(idx) + "+" + tag.drop(idx + 1) + "-SNAPSHOT")
            else None
          }
        }
        .getOrElse("0.0.1-SNAPSHOT")
      Some(versionOrEmpty)
        .filter(_.nonEmpty)
        .getOrElse(state.format())
    } else {
      val rawVersion = os
        .proc("git", "describe", "--tags")
        .call()
        .out
        .text()
        .trim
        .stripPrefix("v")
        .replace("latest", "0.0.0")
        .replace("nightly", "0.0.0")
      val idx = rawVersion.indexOf("-")
      if (idx >= 0) rawVersion.take(idx) + "-" + rawVersion.drop(idx + 1) + "-SNAPSHOT"
      else rawVersion
    }
  else
    state.lastTag
      .getOrElse(state.format())
      .stripPrefix("v")

private def finalPublishVersion = {
  val isCI = System.getenv("CI") != null
  if (isCI)
    T.persistent {
      val state = VcsVersion.vcsState()
      computePublishVersion(state, simple = false)
    }
  else
    T {
      val state = VcsVersion.vcsState()
      computePublishVersion(state, simple = true)
    }
}

object ci extends Module {
  def publishSonatype(tasks: mill.main.Tasks[PublishModule.PublishData]) = T.command {
    publishSonatype0(
      data = define.Target.sequence(tasks.value)(),
      log = T.ctx().log
    )
  }

  private def publishSonatype0(
      data: Seq[PublishModule.PublishData],
      log: mill.api.Logger
  ): Unit = {

    val credentials = sys.env("SONATYPE_USERNAME") + ":" + sys.env("SONATYPE_PASSWORD")
    val pgpPassword = sys.env("PGP_PASSPHRASE")
    val timeout = 10.minutes

    val artifacts = data.map { case PublishModule.PublishData(a, s) =>
      (s.map { case (p, f) => (p.path, f) }, a)
    }

    val isRelease = {
      val versions = artifacts.map(_._2.version).toSet
      val set = versions.map(!_.endsWith("-SNAPSHOT"))
      assert(
        set.size == 1,
        s"Found both snapshot and non-snapshot versions: ${versions.toVector.sorted.mkString(", ")}"
      )
      set.head
    }
    val publisher = new scalalib.publish.SonatypePublisher(
      uri = "https://s01.oss.sonatype.org/service/local",
      snapshotUri = "https://s01.oss.sonatype.org/content/repositories/snapshots",
      credentials = credentials,
      signed = true,
      // format: off
      gpgArgs = Seq(
        "--detach-sign",
        "--batch=true",
        "--yes",
        "--pinentry-mode", "loopback",
        "--passphrase", pgpPassword,
        "--armor",
        "--use-agent"
      ),
      // format: on
      readTimeout = timeout.toMillis.toInt,
      connectTimeout = timeout.toMillis.toInt,
      log = log,
      awaitTimeout = timeout.toMillis.toInt,
      stagingRelease = isRelease
    )

    publisher.publishAll(isRelease, artifacts: _*)
  }
  def upload(directory: String = "artifacts/") = T.command {
    val version = finalPublishVersion()

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

    Upload.upload("scala-cli", "scala-js-cli", ghToken, tag, dryRun = false, overwrite = overwriteAssets)(launchers: _*)
  }
}

private def bash =
  if (isWin) Seq("bash.exe")
  else Nil
