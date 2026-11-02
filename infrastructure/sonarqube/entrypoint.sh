#!/bin/sh
set -eu

plugins_dir="${SQ_EXTENSIONS_DIR:-/opt/sonarqube/extensions}/plugins"
mkdir -p "$plugins_dir"

prepare_plugin() {
    name="$1"
    jar="$2"
    pattern="$3"
    alternate_pattern="$4"
    for pending in "$plugins_dir"/../downloads/$pattern "$plugins_dir"/../downloads/$alternate_pattern; do
        if [ -f "$pending" ]; then
            echo "Pending $name Marketplace download: $pending. Cancel it before using the pinned image." >&2
            exit 1
        fi
    done
    source_jar="/opt/insurance/plugins/$jar"
    target_jar="$plugins_dir/$jar"
    # Fail visibly instead of loading two versions or deleting operator-managed files.
    for existing in "$plugins_dir"/$pattern "$plugins_dir"/$alternate_pattern; do
        [ -f "$existing" ] || continue
        if [ "$existing" != "$target_jar" ]; then
            echo "Conflicting $name plugin: $existing. Review it before changing the pinned version." >&2
            exit 1
        fi
    done
    if [ -f "$target_jar" ]; then
        if ! cmp -s "$source_jar" "$target_jar"; then
            echo "$name checksum differs from the image: $target_jar. Review the existing file." >&2
            exit 1
        fi
    else
        install -m 0444 "$source_jar" "$target_jar"
    fi
    echo "$name prepared from the image; no runtime download."
}
prepare_plugin "Mutation Analysis" mutation-analysis-plugin-1.8.jar 'mutation-analysis-plugin*.jar' 'sonar-mutationanalysis-plugin*.jar'
prepare_plugin "Checkstyle" checkstyle-sonar-plugin-14.3.0.jar 'checkstyle-sonar-plugin*.jar' 'sonar-checkstyle-plugin*.jar'
exec /opt/sonarqube/docker/entrypoint.sh "$@"
