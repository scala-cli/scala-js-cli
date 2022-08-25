#!/usr/bin/env bash
set -ev

launcher="$1"

if [ "$launcher" == "" ]; then
  echo "Usage: $0 launcher" 1>&2
  exit 1
fi

echo "Using launcher $launcher"

fail() {
    echo "$1" >&2
    exit 2
}

# Actual test.
mkdir bin
cat > foo.scala <<'EOF'
object Foo {
  def main(args: Array[String]): Unit = {
    println(s"asdf ${1 + 1}")
    new A
  }

  class A
}
EOF

cs launch scalac:2.13.6 -- \
  -classpath "$(cs fetch --intransitive org.scala-js::scalajs-library:1.9.0)" \
  -Xplugin:"$(cs fetch --intransitive org.scala-js:scalajs-compiler_2.13.6:1.9.0)" \
  -d bin foo.scala

"$launcher" --stdlib "$(cs fetch --intransitive org.scala-js::scalajs-library:1.9.0)" -s -o test.js -mm Foo.main bin 2> test_stderr.txt || cat test_stderr.txt
grep -Fxq "Warning: using a single file as output (--output) is deprecated since Scala.js 1.3.0. Use --outputDir instead." test_stderr.txt \
  || fail "expected warning. Got: $(cat test_stderr.txt)"
test -s test.js || fail "scalajsld: empty output"
test -s test.js.map || fail "scalajsld: empty source map"

node test.js > got-legacy.run
cat > want-legacy.run <<EOF
asdf 2
EOF

diff got-legacy.run want-legacy.run

mkdir test-output
"$launcher" --stdlib "$(cs fetch --intransitive org.scala-js::scalajs-library:1.9.0)" -s --outputDir test-output --moduleSplitStyle SmallestModules --moduleKind CommonJSModule -mm Foo.main bin
test "$(ls test-output/*.js| wc -w)" -gt "1" || fail "scalajsld: produced single js output file"

node test-output/main.js > got.run
cat > want.run <<EOF
asdf 2
EOF

diff got.run want.run
