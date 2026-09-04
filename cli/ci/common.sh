#!/bin/bash
# Shared bits for the CLI's dockerized build/test scripts.
# Nothing here runs on the host except Docker itself -- that is the whole point of the module.

# This module is Java 17 (picocli + lanterna + docker-java), unlike WW-Core and WW-Tester which
# are Java 8 and use maven:3.8.4-openjdk-8.
export WW_MAVEN_IMAGE="${WW_MAVEN_IMAGE:-maven:3.9-eclipse-temurin-17}"

ci_script_path=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
export WW_BASE_PATH=$(dirname "$ci_script_path")

# Maven's local repository.
#
# The other WatchWolf ci/ scripts run Maven as root, which leaves $HOME/.m2 owned by root. We run
# as the invoking user (so nothing this script produces is root-owned), and that makes a root-owned
# cache unusable -- Maven cannot write to it. Share it when we can, fall back to our own when we
# cannot, and say which.
export WW_M2_PATH="$HOME/.m2"
if [ -e "$WW_M2_PATH" ] && [ ! -w "$WW_M2_PATH" ]; then
    export WW_M2_PATH="${XDG_CACHE_HOME:-$HOME/.cache}/watchwolf-cli/m2"
    if [ ! -d "$WW_M2_PATH" ]; then
        echo "[w] $HOME/.m2 is not writable by $(id -un) (the other WatchWolf ci/ scripts run"
        echo "[w] Maven as root). Using $WW_M2_PATH instead, so nothing here ends up root-owned."
    fi
fi
mkdir -p "$WW_M2_PATH"

# docker needs a TTY only when a human is watching; keeping "-it" unconditionally
# breaks these scripts under CI or any non-interactive shell
export WW_TTY_FLAGS=""
if [ -t 1 ]; then WW_TTY_FLAGS="-it"; fi

# Does the checkout live on a filesystem Docker cannot write reliably?
#
# On a CIFS/SMB (or NFS) mount, the thousands of small concurrent writes a Maven build makes into
# a bind mount intermittently land as ZERO-LENGTH files -- javac "succeeds", a handful of .class
# files are empty, and the jar then dies at run time with "ClassFormatError: Truncated class file".
# A different file each build, which is a miserable thing to debug.
#
# So on such a filesystem we build on the container's own disk and copy only the finished
# artefacts back: a few large sequential writes, which these mounts handle fine.
ww_needs_isolated_build() {
    case "$(stat -f -c '%T' "$WW_BASE_PATH" 2>/dev/null)" in
        smb2|smb|cifs|nfs|fuseblk|9p) return 0 ;;
        *) return 1 ;;
    esac
}

# Common docker flags as an ARRAY, not a string -- a string built with $(...) and then left
# unquoted for word-splitting is exactly how "-B --no-transfer-progress" once got mangled into
# two words, one of which docker run then rejected as an unknown flag of its own. An array holds
# each flag as one element regardless of the spaces inside it, so nothing downstream can misparse it.
ww_docker_common_flags=(
    --user "$(id -u):$(id -g)"
    -v "$WW_M2_PATH:/var/maven/.m2"
    -e MAVEN_CONFIG=/var/maven/.m2
)

# -B (batch mode, no interactive prompts) and --no-transfer-progress (skip the byte-by-byte
# download spam) are plain mvn arguments, not something any MAVEN_* env var controls -- there is
# no such thing as MAVEN_ARGS. They go directly on the command line in every ww_mvn* function.
ww_mvn_quiet_flags=(-B --no-transfer-progress)

# Run Maven against the project.
#
# Directly on a local filesystem; via a container-local copy on a network one (see above). Either
# way Maven runs AS THE INVOKING USER, so nothing this produces is root-owned -- unlike the other
# WatchWolf ci/ scripts, which is why $HOME/.m2 is root-owned on a typical dev machine.
#
# $@ = the maven goals and flags
ww_mvn() {
    if ww_needs_isolated_build; then
        ww_mvn_isolated "$@"
    else
        local tty=(); [ -n "$WW_TTY_FLAGS" ] && tty=("$WW_TTY_FLAGS")
        docker run "${tty[@]}" --rm "${ww_docker_common_flags[@]}"              \
            -v "$WW_BASE_PATH:/compile"                                        \
            "$WW_MAVEN_IMAGE"                                                  \
            mvn "${ww_mvn_quiet_flags[@]}" "$@" -Duser.home=/var/maven --file /compile
    fi
}

# Same, with the Docker socket and host networking -- only the system tests need this.
# The extra flags are communicated to ww_mvn_isolated through WW_EXTRA_DOCKER_FLAGS (an array
# name, not a string) rather than a parameter, so no layer of this ever has to re-word-split them.
ww_mvn_with_docker() {
    local sock="${DOCKER_HOST#unix://}"
    [ -n "$sock" ] || sock=/var/run/docker.sock
    local docker_gid
    docker_gid=$(stat -c '%g' "$sock" 2>/dev/null || echo 0)

    if ww_needs_isolated_build; then
        WW_EXTRA_DOCKER_FLAGS=(--network host --group-add "$docker_gid" -v "$sock:/var/run/docker.sock")
        ww_mvn_isolated "$@"
        unset WW_EXTRA_DOCKER_FLAGS
    else
        local tty=(); [ -n "$WW_TTY_FLAGS" ] && tty=("$WW_TTY_FLAGS")
        docker run "${tty[@]}" --rm "${ww_docker_common_flags[@]}"              \
            --network host --group-add "$docker_gid"                          \
            -v "$sock:/var/run/docker.sock"                                    \
            -v "$WW_BASE_PATH:/compile"                                        \
            "$WW_MAVEN_IMAGE"                                                  \
            mvn "${ww_mvn_quiet_flags[@]}" "$@" -Duser.home=/var/maven --file /compile
    fi
}

# Copy the sources onto the container's own disk, build there, copy the artefacts back.
# $@ = maven goals and flags. Reads WW_EXTRA_DOCKER_FLAGS (an array) if the caller set one.
ww_mvn_isolated() {
    local tty=(); [ -n "$WW_TTY_FLAGS" ] && tty=("$WW_TTY_FLAGS")
    local extra=(); [ "${WW_EXTRA_DOCKER_FLAGS+set}" = set ] && extra=("${WW_EXTRA_DOCKER_FLAGS[@]}")

    docker run "${tty[@]}" --rm "${ww_docker_common_flags[@]}" "${extra[@]}"    \
        -v "$WW_BASE_PATH:/mounted"                                            \
        -e WW_MVN_ARGS="${ww_mvn_quiet_flags[*]} $*"                           \
        "$WW_MAVEN_IMAGE" bash -c '
            set -e
            mkdir -p /tmp/build
            cp -a /mounted/pom.xml /tmp/build/
            cp -a /mounted/src /tmp/build/
            # carry any previous reports forward, so the report-rendering goals can see the
            # results the test run just produced
            if [ -d /mounted/target ]; then
                mkdir -p /tmp/build/target
                cp -a /mounted/target/. /tmp/build/target/ 2>/dev/null || true
            fi
            cd /tmp/build

            set +e
            mvn $WW_MVN_ARGS -Duser.home=/var/maven --file /tmp/build/pom.xml
            result=$?
            set -e

            # copy back only the finished artefacts: a few large sequential writes, which a
            # network mount handles fine, unlike the thousands of small ones a build makes
            mkdir -p /mounted/target
            for jar in /tmp/build/target/*.jar; do
                [ -f "$jar" ] && cp -f "$jar" /mounted/target/
            done
            for reports in surefire-reports failsafe-reports validation-reports site; do
                if [ -d "/tmp/build/target/$reports" ]; then
                    # Clear the destination'"'"'s CONTENTS rather than removing the directory
                    # itself and recreating it: on a CIFS/SMB bind mount, "rm -rf" on the whole
                    # tree can race a still-settling handle from the read a moment earlier and
                    # fail with "Directory not empty". Emptying in place sidesteps that, and a
                    # report directory is a nice-to-have -- never worth failing the actual test
                    # result over, which was already captured in $result above.
                    mkdir -p "/mounted/target/$reports"
                    find "/mounted/target/$reports" -mindepth 1 -delete 2>/dev/null || true
                    cp -a "/tmp/build/target/$reports/." "/mounted/target/$reports/" 2>/dev/null || true
                fi
            done
            exit $result
        '
}

export WW_MVN_USER_HOME="-Duser.home=/var/maven"
