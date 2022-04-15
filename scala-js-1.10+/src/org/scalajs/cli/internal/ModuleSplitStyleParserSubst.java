package org.scalajs.cli.internal;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

@TargetClass(className = "org.scalajs.cli.internal.ModuleSplitStyleParser")
final class ModuleSplitStyleParserSubst {

    @Substitute
    ModuleSplitStyle parse(String splitStyle, String[] modulePackages) {
      ModuleSplitStyleParser110Plus parser = new ModuleSplitStyleParser110Plus();
      return parser.parse(splitStyle, modulePackages);
    }
}
