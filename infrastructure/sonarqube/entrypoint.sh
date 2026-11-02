#!/bin/sh
set -eu

source_jar=/opt/insurance/plugins/mutation-analysis-plugin-1.8.jar
plugins_dir="${SQ_EXTENSIONS_DIR:-/opt/sonarqube/extensions}/plugins"
target_jar="$plugins_dir/mutation-analysis-plugin-1.8.jar"

mkdir -p "$plugins_dir"

# Fail visibly instead of loading two versions or deleting an operator-managed plugin.
for existing in "$plugins_dir"/mutation-analysis-plugin*.jar "$plugins_dir"/sonar-mutationanalysis-plugin*.jar; do
    [ -f "$existing" ] || continue
    if [ "$existing" != "$target_jar" ]; then
        echo "Conflicting Mutation Analysis plugin: $existing. Review it before changing the pinned version." >&2
        exit 1
    fi
done

if [ -f "$target_jar" ]; then
    if ! cmp -s "$source_jar" "$target_jar"; then
        echo "Mutation Analysis checksum differs from the image: $target_jar. Review the existing file." >&2
        exit 1
    fi
else
    install -m 0444 "$source_jar" "$target_jar"
fi

echo "Mutation Analysis 1.8 prepared from the image; no runtime download."
# Retain the upstream entrypoint, signal handling and optional command overrides.
exec /opt/sonarqube/docker/entrypoint.sh "$@"
