#!/bin/bash

# default variables
preclean=0
build_image=0

# parse params
while [[ "$#" -gt 0 ]]; do
    case $1 in
        --preclean) preclean=1 ;;
        --image) build_image=1 ;;

        *) echo "[e] Unknown parameter passed: $1" >&2 ; exit 1 ;;
    esac
    shift
done

script_path=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
source "$script_path/common.sh"

echo "[v] Compiling WatchWolf CLI..."

if [ $preclean -eq 1 ]; then
    ww_mvn clean
fi

# the tests are run by ci/tests.sh; this only produces the executable jar
ww_mvn package -Dmaven.test.skip=true
result=$?

if [ $result -ne 0 ]; then
    echo "[e] Exception while compiling WW-CLI" >&2
    exit $result
fi

if [ ! -f "$WW_BASE_PATH/target/watchwolf-cli.jar" ]; then
    echo "[e] Compilation reported success but target/watchwolf-cli.jar is missing." >&2
    exit 1
fi
echo "[i] Built $WW_BASE_PATH/target/watchwolf-cli.jar"

if [ $build_image -eq 1 ]; then
    # "watchwolf-cli:local" -- no namespace prefix, matching cli/watchwolf's own tag. There is no
    # published WatchWolf CLI image; a namespaced tag like "watchwolf/cli:latest" would look like
    # one, and defaulting anything to that name is a supply-chain risk (see cli/watchwolf's
    # header). Also tag with the pom version, purely for keeping local builds apart -- still no
    # registry namespace implied.
    version=$(grep -m1 -oP '(?<=<version>)[^<]+' "$WW_BASE_PATH/pom.xml")
    echo "[v] Building Docker image watchwolf-cli:local ..."
    docker build --tag "watchwolf-cli:local" --tag "watchwolf-cli:$version" "$WW_BASE_PATH"
    if [ $? -ne 0 ]; then
        echo "[e] Exception while building the WW-CLI image" >&2
        exit 1
    fi
    echo "[i] Built image watchwolf-cli:local (also tagged watchwolf-cli:$version)"
fi
