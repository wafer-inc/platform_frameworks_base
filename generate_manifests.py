import argparse
import os
from pathlib import Path


def generate_manifest(path: str):
    path = Path(path)
    system_core_manifest = """
<?xml version="1.0" encoding="UTF-8"?>
<manifest>
<!-- Remove the original -->
<remove-project name="platform/system/core" />

<!-- Define the remote -->
<remote name="wafer"
        fetch="https://github.com/wafer-inc"
        review="" />

<!-- Add your GitHub repository -->
<project path="system/core"
         name="platform_system_core"
         remote="wafer"
         revision="wafer" />
</manifest>
"""

    with open(path / "wafer_system_core.xml", "w") as f:
        f.write(system_core_manifest.strip())

    platform_manifest = f"""
<?xml version="1.0" encoding="UTF-8"?>
<manifest>
<!-- Remove the original -->
<remove-project name="platform/frameworks/base" />

<!-- Define the remote -->
<remote name="wafer"
        fetch="https://github.com/wafer-inc"
        review="" />

<!-- Add your GitHub repository -->
<project path="frameworks/base"
         name="platform_frameworks_base"
         remote="wafer"
         revision="{os.environ["PRIMITIVE_GIT_SHA"]}" />
</manifest>
    """

    with open(path / "wafer_frameworks_base.xml", "w") as f:
        f.write(platform_manifest.strip())


if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="Generate manifests at a specific path."
    )
    parser.add_argument("path", help="The path to use for the manifests.")
    args = parser.parse_args()
    generate_manifest(args.path)
