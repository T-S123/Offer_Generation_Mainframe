#!/usr/bin/env python3
"""Apply the deployment-only JDBC TLS and model-configuration adjustments."""

from pathlib import Path


def replace_once(path, old, new):
    """Fail the image build if the expected source expression has changed."""
    source = Path(path)
    content = source.read_text(encoding="utf-8")
    if content.count(old) != 1:
        raise SystemExit(f"Expected one reviewed expression in {source}")
    source.write_text(content.replace(old, new), encoding="utf-8")


for adapter in (
    "services/src/main/java/com/lending/engine/bureau/infrastructure/BureauDatabase.java",
    "marketing-service/src/main/java/com/lending/offers/infrastructure/Database.java",
):
    replace_once(
        adapter,
        '+uri.getRawPath());',
        '+uri.getRawPath()+(uri.getRawQuery()==null?"":"?"+uri.getRawQuery()));',
    )

replace_once(
    "ai-service/src/main/java/com/lending/ai/AstraClient.java",
    'public static final String MODEL="gpt-6-astra";',
    'public static final String MODEL=System.getenv().getOrDefault("AI_MODEL","gpt-6-astra");',
)
