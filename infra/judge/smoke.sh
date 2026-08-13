#!/usr/bin/env bash
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
  local payload token result status actual_memory

  payload="$(jq -cn \
    --arg sourceCode "$source_code" \
    --arg stdin "$stdin_text" \
    --arg expectedOutput "$expected_output" \
    --argjson languageId "$language_id" \
    --argjson cpuLimit "$cpu_limit" \
    --argjson wallLimit "$wall_limit" \
    --argjson memoryLimit "$memory_limit" \
    '{
      source_code: $sourceCode,
      language_id: $languageId,
      stdin: $stdin,
      expected_output: $expectedOutput,
      cpu_time_limit: $cpuLimit,
      wall_time_limit: $wallLimit,
      memory_limit: $memoryLimit,
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
  jq -cn --arg caseLabel "$label" --argjson actual "$result" \
    '{case: $caseLabel, result: "PASS", evidence: $actual}'
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
run_case "time-limit" "$D3_JUDGE_PYTHON3_ID" 'while True: pass' "" "" '^Time Limit Exceeded$' 1 2 262144
run_case "memory-pressure" "$D3_JUDGE_PYTHON3_ID" 'bytearray(300 * 1024 * 1024)' "" "" '^Runtime Error' 2 5 65536 65536
