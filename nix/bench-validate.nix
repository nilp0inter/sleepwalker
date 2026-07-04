# sleepwalker-bench-validate: no-hardware bench configuration validator.
#
# Parses and validates a bench TOML configuration file without touching
# hardware. Reports structured missing-field errors before any HIL operation
# begins. This is the no-hardware side of the agent-operated HIL contract.
{ lib, python3, python3Packages, stdenvNoCC }:
let
  protocolPkg = python3Packages.callPackage ./protocol-pkg.nix { };
in
stdenvNoCC.mkDerivation {
  pname = "sleepwalker-bench-validate";
  version = "0.1.0";

  dontUnpack = true;
  dontBuild = true;

  buildInputs = [ python3 protocolPkg ];

  installPhase = ''
    runHook preInstall
    mkdir -p $out/bin
    cat > $out/bin/sleepwalker-bench-validate <<'PYEOF'
    #!${python3}/bin/python3
    """sleepwalker-bench-validate: validate a sleepwalker bench TOML file.

    Reports structured missing-field errors before any HIL operation
    touches hardware. Exits non-zero on any validation failure.
    """
    import sys
    import json
    from pathlib import Path

    try:
        import tomllib  # Python 3.11+
    except ModuleNotFoundError:  # pragma: no cover
        import tomli as tomllib  # type: ignore

    REQUIRED_FIELDS = {
        "android": ["adb_serial"],
        "esp": ["uart_port", "flash_port"],
        "hid_observer": ["ssh_target"],
        "hid_match": ["vid", "pid"],
        "artifacts": ["dir"],
    }

    def fail(reason: str, field: str | None = None) -> int:
        payload = {"ok": False, "reason": reason}
        if field is not None:
            payload["field"] = field
        print(json.dumps(payload), file=sys.stderr)
        return 2

    def main(argv: list[str]) -> int:
        if len(argv) != 2:
            return fail("usage: sleepwalker-bench-validate <bench.toml>")
        path = Path(argv[1])
        if not path.exists():
            return fail("bench config file not found", str(path))
        try:
            with path.open("rb") as fh:
                cfg = tomllib.load(fh)
        except Exception as exc:
            return fail(f"toml parse error: {exc}")
        missing = []
        for section, fields in REQUIRED_FIELDS.items():
            sect = cfg.get(section)
            if sect is None:
                missing.append(f"{section}.*")
                continue
            for f in fields:
                if f not in sect or sect[f] in (None, ""):
                    missing.append(f"{section}.{f}")
        if missing:
            return fail("missing required fields: " + ", ".join(missing))
        print(json.dumps({"ok": True, "fields": sorted(
            f"{s}.{f}" for s, fs in REQUIRED_FIELDS.items() for f in fs
        )}))
        return 0

    if __name__ == "__main__":
        raise SystemExit(main(sys.argv))
    PYEOF
    chmod +x $out/bin/sleepwalker-bench-validate
    runHook postInstall
  '';

  meta = with lib; {
    description = "No-hardware sleepwalker bench configuration validator";
    mainProgram = "sleepwalker-bench-validate";
    platforms = platforms.linux;
  };
}