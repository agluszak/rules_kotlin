#!/usr/bin/env bash

# --- begin runfiles.bash initialization v2 ---
# Copy-pasted from the Bazel Bash runfiles library v2.
set -o nounset -o pipefail; f=bazel_tools/tools/bash/runfiles/runfiles.bash
# shellcheck disable=SC1090
source "${RUNFILES_DIR:-/dev/null}/$f" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "${RUNFILES_MANIFEST_FILE:-/dev/null}" | cut -f2- -d' ')" 2>/dev/null || \
  source "$0.runfiles/$f" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "$0.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "$0.exe.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
  { echo>&2 "ERROR: cannot find $f"; exit 1; }; f=; set -o errexit
# --- end runfiles.bash initialization v2 ---

# MARK - Locate Deps

create_scratch_dir_sh_location=rules_bazel_integration_test/tools/create_scratch_dir.sh
create_scratch_dir_sh="$(rlocation "${create_scratch_dir_sh_location}")" || \
  (echo >&2 "Failed to locate ${create_scratch_dir_sh_location}" && exit 1)

# MARK - Process Arguments

bazel="${BIT_BAZEL_BINARY:-}"
workspace_dir="${BIT_WORKSPACE_DIR:-}"

[[ -n "${bazel:-}" ]] || { echo >&2 "Must specify the location of the Bazel binary."; exit 1; }
[[ -n "${workspace_dir:-}" ]] || { echo >&2 "Must specify the location of the workspace directory."; exit 1; }

# MARK - Create Scratch Directory

scratch_dir="$("${create_scratch_dir_sh}" --workspace "${workspace_dir}")"
cd "${scratch_dir}"

# Dump Bazel info
echo "=== Output Bazel info ==="
# Share repository cache across tests to reduce disk space usage
# See https://github.com/bazel-contrib/rules_bazel_integration_test/issues/527
"${bazel}" info \
  --repository_cache="${HOME}/.cache/bazel/repository_cache" \
  --repo_contents_cache="${HOME}/.cache/bazel/repo_contents_cache"

# MARK - Test

echo "=== Running do_test ==="
if [[ -f do_test ]]; then
  ./do_test
else
  echo >&2 "No do_test script found in ${workspace_dir}"
  exit 1
fi
