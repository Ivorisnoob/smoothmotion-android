#!/usr/bin/env python3
"""Compile every engine shader as GLSL ES 3.10 with glslangValidator.

The shaders live as Kotlin string constants in ComputeMotionEngine.kt and are
only compiled by a phone's driver at run time, so a typo would otherwise ship.
This reads them straight from the Kotlin source, so it checks exactly what
runs. No GPU needed.

    python3 tools/validate_shaders.py          # exit code 0 when all pass

Needs glslangValidator (apt install glslang-tools, brew install glslang).
"""
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ENGINE = Path(__file__).resolve().parent.parent / (
    "smoothmotion-core/src/main/kotlin/io/github/ivorisnoob/smoothmotion/core/ComputeMotionEngine.kt"
)

# Which stage each program's source is, from the engine's build(): EsProgram(VERTEX, X) is a
# fragment shader, EsProgram(null, X) a compute shader.
PROGRAM = re.compile(r"EsProgram\((VERTEX|null), ([A-Z_0-9]+)\)")
CONST = re.compile(r'const val ([A-Z_0-9]+) = ((?:[A-Z_0-9]+ \+ )*)(?:"""(.*?)"""|"((?:[^"\\]|\\.)*)")', re.S)


def main() -> int:
    if shutil.which("glslangValidator") is None:
        print("glslangValidator not found: apt install glslang-tools / brew install glslang", file=sys.stderr)
        return 2
    source = ENGINE.read_text()
    consts = {}
    for name, prefix, raw, quoted in CONST.findall(source):
        body = raw if raw else quoted.encode().decode("unicode_escape")
        parts = [consts[p] for p in prefix.split(" + ") if p]
        consts[name] = "".join(parts) + body
    stages = {name: ("frag" if kind == "VERTEX" else "comp") for kind, name in PROGRAM.findall(source)}
    stages["VERTEX"] = "vert"
    if len(stages) < 13:
        print(f"found only {len(stages)} programs; has build() changed shape?", file=sys.stderr)
        return 2
    failed = 0
    with tempfile.TemporaryDirectory() as tmp:
        for name, stage in sorted(stages.items()):
            path = Path(tmp) / f"{name.lower()}.{stage}"
            path.write_text(consts[name])
            result = subprocess.run(["glslangValidator", str(path)], capture_output=True, text=True)
            ok = result.returncode == 0
            failed += not ok
            print(f"{'ok  ' if ok else 'FAIL'} {name} ({stage})")
            if not ok:
                print(result.stdout.strip() or result.stderr.strip())
    print(f"{len(stages) - failed}/{len(stages)} shaders compile as GLSL ES 3.10")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
