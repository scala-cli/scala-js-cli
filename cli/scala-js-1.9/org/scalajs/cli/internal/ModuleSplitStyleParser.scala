package org.scalajs.cli.internal

import org.scalajs.linker.interface.{ModuleSplitStyle => ActualModuleSplitStyle}

// class rather than object, as that's easier to substitute from native-image
class ModuleSplitStyleParser {
  def tryParse(splitStyle: String): Option[ModuleSplitStyle] =
    if (splitStyle == ActualModuleSplitStyle.FewestModules.toString)
      Some(ModuleSplitStyle(ActualModuleSplitStyle.FewestModules))
    else if (splitStyle == ActualModuleSplitStyle.SmallestModules.toString)
      Some(ModuleSplitStyle(ActualModuleSplitStyle.SmallestModules))
    else
      None
  def parse(splitStyle: String, modulePackages: Array[String]): ModuleSplitStyle =
    tryParse(splitStyle).getOrElse {
      if (splitStyle == "SmallModulesFor")
        throw new IllegalArgumentException(s"SmallModuleFor style not supported for Scala.js < 1.10")
      else
        throw new IllegalArgumentException(s"$splitStyle is not a valid module split style")
    }
}