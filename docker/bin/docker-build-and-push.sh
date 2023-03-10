#!/usr/bin/env bash

set -e
(
    cd $(dirname $0)/..

    clean=0
    latest=0

    for arg in "$@"
    do
        case $arg in
            --clean)
            clean=1
            shift
            ;;
            --latest)
            latest=1
            shift
            ;;
            *)
            shift
            ;;
        esac
    done

    if [ "$1" == "--clean" ] || ! [ -e build/libs/core2-standalone.jar ]; then
        ../gradlew :docker:shadowJar
    fi

    # TODO, consider git write-tree dance to hash uncommitted changes, just in case
    sha=$(git rev-parse --short HEAD)

    echo Building Docker image...

    if [ "$latest" -eq "1" ]; then
      docker buildx build --push --platform linux/arm/v7,linux/arm64/v8,linux/amd64 --tag ghcr.io/xtdb/core2:"$sha" --tag ghcr.io/xtdb/core2:latest --build-arg GIT_SHA="$sha" --build-arg CORE2_VERSION="${CORE2_VERSION:-dev-SNAPSHOT}" .
    else
      docker buildx build --push --platform linux/arm/v7,linux/arm64/v8,linux/amd64 --tag ghcr.io/xtdb/core2:"$sha" --build-arg GIT_SHA="$sha" --build-arg CORE2_VERSION="${CORE2_VERSION:-dev-SNAPSHOT}" .
    fi
    echo Done
)