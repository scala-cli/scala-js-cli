/*                     __                                                              *\
**     ________ ___   / /  ___      __ ____  Scala.js CLI                              **
**    / __/ __// _ | / /  / _ | __ / // __/  (c) 2013-2014, LAMP/EPFL                  **
**  __\ \/ /__/ __ |/ /__/ __ |/_// /_\ \    (c) 2017-2022 Scala.js Sébastien Doeraene **
** /____/\___/_/ |_/____/_/ | |__/ /____/    http://scala-js.org/                      **
**                          |/____/                                                    **
\*                                                                                     */

package org.scalajs.cli

import org.scalajs.ir.ScalaJSVersions
import org.scalajs.ir.Trees.{Tree, ClassDef}
import org.scalajs.ir.Printers.IRTreePrinter

import org.scalajs.linker._
import org.scalajs.linker.interface._
import org.scalajs.linker.interface.unstable.IRFileImpl
import org.scalajs.linker.standard._

import scala.collection.immutable

import scala.concurrent._
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global

import scala.util.{Failure, Success}

import java.io.{Console => _, _}
import java.util.zip.{ZipFile, ZipEntry}
import java.nio.file.Path

object Scalajsp {

  private case class Options(
      jar: Option[File] = None,
      fileNames: immutable.Seq[String] = Nil
  )

  def main(args: Array[String]): Unit = {
    val parser = new scopt.OptionParser[Options]("scalajsp") {
      head("scalajsp", ScalaJSVersions.current)
      arg[String]("<file> ...")
        .unbounded()
        .action { (x, c) => c.copy(fileNames = c.fileNames :+ x) }
        .text("*.sjsir file to display content of")
      opt[File]('j', "jar")
        .valueName("<jar>")
        .action { (x, c) => c.copy(jar = Some(x)) }
        .text("Read *.sjsir file(s) from the given JAR.")
      opt[Unit]('s', "supported")
        .action { (_, _) =>
          printSupported(); exit(0)
        }
        .text("Show supported Scala.js IR versions")
      version("version")
        .abbr("v")
        .text("Show scalajsp version")
      help("help")
        .abbr("h")
        .text("prints this usage text")

      override def showUsageOnError = Some(true)
    }

    for {
      options <- parser.parse(args, Options())
      fileName <- options.fileNames
    } {
      val vfile = options.jar
        .map { jar =>
          readFromJar(jar, fileName)
        }
        .getOrElse {
          readFromFile(fileName)
        }

      displayFileContent(Await.result(vfile, Duration.Inf), options)
    }
  }

  private def printSupported(): Unit = {
    import ScalaJSVersions._
    println(s"Scala.js IR library version is: $current")
    println(s"Supports Scala.js IR versions up to $binaryEmitted")
  }

  private def displayFileContent(vfile: IRFile, opts: Options): Unit = {
    val tree = Await.result(IRFileImpl.fromIRFile(vfile).tree, Duration.Inf)
    new IRTreePrinter(stdout).print(tree)
    stdout.write('\n')
    stdout.flush()
  }

  private def fail(msg: String): Nothing = {
    Console.err.println(msg)
    exit(1)
  }

  private def exit(code: Int): Nothing = {
    System.exit(code)
    throw new AssertionError("unreachable")
  }

  private def readFromFile(fileName: String): Future[IRFile] = {
    val file = new File(fileName)

    if (!file.exists) {
      fail(s"No such file: $fileName")
    } else if (!file.canRead) {
      fail(s"Unable to read file: $fileName")
    } else {
      PathIRFile(file.toPath())
    }
  }

  private def readFromJar(jar: File, name: String): Future[IRFile] = {
    /* This could be more efficient if we only read the relevant entry. But it
     * probably does not matter, and this implementation is very simple.
     */

    def findRequestedClass(sjsirFiles: Seq[IRFile]): Future[IRFile] = {
      Future
        .traverse(sjsirFiles) { irFile =>
          val ir = IRFileImpl.fromIRFile(irFile)
          ir.entryPointsInfo
            .map { i =>
              if (i.className.nameString == name) Success(Some(ir))
              else Success(None)
            }
            .recover { case t => Failure(t) }
        }
        .map { irs =>
          irs
            .collectFirst { case Success(Some(f)) =>
              f
            }
            .getOrElse {
              fail(s"No such class in jar: $name")
            }
        }
    }

    val cache = StandardImpl.irFileCache().newCache

    for {
      (containers, _) <- PathIRContainer.fromClasspath(jar.toPath() :: Nil)
      irFiles <- cache.cached(containers)
      requestedFile <- findRequestedClass(irFiles)
    } yield {
      requestedFile
    }
  }

  private val stdout =
    new BufferedWriter(new OutputStreamWriter(Console.out, "UTF-8"))

}
