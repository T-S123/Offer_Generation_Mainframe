#!/usr/bin/env python3
"""Prepare the CodeBuild ZIP from source and a narrow deployment-file allowlist."""

import argparse
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile


def files_for(root):
    """Yield only build inputs and checked-in demo assets."""
    for path in (
        "ai-service/pom.xml",
        "services/pom.xml",
        "marketing-service/pom.xml",
        "ai-service/config/mcp-servers.json",
        "scripts/run.sh",
        "scripts/bank-env.sh",
        "scripts/startup-guard.sh",
        "scripts/seed-presenter-a2a.py",
        "docs/aws-a2a/Dockerfile",
        "docs/aws-a2a/buildspec.yml",
        "docs/aws-a2a/boot.py",
        "docs/aws-a2a/bridge.py",
        "docs/aws-a2a/prepare-build.py",
    ):
        yield root / path
    for folder in (
        "ai-service/src/main",
        "services/src/main",
        "marketing-service/src/main",
        "app/cbl",
        "app/cpy",
        "app/data",
    ):
        yield from (path for path in (root / folder).rglob("*") if path.is_file())


def main():
    """Create a new ZIP and leave existing archives intact."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    inputs = sorted(files_for(root))
    for item in inputs:
        if not item.is_file() or not item.resolve().is_relative_to(root):
            raise SystemExit(f"Missing or external source file: {item}")
        if any(part.startswith(".") for part in item.relative_to(root).parts):
            raise SystemExit(f"Inspect unexpected hidden source input: {item}")
    with ZipFile(args.output, "x", ZIP_DEFLATED) as archive:
        for item in inputs:
            data = item.read_bytes()
            if item.suffix == ".sh":
                data = data.replace(b"\r\n", b"\n")
            archive.writestr(item.relative_to(root).as_posix(), data)
    print(f"{args.output.resolve()} ({len(inputs)} source files)")


if __name__ == "__main__":
    main()
