#!/bin/bash

# The code checks are real JUnit tests, not grep -- see src/validation-test/java. They assert
# things about *this repository* (naming conventions, that every install step declares a
# verification and a remedy, that pure logic stays free of I/O imports), as @TestFactory dynamic
# tests so a violation reports individually and names the offending file.
#
# This wrapper exists so the three WatchWolf verbs are the same everywhere:
#   ./ci/build.sh   ./ci/tests.sh   ./ci/validator.sh

script_path=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
exec bash "$script_path/tests.sh" --validation "$@"
