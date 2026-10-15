#!/usr/bin/env bash
#
# Builds an OpenGGF checkout and installs its jar and POM into this project's
# private Maven repository (.m2/repository, set in .mvn/maven.config). Your own
# ~/.m2, including any OpenGGF artifacts other projects installed there, is
# neither read nor changed for the engine.
#
# Usage: scripts/install-engine.sh <path to an OpenGGF checkout>
# The checkout needs the editor window-chrome extension point (EditorWindowChrome),
# on OpenGGF develop.

set -euo pipefail

engine="${1:?usage: scripts/install-engine.sh <path to an OpenGGF checkout>}"
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
engine="$(cd -- "$engine" && pwd)"

if [[ ! -f "$engine/src/main/java/com/openggf/editor/EditorWindowChrome.java" ]]; then
    echo "$engine has no EditorWindowChrome; check out OpenGGF develop" >&2
    exit 1
fi

(cd "$engine" && mvn -q -Dmse=off -DskipTests package)
version="$(cd "$engine" && mvn -q -Dmse=off -DforceStdout help:evaluate -Dexpression=project.version)"
jar="$engine/target/OpenGGF-$version.jar"
[[ -f "$jar" ]] || { echo "Engine jar not found: $jar" >&2; exit 1; }

cd "$here"
mvn -q install:install-file -Dfile="$jar" -DpomFile="$engine/pom.xml"
echo "Installed OpenGGF $version from $engine into $here/.m2/repository"
