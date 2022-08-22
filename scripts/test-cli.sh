#! /bin/sh

if [[ "$OSTYPE" == "msys" ]]; then
  mill="./mill.bat"
else
  mill="./mill"
fi

launcherMillCommand="Cli.standaloneLauncher"
launcherName="scala-js-cli"

"$mill" -i copyTo "$launcherMillCommand" "$launcherName" 1>&2

ls .

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

"./$launcherName" -s -o test.js -mm Foo.main bin 2> test_stderr.txt
grep -Fxq "Warning: using a single file as output (--output) is deprecated since Scala.js 1.3.0. Use --outputDir instead. " test_stderr.txt \
  || fail "expected warning. Got: $(cat test_stderr.txt)"
test -s test.js || fail "scalajsld: empty output"
test -s test.js.map || fail "scalajsld: empty source map"

node test.js > got-legacy.run
cat > want-legacy.run <<EOF
asdf 2
EOF

diff got-legacy.run want-legacy.run

mkdir test-output
"$launcherName" -s --outputDir test-output --moduleSplitStyle SmallestModules --moduleKind CommonJSModule -mm Foo.main bin
test "$(ls test-output/*.js| wc -w)" -gt "1" || fail "scalajsld: produced single js output file"

node test-output/main.js > got.run
cat > want.run <<EOF
asdf 2
EOF

diff got.run want.run
