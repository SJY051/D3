#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
tmp_dir="$(mktemp -d)"
trap 'rm -rf -- "$tmp_dir"' EXIT

mkdir -p "$tmp_dir/bin" "$tmp_dir/work/scripts"
cat >"$tmp_dir/work/scripts/load-config" <<'EOF'
RESTART_MAX_TRIES=1
EOF

cat >"$tmp_dir/bin/sudo" <<'EOF'
#!/usr/bin/env bash
if [[ "$1" == "$FAIL_STEP" ]]; then
  [[ "$1" == "tee" ]] && cat >/dev/null
  exit 42
fi
[[ "$1" == "tee" ]] && cat >/dev/null
EOF

cat >"$tmp_dir/bin/rails" <<'EOF'
#!/usr/bin/env bash
touch "$START_MARKER"
exit 0
EOF

cat >"$tmp_dir/bin/rake" <<'EOF'
#!/usr/bin/env bash
touch "$START_MARKER"
exit 0
EOF

chmod +x "$tmp_dir/bin/sudo" "$tmp_dir/bin/rails" "$tmp_dir/bin/rake"

for script in server workers; do
  for fail_step in touch chown chmod tee; do
    marker="$tmp_dir/${script}-${fail_step}.started"
    if (
      cd "$tmp_dir/work"
      PATH="$tmp_dir/bin:$PATH" \
        FAIL_STEP="$fail_step" \
        START_MARKER="$marker" \
        bash "$repo_root/infra/judge/startup/$script"
    ); then
      echo "$script continued after $fail_step failed" >&2
      exit 1
    fi
    if [[ -e "$marker" ]]; then
      echo "$script launched a runtime process after $fail_step failed" >&2
      exit 1
    fi
  done
done

echo "judge-startup-fail-fast: PASS"
