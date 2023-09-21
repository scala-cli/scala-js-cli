package org.scalajs.cli.internal

import org.scalajs.linker.interface.ESVersion

import java.util.Locale

object EsVersionParser {
  def parse(esVersion: String): ESVersion =
    esVersion.trim.toLowerCase(Locale.ROOT) match {
        case "es5_1" => ESVersion.ES5_1
        case "es2015" => ESVersion.ES2015
        case "es2016" => ESVersion.ES2016
        case "es2017" => ESVersion.ES2017
        case "es2018" => ESVersion.ES2018
        case "es2019" => ESVersion.ES2019
        case "es2020" => ESVersion.ES2020
        case "es2021" => ESVersion.ES2021
        case unknown => throw new IllegalArgumentException(s"Warning: unrecognized argument: $unknown for --esVersion parameter")
      }

  val All: List[ESVersion] =
    List(ESVersion.ES5_1, ESVersion.ES2015, ESVersion.ES2016, ESVersion.ES2017, ESVersion.ES2018, ESVersion.ES2019, ESVersion.ES2020, ESVersion.ES2021)
}
