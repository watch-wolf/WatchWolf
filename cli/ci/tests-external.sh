#!/bin/bash
#
# Smoke-tests every external dependency this module has, without doing the slow, expensive part of
# a real build. "External dependency" here means: something outside this repo that the CLI, or its
# own Docker image, reaches out to and could break out from under us with no warning -- Maven
# Central and the base images the Dockerfile pulls, hub.spigotmc.org, fill.papermc.io,
# watchwolf.dev, and the eclipse-temurin JDK images. It deliberately does NOT build every published
# Spigot/Paper server version -- Spigot alone is about an hour per version -- only that fetching the
# *list* of versions (and, for the JDK images, actually pulling them) still works.
#
# Calls into build.sh/tests.sh rather than duplicating their machinery, same as every other ci/
# script: this only decides WHAT to run.

script_path=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
source "$script_path/common.sh"

# The classes covering "does this external thing still respond the way we assume" -- listing
# published versions/plugins/builds, and (the one non-network dependency here) that the JDK image
# tags PullJdkImagesStep pulls still exist. Kept as one place to extend: if a new remote client or
# external-image dependency shows up with no system test yet, add its IT* class here.
external_dependency_tests="ITSpigotHubClientShould,ITPaperApiClientShould,ITWatchWolfWebClientShould,ITPullJdkImagesShould"

overall=0

echo "[v] Building the watchwolf-cli Docker image -- exercises every build-time external"
echo "    dependency (Maven Central, the eclipse-temurin base images, Docker's own static"
echo "    docker/compose binaries, the apt packages the Dockerfile installs)..."
"$script_path/build.sh" --image
result=$?
if [ $result -ne 0 ]; then
    echo "[e] Building the image failed" >&2
    overall=$result
fi

echo "[v] Running the external-dependency system tests: $external_dependency_tests"
"$script_path/tests.sh" --integration --tests "$external_dependency_tests"
result=$?
if [ $result -ne 0 ]; then
    echo "[e] The external-dependency system tests failed" >&2
    overall=$result
fi

if [ $overall -ne 0 ]; then
    exit $overall
fi
echo "[i] Done"
