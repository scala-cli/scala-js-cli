package org.scalajs.cli.internal

import org.scalajs.linker.interface.{ModuleSplitStyle => ActualModuleSplitStyle}

// As the original ModuleSplitStyle is in a package with 'interface' as a component,
// it can't be referenced from Java code, so we use this class instead.
final case class ModuleSplitStyle(underlying: ActualModuleSplitStyle)
