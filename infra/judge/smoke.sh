#!/usr/bin/env bash
set +x
set -euo pipefail

readonly base_url="${JUDGE0_BASE_URL:-http://127.0.0.1:2358}"
readonly poll_deadline_seconds="${D3_JUDGE_POLL_DEADLINE_SECONDS:-30}"
: "${JUDGE0_AUTH_HEADER:?Set JUDGE0_AUTH_HEADER without printing it.}"
: "${JUDGE0_AUTH_TOKEN:?Set JUDGE0_AUTH_TOKEN without printing it.}"
: "${D3_JUDGE_C_ID:?Set the verified C language ID.}"
: "${D3_JUDGE_CPP_ID:?Set the verified C++ language ID.}"
: "${D3_JUDGE_JAVA_ID:?Set the verified Java language ID.}"
: "${D3_JUDGE_PYTHON3_ID:?Set the verified Python 3 language ID.}"
: "${D3_JUDGE_JAVASCRIPT_ID:?Set the verified JavaScript language ID.}"
: "${D3_JUDGE_TYPESCRIPT_ID:?Set the verified TypeScript language ID.}"

case "$base_url" in
  http://127.0.0.1:2358 | http://localhost:2358) ;;
  *)
    echo "Refusing Judge0 smoke outside the approved loopback endpoints." >&2
    exit 2
    ;;
esac

if [[ ! "$poll_deadline_seconds" =~ ^[1-9][0-9]*$ ]] || (( poll_deadline_seconds > 30 )); then
  echo "D3_JUDGE_POLL_DEADLINE_SECONDS must be an integer from 1 through 30." >&2
  exit 2
fi

tmp_dir="$(mktemp -d)"
trap 'rm -rf -- "$tmp_dir"' EXIT
available_languages=""

auth_curl() {
  curl -q --noproxy '*' --fail --silent --show-error --max-time 15 \
    --header "${JUDGE0_AUTH_HEADER}: ${JUDGE0_AUTH_TOKEN}" \
    "$@"
}

auth_curl_with_timeout() {
  local timeout_seconds="$1"
  shift
  curl -q --noproxy '*' --fail --silent --show-error --max-time "$timeout_seconds" \
    --header "${JUDGE0_AUTH_HEADER}: ${JUDGE0_AUTH_TOKEN}" \
    "$@"
}

wait_for_submission() {
  local token="$1"
  local deadline remaining result status_id

  deadline=$((SECONDS + poll_deadline_seconds))
  while (( SECONDS < deadline )); do
    remaining=$((deadline - SECONDS))
    if ! result="$(auth_curl_with_timeout "$remaining" \
      "${base_url}/submissions/${token}?base64_encoded=false&fields=language_id,status,time,memory" 2>/dev/null)"; then
      sleep 0.25
      continue
    fi
    status_id="$(jq -er '.status.id' <<<"$result")"
    if (( status_id > 2 )); then
      jq -c '{languageId: .language_id, status: .status.description, time, memory}' <<<"$result"
      return 0
    fi
    sleep 0.25
  done

  echo "Submission polling reached the ${poll_deadline_seconds}-second deadline." >&2
  return 1
}

validate_language_mapping() {
  local product_language="$1"
  local language_id="$2"
  local expected_name="$3"
  local actual_name

  actual_name="$(jq -er --argjson id "$language_id" \
    '[.[] | select(.id == $id)] | if length == 1 then .[0].name else error("runtime ID is missing or duplicated") end' \
    <<<"$available_languages")"
  if [[ "$actual_name" != "$expected_name" ]]; then
    echo "${product_language} runtime mismatch: expected '${expected_name}', got '${actual_name}'." >&2
    return 1
  fi
}

assert_request_rejected() {
  local label="$1"
  local field="$2"
  local value="$3"
  local payload response_file http_code

  payload="$(jq -cn \
    --argjson languageId "$D3_JUDGE_PYTHON3_ID" \
    --arg field "$field" \
    --argjson value "$value" \
    '{source_code: "print(1)", language_id: $languageId, enable_network: false} | .[$field] = $value')"
  response_file="$tmp_dir/limit-${label}.json"
  http_code="$(curl -q --noproxy '*' --silent --show-error --max-time 10 \
    --header "${JUDGE0_AUTH_HEADER}: ${JUDGE0_AUTH_TOKEN}" \
    --header 'Content-Type: application/json' \
    --request POST --data "$payload" \
    --output "$response_file" --write-out '%{http_code}' \
    "${base_url}/submissions?base64_encoded=false&wait=false" || true)"
  if [[ "$http_code" != "422" ]]; then
    echo "Expected ${label} request rejection HTTP 422, got ${http_code}." >&2
    return 1
  fi
  jq -cn --arg limit "$label" --arg status "$http_code" \
    '{check: "server-request-boundary", boundary: $limit, result: "PASS", httpStatus: $status}'
}

assert_endpoint_rejected() {
  local label="$1"
  local method="$2"
  local path="$3"
  local payload="$4"
  local expected_status="$5"
  local response_file http_code
  local -a data_args=()

  response_file="$tmp_dir/endpoint-${label}.json"
  if [[ -n "$payload" ]]; then
    data_args=(--header 'Content-Type: application/json' --data "$payload")
  fi
  http_code="$(curl -q --noproxy '*' --silent --show-error --max-time 10 \
    --header "${JUDGE0_AUTH_HEADER}: ${JUDGE0_AUTH_TOKEN}" \
    --request "$method" "${data_args[@]}" \
    --output "$response_file" --write-out '%{http_code}' \
    "${base_url}${path}" || true)"
  if [[ "$http_code" != "$expected_status" ]]; then
    echo "Expected ${label} endpoint rejection HTTP ${expected_status}, got ${http_code}." >&2
    return 1
  fi
  jq -cn --arg boundary "$label" --arg status "$http_code" \
    '{check: "server-request-boundary", boundary: $boundary, result: "PASS", httpStatus: $status}'
}

run_case() {
  local label="$1"
  local language_id="$2"
  local source_code="$3"
  local stdin_text="$4"
  local expected_output="$5"
  local expected_status_pattern="$6"
  local cpu_limit="${7:-2}"
  local wall_limit="${8:-5}"
  local memory_limit="${9:-262144}"
  local expected_memory="${10:-}"
  local expected_time_min="${11:-}"
  local expected_time_max="${12:-}"
  local stack_limit="${13:-65536}"
  local process_limit="${14:-60}"
  local file_limit="${15:-1024}"
  local payload token result status actual_memory

  payload="$(jq -cn \
    --arg sourceCode "$source_code" \
    --arg stdin "$stdin_text" \
    --arg expectedOutput "$expected_output" \
    --argjson languageId "$language_id" \
    --argjson cpuLimit "$cpu_limit" \
    --argjson wallLimit "$wall_limit" \
    --argjson memoryLimit "$memory_limit" \
    --argjson stackLimit "$stack_limit" \
    --argjson processLimit "$process_limit" \
    --argjson fileLimit "$file_limit" \
    '{
      source_code: $sourceCode,
      language_id: $languageId,
      stdin: $stdin,
      expected_output: $expectedOutput,
      cpu_time_limit: $cpuLimit,
      wall_time_limit: $wallLimit,
      memory_limit: $memoryLimit,
      stack_limit: $stackLimit,
      max_processes_and_or_threads: $processLimit,
      max_file_size: $fileLimit,
      enable_network: false
    }')"
  token="$(auth_curl \
    --header 'Content-Type: application/json' \
    --request POST \
    --data "$payload" \
    "${base_url}/submissions?base64_encoded=false&wait=false" | jq -er '.token')"
  result="$(wait_for_submission "$token")"
  status="$(jq -er '.status' <<<"$result")"
  if [[ ! "$status" =~ $expected_status_pattern ]]; then
    jq -cn --arg caseLabel "$label" --arg expected "$expected_status_pattern" --argjson actual "$result" \
      '{case: $caseLabel, result: "FAIL", expectedStatusPattern: $expected, actual: $actual}'
    return 1
  fi
  if [[ -n "$expected_memory" ]]; then
    actual_memory="$(jq -er '.memory' <<<"$result")"
    if [[ "$actual_memory" != "$expected_memory" ]]; then
      jq -cn --arg caseLabel "$label" --argjson expectedMemory "$expected_memory" --argjson actual "$result" \
        '{case: $caseLabel, result: "FAIL", expectedMemoryKiB: $expectedMemory, actual: $actual}'
      return 1
    fi
  fi
  if [[ -n "$expected_time_min" || -n "$expected_time_max" ]]; then
    if [[ -z "$expected_time_min" || -z "$expected_time_max" ]] ||
      ! jq -e --argjson min "$expected_time_min" --argjson max "$expected_time_max" \
        '.time != null and ((.time | tonumber) >= $min) and ((.time | tonumber) <= $max)' \
        >/dev/null <<<"$result"; then
      jq -cn --arg caseLabel "$label" \
        --arg expectedMin "$expected_time_min" --arg expectedMax "$expected_time_max" \
        --argjson actual "$result" \
        '{case: $caseLabel, result: "FAIL", expectedTimeSeconds: {min: $expectedMin, max: $expectedMax}, actual: $actual}'
      return 1
    fi
  fi
  jq -cn --arg caseLabel "$label" --argjson actual "$result" \
    '{case: $caseLabel, result: "PASS", evidence: $actual}'
}

run_default_case() {
  local label="$1"
  local language_id="$2"
  local source_code="$3"
  local stdin_text="$4"
  local expected_output="$5"
  local expected_status_pattern="$6"
  local expected_memory="${7:-}"
  local expected_time_min="${8:-}"
  local expected_time_max="${9:-}"
  local expected_elapsed_min="${10:-}"
  local expected_elapsed_max="${11:-}"
  local payload token result status actual_memory started_ms ended_ms elapsed_seconds

  payload="$(jq -cn \
    --arg sourceCode "$source_code" \
    --arg stdin "$stdin_text" \
    --arg expectedOutput "$expected_output" \
    --argjson languageId "$language_id" \
    '{
      source_code: $sourceCode,
      language_id: $languageId,
      stdin: $stdin,
      expected_output: $expectedOutput,
      enable_network: false
    }')"
  token="$(auth_curl \
    --header 'Content-Type: application/json' \
    --request POST \
    --data "$payload" \
    "${base_url}/submissions?base64_encoded=false&wait=false" | jq -er '.token')"
  started_ms="$(date +%s%3N)"
  result="$(wait_for_submission "$token")"
  ended_ms="$(date +%s%3N)"
  elapsed_seconds="$(jq -cn --argjson start "$started_ms" --argjson finish "$ended_ms" '($finish - $start) / 1000')"
  status="$(jq -er '.status' <<<"$result")"
  if [[ ! "$status" =~ $expected_status_pattern ]]; then
    jq -cn --arg caseLabel "$label" --arg expected "$expected_status_pattern" --argjson actual "$result" \
      '{case: $caseLabel, result: "FAIL", expectedStatusPattern: $expected, actual: $actual}'
    return 1
  fi
  if [[ -n "$expected_memory" ]]; then
    actual_memory="$(jq -er '.memory' <<<"$result")"
    if [[ "$actual_memory" != "$expected_memory" ]]; then
      jq -cn --arg caseLabel "$label" --argjson expectedMemory "$expected_memory" --argjson actual "$result" \
        '{case: $caseLabel, result: "FAIL", expectedMemoryKiB: $expectedMemory, actual: $actual}'
      return 1
    fi
  fi
  if [[ -n "$expected_time_min" || -n "$expected_time_max" ]]; then
    if [[ -z "$expected_time_min" || -z "$expected_time_max" ]] ||
      ! jq -e --argjson min "$expected_time_min" --argjson max "$expected_time_max" \
        '.time != null and ((.time | tonumber) >= $min) and ((.time | tonumber) <= $max)' \
        >/dev/null <<<"$result"; then
      jq -cn --arg caseLabel "$label" \
        --arg expectedMin "$expected_time_min" --arg expectedMax "$expected_time_max" \
        --argjson actual "$result" \
        '{case: $caseLabel, result: "FAIL", expectedTimeSeconds: {min: $expectedMin, max: $expectedMax}, actual: $actual}'
      return 1
    fi
  fi
  if [[ -n "$expected_elapsed_min" || -n "$expected_elapsed_max" ]]; then
    if [[ -z "$expected_elapsed_min" || -z "$expected_elapsed_max" ]] ||
      ! jq -en --argjson elapsed "$elapsed_seconds" \
        --argjson min "$expected_elapsed_min" --argjson max "$expected_elapsed_max" \
        '$elapsed >= $min and $elapsed <= $max' >/dev/null; then
      jq -cn --arg caseLabel "$label" \
        --arg expectedMin "$expected_elapsed_min" --arg expectedMax "$expected_elapsed_max" \
        --argjson elapsed "$elapsed_seconds" --argjson actual "$result" \
        '{case: $caseLabel, result: "FAIL", expectedElapsedSeconds: {min: $expectedMin, max: $expectedMax}, elapsedSeconds: $elapsed, actual: $actual}'
      return 1
    fi
  fi
  jq -cn --arg caseLabel "$label" --argjson actual "$result" --argjson elapsed "$elapsed_seconds" \
    '{case: $caseLabel, result: "PASS", evidence: ($actual + {elapsedSeconds: $elapsed})}'
}

unauthenticated_code="$(curl -q --noproxy '*' --silent --show-error --max-time 5 \
  --output "$tmp_dir/unauthenticated.json" --write-out '%{http_code}' \
  "${base_url}/version" || true)"
if [[ "$unauthenticated_code" != "401" ]]; then
  echo "Expected unauthenticated HTTP 401, got ${unauthenticated_code}." >&2
  exit 1
fi
jq -cn --arg status "$unauthenticated_code" '{check: "authentication", result: "PASS", httpStatus: $status}'

version="$(auth_curl "${base_url}/version")"
workers="$(auth_curl "${base_url}/workers")"
jq -cn --arg version "$version" --argjson workers "$workers" \
  '{check: "runtime", result: "PASS", version: $version, workers: $workers}'

language_ids="$(jq -cn \
  --argjson c "$D3_JUDGE_C_ID" \
  --argjson cpp "$D3_JUDGE_CPP_ID" \
  --argjson java "$D3_JUDGE_JAVA_ID" \
  --argjson python3 "$D3_JUDGE_PYTHON3_ID" \
  --argjson javascript "$D3_JUDGE_JAVASCRIPT_ID" \
  --argjson typescript "$D3_JUDGE_TYPESCRIPT_ID" \
  '[$c, $cpp, $java, $python3, $javascript, $typescript]')"
if [[ "$(jq 'unique | length' <<<"$language_ids")" != "6" ]]; then
  echo "Each product language must use a unique Judge0 runtime ID." >&2
  exit 1
fi

available_languages="$(auth_curl "${base_url}/languages")"
validate_language_mapping "C" "$D3_JUDGE_C_ID" "C (GCC 9.2.0)"
validate_language_mapping "C++" "$D3_JUDGE_CPP_ID" "C++ (GCC 9.2.0)"
validate_language_mapping "Java" "$D3_JUDGE_JAVA_ID" "Java (OpenJDK 13.0.1)"
validate_language_mapping "Python 3" "$D3_JUDGE_PYTHON3_ID" "Python (3.8.1)"
validate_language_mapping "JavaScript" "$D3_JUDGE_JAVASCRIPT_ID" "JavaScript (Node.js 12.14.0)"
validate_language_mapping "TypeScript" "$D3_JUDGE_TYPESCRIPT_ID" "TypeScript (3.7.4)"
printf '%s' "$available_languages" | jq -c --argjson ids "$language_ids" \
  '[.[] | select(.id as $id | $ids | index($id)) | {id, name}]'

network_payload="$(jq -cn --argjson languageId "$D3_JUDGE_PYTHON3_ID" \
  '{source_code: "print(1)", language_id: $languageId, enable_network: true}')"
network_code="$(curl -q --noproxy '*' --silent --show-error --max-time 10 \
  --header "${JUDGE0_AUTH_HEADER}: ${JUDGE0_AUTH_TOKEN}" \
  --header 'Content-Type: application/json' \
  --request POST --data "$network_payload" \
  --output "$tmp_dir/network.json" --write-out '%{http_code}' \
  "${base_url}/submissions?base64_encoded=false&wait=false" || true)"
if [[ "$network_code" != "422" ]]; then
  echo "Expected network opt-in rejection HTTP 422, got ${network_code}." >&2
  exit 1
fi
jq -cn --arg status "$network_code" '{check: "submission-network-opt-in-denied", result: "PASS", httpStatus: $status}'

assert_request_rejected "cpu-time-ceiling" "cpu_time_limit" 11
assert_request_rejected "wall-time-ceiling" "wall_time_limit" 16
assert_request_rejected "extra-time-ceiling" "cpu_extra_time" 0.6
assert_request_rejected "memory-ceiling" "memory_limit" 262145
assert_request_rejected "stack-ceiling" "stack_limit" 65537
assert_request_rejected "process-or-thread-ceiling" "max_processes_and_or_threads" 61
assert_request_rejected "file-size-ceiling" "max_file_size" 1025
assert_request_rejected "additional-files-disabled" "additional_files" '"AA=="'
assert_request_rejected "repeated-runs-ceiling" "number_of_runs" 4
assert_request_rejected "callback-disabled" "callback_url" '"http://127.0.0.1:9/d3-disabled-callback"'
assert_request_rejected "compiler-options-disabled" "compiler_options" '"-O3"'
assert_request_rejected "command-arguments-disabled" "command_line_arguments" '"--help"'

basic_payload="$(jq -cn --argjson languageId "$D3_JUDGE_PYTHON3_ID" \
  '{source_code: "print(1)", language_id: $languageId, enable_network: false}')"
assert_endpoint_rejected "wait-disabled" "POST" "/submissions?base64_encoded=false&wait=true" "$basic_payload" "400"
batch_payload="$(jq -cn --argjson languageId "$D3_JUDGE_PYTHON3_ID" \
  '{submissions: [{source_code: "print(1)", language_id: $languageId, enable_network: false}]}')"
assert_endpoint_rejected "batch-disabled" "POST" "/submissions/batch?base64_encoded=false" "$batch_payload" "400"
delete_token="$(auth_curl \
  --header 'Content-Type: application/json' --request POST --data "$basic_payload" \
  "${base_url}/submissions?base64_encoded=false&wait=false" | jq -er '.token')"
wait_for_submission "$delete_token" >/dev/null
assert_endpoint_rejected "deletion-disabled" "DELETE" "/submissions/${delete_token}?base64_encoded=false" "" "403"
unset delete_token

if ! timeout 5 bash -c 'exec 3<>/dev/tcp/1.1.1.1/53; exec 3>&-'; then
  echo "Host egress control could not reach 1.1.1.1:53/TCP; sandbox network evidence is inconclusive." >&2
  exit 1
fi
jq -cn '{check: "host-egress-control", result: "PASS", endpoint: "1.1.1.1:53/TCP"}'

run_case "hello-c" "$D3_JUDGE_C_ID" $'#include <stdio.h>\nint main(void) { puts("D3"); return 0; }' "" $'D3\n' '^Accepted$'
run_case "hello-cpp" "$D3_JUDGE_CPP_ID" $'#include <iostream>\nint main() { std::cout << "D3\\n"; }' "" $'D3\n' '^Accepted$'
run_case "hello-java" "$D3_JUDGE_JAVA_ID" $'class Main { public static void main(String[] args) { System.out.println("D3"); } }' "" $'D3\n' '^Accepted$'
run_case "hello-python3" "$D3_JUDGE_PYTHON3_ID" $'print("D3")' "" $'D3\n' '^Accepted$'
run_case "hello-javascript" "$D3_JUDGE_JAVASCRIPT_ID" $'console.log("D3");' "" $'D3\n' '^Accepted$'
run_case "hello-typescript" "$D3_JUDGE_TYPESCRIPT_ID" \
  $'declare function eval(code: string): any;\neval("console").log("D3");' "" $'D3\n' '^Accepted$'

run_case "deterministic-c" "$D3_JUDGE_C_ID" \
  $'#include <stdio.h>\nint main(void) { int a, b; scanf("%d %d", &a, &b); printf("%d\\n", a + b); return 0; }' \
  $'2 3\n' $'5\n' '^Accepted$'
run_case "deterministic-cpp" "$D3_JUDGE_CPP_ID" \
  $'#include <iostream>\nint main() { int a, b; std::cin >> a >> b; std::cout << a + b << "\\n"; }' \
  $'2 3\n' $'5\n' '^Accepted$'
run_case "deterministic-java" "$D3_JUDGE_JAVA_ID" \
  $'import java.util.Scanner;\nclass Main { public static void main(String[] args) { Scanner in = new Scanner(System.in); System.out.println(in.nextInt() + in.nextInt()); } }' \
  $'2 3\n' $'5\n' '^Accepted$'
run_case "deterministic-python3" "$D3_JUDGE_PYTHON3_ID" \
  $'a, b = map(int, input().split())\nprint(a + b)' $'2 3\n' $'5\n' '^Accepted$'
run_case "deterministic-javascript" "$D3_JUDGE_JAVASCRIPT_ID" \
  $'const values = require("fs").readFileSync(0, "utf8").trim().split(" ").map(Number);\nconsole.log(values[0] + values[1]);' \
  $'2 3\n' $'5\n' '^Accepted$'
run_case "deterministic-typescript" "$D3_JUDGE_TYPESCRIPT_ID" \
  $'declare function eval(code: string): any;\nconst input: string = eval("require")("fs").readFileSync(0, "utf8");\nconst values: string[] = input.trim().split(" ");\neval("console").log(parseInt(values[0]) + parseInt(values[1]));' \
  $'2 3\n' $'5\n' '^Accepted$'
run_case "submission-network-denied" "$D3_JUDGE_PYTHON3_ID" \
  $'import socket\ntry:\n    socket.create_connection(("1.1.1.1", 53), timeout=1)\n    print("OPEN")\nexcept OSError:\n    print("BLOCKED")' \
  "" $'BLOCKED\n' '^Accepted$'
run_case "wrong-answer" "$D3_JUDGE_PYTHON3_ID" $'a, b = map(int, input().split())\nprint(a - b)' $'2 3\n' $'5\n' '^Wrong Answer$'
run_case "compilation-error" "$D3_JUDGE_C_ID" 'int main(void) { this is invalid; }' "" "" '^Compilation Error$'
run_case "runtime-error" "$D3_JUDGE_PYTHON3_ID" 'raise RuntimeError("redacted smoke")' "" "" '^Runtime Error'
run_case "time-limit" "$D3_JUDGE_PYTHON3_ID" 'while True: pass' "" "" '^Time Limit Exceeded$' 1 2 262144 "" 0.9 1.5
run_case "wall-time-limit" "$D3_JUDGE_PYTHON3_ID" \
  $'import time\ntime.sleep(3)\nprint("OPEN")' "" "" '^Time Limit Exceeded$' 10 1 262144 "" 0 0.2
run_case "memory-pressure" "$D3_JUDGE_PYTHON3_ID" 'bytearray(300 * 1024 * 1024)' "" "" '^Runtime Error' 2 5 65536 65536
run_case "process-control" "$D3_JUDGE_PYTHON3_ID" \
  $'import os, time\nchildren = []\nblocked = False\ntry:\n    for _ in range(20):\n        pid = os.fork()\n        if pid == 0:\n            time.sleep(0.2)\n            os._exit(0)\n        children.append(pid)\nexcept OSError:\n    blocked = True\nfinally:\n    for pid in children:\n        try:\n            os.waitpid(pid, 0)\n        except ChildProcessError:\n            pass\nprint("BLOCKED" if blocked else "OPEN")' \
  "" $'OPEN\n' '^Accepted$' 2 5 262144 "" "" "" 65536 60 1024
run_case "process-limit" "$D3_JUDGE_PYTHON3_ID" \
  $'import os, time\nchildren = []\nblocked = False\ntry:\n    for _ in range(20):\n        pid = os.fork()\n        if pid == 0:\n            time.sleep(0.2)\n            os._exit(0)\n        children.append(pid)\nexcept OSError:\n    blocked = True\nfinally:\n    for pid in children:\n        try:\n            os.waitpid(pid, 0)\n        except ChildProcessError:\n            pass\nprint("BLOCKED" if blocked else "OPEN")' \
  "" $'BLOCKED\n' '^Accepted$' 2 5 262144 "" "" "" 65536 8 1024
run_case "stack-control" "$D3_JUDGE_C_ID" \
  $'#include <stdio.h>\n__attribute__((noinline)) void dive(int n) { volatile char pad[1024 * 1024]; pad[0] = (char)n; if (n > 0) dive(n - 1); if (pad[0] == 127) puts("never"); }\nint main(void) { dive(16); puts("OPEN"); return 0; }' \
  "" $'OPEN\n' '^Accepted$' 2 5 262144 "" "" "" 65536 60 1024
run_case "stack-limit" "$D3_JUDGE_C_ID" \
  $'#include <stdio.h>\n__attribute__((noinline)) void dive(int n) { volatile char pad[1024 * 1024]; pad[0] = (char)n; if (n > 0) dive(n - 1); if (pad[0] == 127) puts("never"); }\nint main(void) { dive(16); puts("OPEN"); return 0; }' \
  "" "" '^Runtime Error' 2 5 262144 "" "" "" 8192 60 1024
run_case "file-size-control" "$D3_JUDGE_PYTHON3_ID" \
  $'with open("large.bin", "wb") as output:\n    output.write(b"x" * (256 * 1024))\nprint("OPEN")' \
  "" $'OPEN\n' '^Accepted$' 2 5 262144 "" "" "" 65536 60 1024
run_case "file-size-limit" "$D3_JUDGE_PYTHON3_ID" \
  $'with open("large.bin", "wb") as output:\n    output.write(b"x" * (256 * 1024))\nprint("OPEN")' \
  "" "" '^Runtime Error' 2 5 262144 "" "" "" 65536 60 64

run_default_case "default-cpu-time" "$D3_JUDGE_PYTHON3_ID" 'while True: pass' \
  "" "" '^Time Limit Exceeded$' "" 1.8 2.6
run_default_case "default-wall-time" "$D3_JUDGE_PYTHON3_ID" \
  $'import time\ntime.sleep(7)\nprint("OPEN")' "" "" '^Time Limit Exceeded$' "" 0 0.2 4.5 6.5
run_default_case "default-memory" "$D3_JUDGE_PYTHON3_ID" \
  'bytearray(300 * 1024 * 1024)' "" "" '^Runtime Error' 262144
run_default_case "default-process-limit" "$D3_JUDGE_PYTHON3_ID" \
  $'import os, time\nchildren = []\nblocked = False\ntry:\n    for _ in range(70):\n        pid = os.fork()\n        if pid == 0:\n            time.sleep(0.2)\n            os._exit(0)\n        children.append(pid)\nexcept OSError:\n    blocked = True\nfinally:\n    for pid in children:\n        try:\n            os.waitpid(pid, 0)\n        except ChildProcessError:\n            pass\nprint("BLOCKED" if blocked else "OPEN")' \
  "" $'BLOCKED\n' '^Accepted$'
run_default_case "default-stack-limit" "$D3_JUDGE_C_ID" \
  $'#include <stdio.h>\n__attribute__((noinline)) void dive(int n) { volatile char pad[1024 * 1024]; pad[0] = (char)n; if (n > 0) dive(n - 1); if (pad[0] == 127) puts("never"); }\nint main(void) { dive(80); puts("OPEN"); return 0; }' \
  "" "" '^Runtime Error'
run_default_case "default-file-size" "$D3_JUDGE_PYTHON3_ID" \
  $'with open("large.bin", "wb") as output:\n    output.write(b"x" * (2 * 1024 * 1024))\nprint("OPEN")' \
  "" "" '^Runtime Error'
