#!/bin/bash

# default variables
unit=0
integration=0
validation=0
test_match=""
skip_preflight=0

# parse params
while [[ "$#" -gt 0 ]]; do
    case $1 in
        --unit) unit=1 ;;
        --integration) integration=1 ;;
        --validation) validation=1 ;;
        --tests) test_match="$2" ; shift ;;
        --skip-preflight) skip_preflight=1 ;;

        *) echo "[e] Unknown parameter passed: $1" >&2 ; exit 1 ;;
    esac
    shift
done

if [ $integration -eq 0 ] && [ $unit -eq 0 ] && [ $validation -eq 0 ]; then
    echo "[e] You must specify at least one type of test to run!" >&2
    exit 1
fi

script_path=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
source "$script_path/common.sh"

# The system tests drive a real Docker daemon (they start throwaway containers). With no daemon
# reachable every IT* suite dies identically with "Cannot connect to the Docker daemon" after
# minutes of Maven, and the report never mentions the cause.
#
# So check first -- from inside the same image and the same mounts the tests will use, so that
# what we probe is exactly what they will get. This mirrors the port preflight in
# WatchWolf-Tester/ci/tests.sh.
preflight_docker_daemon() {
    local probe
    echo "[v] Checking the Docker daemon is reachable from inside the test container..."
    local sock="${DOCKER_HOST#unix://}"
    [ -n "$sock" ] || sock=/var/run/docker.sock
    local docker_gid
    docker_gid=$(stat -c '%g' "$sock" 2>/dev/null || echo 0)

    # Probe from inside the same image, socket and network mode the tests will use, so what we
    # check is exactly what they will get. The maven image has no `docker` CLI -- docker-java
    # talks to the daemon directly over HTTP through the Unix socket, so that is what this checks
    # too, with curl (present in the image) rather than a client binary that was never going to
    # be there.
    probe=$(docker run --rm --network host --user "$(id -u):$(id -g)" --group-add "$docker_gid" \
                -v "$sock":/var/run/docker.sock --entrypoint sh "$WW_MAVEN_IMAGE"               \
                -c 'curl -s --unix-socket /var/run/docker.sock http://localhost/version 2>/dev/null' \
            2>/dev/null)
    # pull "Version":"29.4.3" out of the JSON without needing jq in this image
    probe=$(printf '%s' "$probe" | grep -o '"Version":"[^"]*"' | head -1 | cut -d'"' -f4)

    if [ -z "$probe" ] || [ "$probe" = "FAIL" ]; then
        echo "" >&2
        echo "[e] The Docker daemon is not reachable from inside the test container, so every" >&2
        echo "    system test would fail with 'Cannot connect to the Docker daemon'." >&2
        echo "    Not running them." >&2
        echo "" >&2
        echo "      socket: ${DOCKER_HOST:-unix:///var/run/docker.sock}" >&2
        echo "      user:   $(id -un) (uid $(id -u), groups: $(id -Gn))" >&2
        echo "" >&2
        echo "    Is the daemon running, and is $(id -un) in the 'docker' group?" >&2
        echo "      sudo usermod -aG docker $(id -un)   # then log out and back in" >&2
        echo "" >&2
        echo "    To run anyway, pass --skip-preflight." >&2
        echo "" >&2
        return 1
    fi

    echo "[v] Docker daemon reachable (server $probe)"
    return 0
}

if [ $integration -eq 1 ] && [ $skip_preflight -eq 0 ]; then
    preflight_docker_daemon || exit 1
fi

# clear
ww_mvn clean

overall=0

if [ $unit -eq 1 ]; then
    unit_tests_report_path="$WW_BASE_PATH/target/surefire-reports"
    mkdir -p "$unit_tests_report_path"

    # unit tests are hermetic: no Docker socket, no network, no $HOME/WatchWolf
    if [ ! -z "$test_match" ]; then
        echo "[v] Running filtered unit tests: $test_match"
        ww_mvn test -Dmaven.test.redirectTestOutputToFile=true             \
                        -Dtest="$test_match"        \
                2>&1 | tee "$unit_tests_report_path/docker-log.txt"
        result=${PIPESTATUS[0]}
    else
        ww_mvn test -Dmaven.test.redirectTestOutputToFile=true             \
                                                    \
                2>&1 | tee "$unit_tests_report_path/docker-log.txt"
        result=${PIPESTATUS[0]}
    fi

    ww_mvn surefire-report:report-only
    ww_mvn site -DgenerateReports=false

    if [ $result -ne 0 ]; then
        echo "[e] Unit tests failed" >&2
        overall=$result
    fi
fi

if [ $validation -eq 1 ]; then
    validation_tests_report_path="$WW_BASE_PATH/target/validation-reports"
    mkdir -p "$validation_tests_report_path"

    ww_mvn test -P validation-test                                         \
                    -Dmaven.test.redirectTestOutputToFile=true                          \
                                                    \
            2>&1 | tee "$validation_tests_report_path/docker-log.txt"
    result=${PIPESTATUS[0]}

    if [ $result -ne 0 ]; then
        echo "[e] Code checks failed" >&2
        overall=$result
    fi
fi

if [ $integration -eq 1 ]; then
    # /!\ The system tests start REAL throwaway containers through the mounted Docker socket.
    #     That is what preflight_docker_daemon checks above. See ci/README.md.
    if [ $skip_preflight -eq 1 ]; then
        echo "[w] Preflight skipped; a missing daemon will surface as 'Cannot connect to the Docker daemon'."
    fi

    integration_tests_report_path="$WW_BASE_PATH/target/failsafe-reports"
    mkdir -p "$integration_tests_report_path"

    if [ ! -z "$test_match" ]; then
        echo "[v] Running filtered system tests: $test_match"
        ww_mvn_with_docker test failsafe:integration-test failsafe:verify              \
                        -P integration-test -Dmaven.test.redirectTestOutputToFile=true  \
                        -Dit.test="$test_match"     \
                2>&1 | tee "$integration_tests_report_path/docker-log.txt"
        result=${PIPESTATUS[0]}
    else
        ww_mvn_with_docker test failsafe:integration-test failsafe:verify              \
                        -P integration-test -Dmaven.test.redirectTestOutputToFile=true  \
                                                    \
                2>&1 | tee "$integration_tests_report_path/docker-log.txt"
        result=${PIPESTATUS[0]}
    fi

    ww_mvn surefire-report:failsafe-report-only
    ww_mvn site -DgenerateReports=false

    if [ $result -ne 0 ]; then
        echo "[e] System tests failed" >&2
        overall=$result
    fi
fi

if [ $overall -ne 0 ]; then
    exit $overall
fi
echo "[i] Done"
