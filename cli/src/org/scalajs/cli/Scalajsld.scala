/*                     __                                                              *\
**     ________ ___   / /  ___      __ ____  Scala.js CLI                              **
**    / __/ __// _ | / /  / _ | __ / // __/  (c) 2013-2014, LAMP/EPFL                  **
**  __\ \/ /__/ __ |/ /__/ __ |/_// /_\ \    (c) 2017-2022 Scala.js Sébastien Doeraene **
** /____/\___/_/ |_/____/_/ | |__/ /____/    http://scala-js.org/                      **
**                          |/____/                                                    **
\*                                                                                     */

package org.scalajs.cli

import org.scalajs.ir.ScalaJSVersions

import org.scalajs.logging._

import org.scalajs.linker._
import org.scalajs.linker.interface._

import CheckedBehavior.Compliant

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global

import java.io.File
import java.net.URI
import java.nio.file.Path
import java.lang.NoClassDefFoundError
import org.scalajs.cli.internal.{EsVersionParser, ModuleSplitStyleParser}

object Scalajsld {

  private case class Options(
      cp: Seq[File] = Seq.empty,
      moduleInitializers: Seq[ModuleInitializer] = Seq.empty,
      output: Option[File] = None,
      outputDir: Option[File] = None,
      semantics: Semantics = Semantics.Defaults,
      esFeatures: ESFeatures = ESFeatures.Defaults,
      moduleKind: ModuleKind = ModuleKind.NoModule,
      moduleSplitStyle: String = ModuleSplitStyle.FewestModules.toString,
      smallModuleForPackages: Seq[String] = Seq.empty,
      outputPatterns: OutputPatterns = OutputPatterns.Defaults,
      noOpt: Boolean = false,
      fullOpt: Boolean = false,
      prettyPrint: Boolean = false,
      sourceMap: Boolean = false,
      relativizeSourceMap: Option[URI] = None,
      checkIR: Boolean = false,
      stdLib: Seq[File] = Nil,
      jsHeader: String = "",
      logLevel: Level = Level.Info
  )

  private def moduleInitializer(
      s: String,
      hasArgs: Boolean
  ): ModuleInitializer = {
    val lastDot = s.lastIndexOf('.')
    if (lastDot < 0)
      throw new IllegalArgumentException(s"$s is not a valid main method")
    val className = s.substring(0, lastDot)
    val mainMethodName = s.substring(lastDot + 1)
    if (hasArgs)
      ModuleInitializer.mainMethodWithArgs(className, mainMethodName)
    else
      ModuleInitializer.mainMethod(className, mainMethodName)
  }

  private implicit object ModuleKindRead extends scopt.Read[ModuleKind] {
    val arity = 1
    val reads = { (s: String) =>
      ModuleKind.All
        .find(_.toString() == s)
        .getOrElse(
          throw new IllegalArgumentException(s"$s is not a valid module kind")
        )
    }
  }

  private object ModuleSplitStyleRead {
    val All = List(
      ModuleSplitStyle.FewestModules.toString,
      ModuleSplitStyle.SmallestModules.toString,
      "SmallModulesFor"
    )

    def moduleSplitStyleRead(
        splitStyle: String,
        modulePackages: Seq[String]
    ): ModuleSplitStyle =
      try {
        (new ModuleSplitStyleParser)
          .parse(splitStyle, modulePackages.toArray)
          .underlying
      } catch {
        case e: NoClassDefFoundError =>
          throw new IllegalArgumentException(
            s"$splitStyle is not a valid module split style",
            e.getCause
          )
      }
  }

  def main(args: Array[String]): Unit = {
    val parser = new scopt.OptionParser[Options]("scalajsld") {
      head("scalajsld", ScalaJSVersions.current)
      arg[File]("<value> ...")
        .unbounded()
        .action { (x, c) => c.copy(cp = c.cp :+ x) }
        .text("Entries of Scala.js classpath to link")
      opt[String]("mainMethod")
        .valueName("<full.name.Object.main>")
        .abbr("mm")
        .unbounded()
        .action { (x, c) =>
          val newModule = moduleInitializer(x, hasArgs = true)
          c.copy(moduleInitializers = c.moduleInitializers :+ newModule)
        }
        .text("Execute the specified main(Array[String]) method on startup")
      opt[String]("mainMethodWithNoArgs")
        .valueName("<full.name.Object.main>")
        .abbr("mma")
        .unbounded()
        .action { (x, c) =>
          val newModule = moduleInitializer(x, hasArgs = false)
          c.copy(moduleInitializers = c.moduleInitializers :+ newModule)
        }
        .text("Execute the specified main() method on startup")
      opt[File]('o', "output")
        .valueName("<file>")
        .action { (x, c) => c.copy(output = Some(x)) }
        .text("Output file of linker (deprecated)")
      opt[File]('z', "outputDir")
        .valueName("<dir>")
        .action { (x, c) => c.copy(outputDir = Some(x)) }
        .text("Output directory of linker (required)")
      // "By default, undefined behaviors are in Fatal mode for fastLinkJS and in Unchecked mode for fullLinkJS"
      // taken from: https://www.scala-js.org/doc/semantics.html#undefined-behaviors
      opt[Unit]('f', "fastOpt")
        .action { (_, c) =>
          c.copy(noOpt = false, fullOpt = false, semantics = Semantics.Defaults)
        }
        .text("Optimize code (this is the default)")
      opt[Unit]('n', "noOpt")
        .action { (_, c) => c.copy(noOpt = true, fullOpt = false) }
        .text("Don't optimize code")
      opt[String]("moduleSplitStyle")
        .action { (x, c) => c.copy(moduleSplitStyle = x) }
        .text(
          "Module splitting style " + ModuleSplitStyleRead.All
            .mkString("(", ", ", ")")
        )
      opt[Seq[String]]("smallModuleForPackages")
        .valueName("<package1>,<package2>...")
        .action((x, c) => c.copy(smallModuleForPackages = x))
        .text(
          "Create as many small modules as possible for the classes in the passed packages and their subpackages."
        )
      opt[String]("jsFilePattern")
        .action { (x, c) =>
          c.copy(outputPatterns = OutputPatterns.fromJSFile(x))
        }
        .text(
          "Pattern for JS file names (default: `%s.js`). " +
            "Expects a printf-style pattern with a single placeholder for the module ID. " +
            "A typical use case is changing the file extension, e.g. `%.mjs` for Node.js modules."
        )
      // "By default, undefined behaviors are in Fatal mode for fastLinkJS and in Unchecked mode for fullLinkJS"
      // taken from: https://www.scala-js.org/doc/semantics.html#undefined-behaviors
      opt[Unit]('u', "fullOpt")
        .action { (_, c) =>
          c.copy(
            noOpt = false,
            fullOpt = true,
            semantics = Semantics.Defaults.optimized
          )
        }
        .text("Fully optimize code (uses Google Closure Compiler)")
      opt[Unit]('p', "prettyPrint")
        .action { (_, c) => c.copy(prettyPrint = true) }
        .text("Pretty print full opted code (meaningful with -u)")
      opt[Unit]('s', "sourceMap")
        .action { (_, c) => c.copy(sourceMap = true) }
        .text("Produce a source map for the produced code")
      opt[Unit]("compliantAsInstanceOfs")
        .action { (_, c) =>
          c.copy(semantics = c.semantics.withAsInstanceOfs(Compliant))
        }
        .text("Use compliant asInstanceOfs")
      opt[Unit]("es2015")
        .action { (_, c) =>
          c.copy(esFeatures = c.esFeatures.withESVersion(ESVersion.ES2015))
        }
        .text("Use ECMAScript 2015")
      opt[String]("esVersion")
        .action { (esV, c) =>
          c.copy(esFeatures =
            c.esFeatures.withESVersion(EsVersionParser.parse(esV))
          )
        }
        .text("EsVersion " + EsVersionParser.All.mkString("(", ", ", ")"))
      opt[ModuleKind]('k', "moduleKind")
        .action { (kind, c) => c.copy(moduleKind = kind) }
        .text("Module kind " + ModuleKind.All.mkString("(", ", ", ")"))
      opt[Unit]('c', "checkIR")
        .action { (_, c) => c.copy(checkIR = true) }
        .text("Check IR before optimizing")
      opt[File]('r', "relativizeSourceMap")
        .valueName("<path>")
        .action { (x, c) => c.copy(relativizeSourceMap = Some(x.toURI)) }
        .text(
          "Relativize source map with respect to given path (meaningful with -s)"
        )
      opt[Unit]("noStdlib")
        .action { (_, c) => c.copy(stdLib = Nil) }
        .text("Don't automatically include Scala.js standard library")
      opt[String]("stdlib")
        .valueName("<scala.js stdlib jar>")
        .hidden()
        .action { (x, c) =>
          c.copy(stdLib = x.split(File.pathSeparator).map(new File(_)).toSeq)
        }
        .text(
          "Location of Scala.js standard libarary. This is set by the " +
            "runner script and automatically prepended to the classpath. " +
            "Use -n to not include it."
        )
      opt[String]("jsHeader")
        .action { (jsHeader, c) => c.copy(jsHeader = jsHeader) }
        .text("A header that will be added at the top of generated .js files")
      opt[Unit]('d', "debug")
        .action { (_, c) => c.copy(logLevel = Level.Debug) }
        .text("Debug mode: Show full log")
      opt[Unit]('q', "quiet")
        .action { (_, c) => c.copy(logLevel = Level.Warn) }
        .text("Only show warnings & errors")
      opt[Unit]("really-quiet")
        .abbr("qq")
        .action { (_, c) => c.copy(logLevel = Level.Error) }
        .text("Only show errors")
      version("version")
        .abbr("v")
        .text("Show scalajsld version")
      help("help")
        .abbr("h")
        .text("prints this usage text")
      checkConfig { c =>
        if (c.output.isDefined) {
          reportWarning(
            "using a single file as output (--output) is deprecated since Scala.js 1.3.0." +
              " Use --outputDir instead."
          )
        }

        if (c.outputDir.isDefined == c.output.isDefined)
          failure("exactly one of --output or --outputDir have to be defined")
        else
          success
      }

      override def showUsageOnError = Some(true)
    }

    for (options <- parser.parse(args, Options())) {
      val classpath = (options.stdLib ++ options.cp).map(_.toPath())
      val moduleInitializers = options.moduleInitializers

      val semantics =
        if (options.fullOpt) options.semantics.optimized
        else options.semantics
      val moduleSplitStyle = ModuleSplitStyleRead.moduleSplitStyleRead(
        options.moduleSplitStyle,
        options.smallModuleForPackages
      )

      val config = StandardConfig()
        .withSemantics(semantics)
        .withModuleKind(options.moduleKind)
        .withModuleSplitStyle(moduleSplitStyle)
        .withOutputPatterns(options.outputPatterns)
        .withESFeatures(options.esFeatures)
        .withCheckIR(options.checkIR)
        .withOptimizer(!options.noOpt)
        .withParallel(true)
        .withSourceMap(options.sourceMap)
        .withRelativizeSourceMapBase(options.relativizeSourceMap)
        .withClosureCompiler(options.fullOpt)
        .withPrettyPrint(options.prettyPrint)
        .withBatchMode(true)
        .withJSHeader(options.jsHeader)

      val linker = StandardImpl.linker(config)
      val logger = new ScalaConsoleLogger(options.logLevel)
      val cache = StandardImpl.irFileCache().newCache

      val result = PathIRContainer
        .fromClasspath(classpath)
        .flatMap(containers => cache.cached(containers._1))
        .flatMap { irFiles =>
          (options.output, options.outputDir) match {
            case (Some(jsFile), None) =>
              (DeprecatedLinkerAPI: DeprecatedLinkerAPI).link(
                linker,
                irFiles.toList,
                moduleInitializers,
                jsFile,
                logger
              )
            case (None, Some(outputDir)) =>
              linker.link(
                irFiles,
                moduleInitializers,
                PathOutputDirectory(outputDir.toPath()),
                logger
              )
            case _ =>
              throw new AssertionError(
                "Either output or outputDir have to be defined."
              )
          }
        }
      Await.result(result, Duration.Inf)
    }
  }

  // Covers deprecated api with not deprecated method. Suppresses warning.
  private abstract class DeprecatedLinkerAPI {
    def link(
        linker: Linker,
        irFiles: Seq[IRFile],
        moduleInitializers: Seq[ModuleInitializer],
        linkerOutputFile: File,
        logger: Logger
    ): Future[Unit]
  }

  private object DeprecatedLinkerAPI extends DeprecatedLinkerAPI {
    def apply(): DeprecatedLinkerAPI = this

    @deprecated("Deprecate to silence warnings", "never/always")
    def link(
        linker: Linker,
        irFiles: Seq[IRFile],
        moduleInitializers: Seq[ModuleInitializer],
        linkerOutputFile: File,
        logger: Logger
    ): Future[Unit] = {
      val js = linkerOutputFile.toPath()
      val sm = js.resolveSibling(js.getFileName().toString() + ".map")

      def relURI(f: Path) =
        new URI(null, null, f.getFileName().toString(), null)

      val output = LinkerOutput(PathOutputFile(js))
        .withSourceMap(PathOutputFile(sm))
        .withSourceMapURI(relURI(sm))
        .withJSFileURI(relURI(js))
      linker.link(irFiles, moduleInitializers, output, logger)
    }
  }
}
