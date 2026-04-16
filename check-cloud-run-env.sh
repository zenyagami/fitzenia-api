#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'EOF'
Usage: ./check-cloud-run-env.sh [all|dev|prod] [env-file]

Checks that env keys from the local env file are declared in the Cloud Run YAML,
and also flags YAML env keys that are missing from the env file.

Defaults:
  target   -> all
  env-file -> .env, then .env.example

Environment overrides:
  IGNORE_KEYS=PORT,ANOTHER_LOCAL_ONLY_KEY

Examples:
  ./check-cloud-run-env.sh
  ./check-cloud-run-env.sh dev .env.dev
  ./check-cloud-run-env.sh prod .env.prod
EOF
}

resolve_env_file() {
  local candidate
  for candidate in .env .env.example; do
    if [[ -f "$candidate" ]]; then
      printf '%s' "$candidate"
      return 0
    fi
  done
  printf '%s' ".env"
}

discover_env_keys() {
  awk '
    /^[[:space:]]*#/ { next }
    /^[[:space:]]*$/ { next }
    /^[[:space:]]*(export[[:space:]]+)?[A-Za-z_][A-Za-z0-9_]*=/ {
      line = $0
      sub(/^[[:space:]]*/, "", line)
      sub(/^export[[:space:]]+/, "", line)
      sub(/=.*/, "", line)
      print line
    }
  ' "$ENV_FILE" | sort -u
}

discover_yaml_env_keys() {
  local yaml_file="$1"

  awk '
    function indent_of(line) {
      match(line, /^[ ]*/)
      return RLENGTH
    }

    /^[[:space:]]*env:[[:space:]]*$/ {
      in_env = 1
      env_indent = indent_of($0)
      next
    }

    in_env {
      if ($0 ~ /^[[:space:]]*$/ || $0 ~ /^[[:space:]]*#/) {
        next
      }

      if ($0 ~ /^[[:space:]]*-[[:space:]]*name:[[:space:]]*/) {
        line = $0
        sub(/^[[:space:]]*-[[:space:]]*name:[[:space:]]*/, "", line)
        gsub(/["'\''[:space:]]/, "", line)
        print line
        next
      }

      current_indent = indent_of($0)
      if (current_indent <= env_indent && $0 !~ /^[[:space:]]*-/) {
        in_env = 0
        next
      }
    }
  ' "$yaml_file" | sort -u
}

print_list() {
  local title="$1"
  local file="$2"
  if [[ -s "$file" ]]; then
    echo "$title"
    sed 's/^/  - /' "$file"
  fi
}

TARGET="${1:-all}"
if [[ "$TARGET" == "-h" || "$TARGET" == "--help" ]]; then
  usage
  exit 0
fi

case "$TARGET" in
  all)
    YAML_FILES=("cloud-run-config.dev.yaml" "cloud-run-config.yaml")
    ;;
  dev)
    YAML_FILES=("cloud-run-config.dev.yaml")
    ;;
  prod)
    YAML_FILES=("cloud-run-config.yaml")
    ;;
  *)
    usage
    exit 1
    ;;
esac

ENV_FILE="${2:-$(resolve_env_file)}"
if [[ ! -f "$ENV_FILE" ]]; then
  echo "[ERROR] Missing env file: $ENV_FILE" >&2
  exit 1
fi

for yaml_file in "${YAML_FILES[@]}"; do
  if [[ ! -f "$yaml_file" ]]; then
    echo "[ERROR] Missing Cloud Run config: $yaml_file" >&2
    exit 1
  fi
done

tmp_dir="$(mktemp -d)"
cleanup() {
  rm -rf "$tmp_dir"
}
trap cleanup EXIT

ignore_file="$tmp_dir/ignore_keys.txt"
printf '%s\n' PORT > "$ignore_file"
if [[ -n "${IGNORE_KEYS:-}" ]]; then
  printf '%s\n' "$IGNORE_KEYS" | tr ', ' '\n\n' | sed '/^$/d' >> "$ignore_file"
fi
sort -u "$ignore_file" -o "$ignore_file"

env_keys_raw="$tmp_dir/env_keys_raw.txt"
env_keys="$tmp_dir/env_keys.txt"
discover_env_keys > "$env_keys_raw"
grep -vxF -f "$ignore_file" "$env_keys_raw" > "$env_keys" || true

status=0
echo "[INFO] Env file: $ENV_FILE"
echo "[INFO] Ignored keys: $(tr '\n' ',' < "$ignore_file" | sed 's/,$//')"

for yaml_file in "${YAML_FILES[@]}"; do
  yaml_keys_raw="$tmp_dir/$(basename "$yaml_file").raw.txt"
  yaml_keys="$tmp_dir/$(basename "$yaml_file").txt"
  missing_in_yaml="$tmp_dir/$(basename "$yaml_file").missing_in_yaml.txt"
  missing_in_env="$tmp_dir/$(basename "$yaml_file").missing_in_env.txt"

  discover_yaml_env_keys "$yaml_file" > "$yaml_keys_raw"
  grep -vxF -f "$ignore_file" "$yaml_keys_raw" > "$yaml_keys" || true

  comm -23 "$env_keys" "$yaml_keys" > "$missing_in_yaml" || true
  comm -13 "$env_keys" "$yaml_keys" > "$missing_in_env" || true

  echo
  echo "[INFO] Checking $yaml_file"
  if [[ ! -s "$missing_in_yaml" && ! -s "$missing_in_env" ]]; then
    echo "[OK] $yaml_file matches $ENV_FILE"
    continue
  fi

  status=1
  print_list "[ERROR] Present in $ENV_FILE but missing from $yaml_file:" "$missing_in_yaml"
  print_list "[ERROR] Present in $yaml_file but missing from $ENV_FILE:" "$missing_in_env"
done

if [[ "$status" -ne 0 ]]; then
  exit "$status"
fi

echo
echo "[OK] Cloud Run env declarations are in sync with $ENV_FILE"
