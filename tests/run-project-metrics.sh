#!/usr/bin/env bash
set -euo pipefail

benchmark_mode="${1:-standard}"
case "$benchmark_mode" in
  quick|standard|full) ;;
  *)
    echo "Neplatny benchmark mode '$benchmark_mode'. Pouzij quick, standard nebo full." >&2
    exit 1
    ;;
esac

get_java_tool() {
  local name="$1"
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/$name" ]]; then
    printf '%s\n' "$JAVA_HOME/bin/$name"
    return 0
  fi
  if command -v "$name" >/dev/null 2>&1; then
    command -v "$name"
    return 0
  fi
  echo "Nástroj '$name' nebyl nalezen ani v PATH, ani v JAVA_HOME. Nainstalujte JDK 17+ a nastavte PATH nebo JAVA_HOME." >&2
  exit 1
}

script_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_root/.." && pwd)"
build_root="$repo_root/build"
report_root="$build_root/metrics-$(date +%s)-$$"
main_out="$report_root/classes"
test_out="$report_root/test-classes"

javac_bin="$(get_java_tool javac)"
java_bin="$(get_java_tool java)"

mkdir -p "$main_out" "$test_out"

mapfile -t src_files < <(find "$repo_root/src" -name '*.java' -print)
mapfile -t test_files < <(find "$repo_root/tests" -name '*.java' -print)

echo "Kompiluji hlavní zdrojáky..."
"$javac_bin" -encoding UTF-8 -d "$main_out" "${src_files[@]}"

echo "Kompiluji testy a report..."
"$javac_bin" -encoding UTF-8 -cp "$main_out" -d "$test_out" "${test_files[@]}"

classpath="$main_out:$test_out"
echo "Generuji report projektu..."
"$java_bin" -Dmetrics.benchmark.mode="$benchmark_mode" -cp "$classpath" ProjectMetricsReport
