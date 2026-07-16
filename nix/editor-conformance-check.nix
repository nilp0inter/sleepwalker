# No-hardware contract tests for the Editor conformance runner.
{ runCommand, python3 }:
let
  testPython = python3.withPackages (ps: [ ps.hypothesis ps.pytest ]);
in
runCommand "sleepwalker-editor-conformance-check" {
  nativeBuildInputs = [ testPython ];
} ''
  export SLEEPWALKER_EDITOR_RUNNER=${./smoke-editor-conformance.py}
  python3 -m pytest ${./tests/test_smoke_editor_conformance.py} \
    -q -p no:cacheprovider
  touch "$out"
''
