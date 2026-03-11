#!/usr/bin/env bash
set -euo pipefail

run_app=false
if [[ "${1:-}" == "--run" ]]; then
  run_app=true
fi

get_java_tool() {
  local name="$1"
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/$name" ]]; then
    echo "$JAVA_HOME/bin/$name"
    return
  fi

  if command -v "$name" >/dev/null 2>&1; then
    command -v "$name"
    return
  fi

  echo "Nástroj '$name' nebyl nalezen ani v PATH, ani v JAVA_HOME. Nainstalujte JDK 17+ a nastavte PATH nebo JAVA_HOME." >&2
  exit 1
}

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
build_root="$repo_root/build"
main_out="$build_root/classes"

javac_bin="$(get_java_tool javac)"
java_bin="$(get_java_tool java)"

rm -rf "$main_out"
mkdir -p "$main_out"

mapfile -t src_files < <(find "$repo_root/src" -type f -name '*.java' | sort)
if [[ "${#src_files[@]}" -eq 0 ]]; then
  echo "Ve složce src nebyly nalezeny žádné Java zdrojáky." >&2
  exit 1
fi

echo "Kompiluji hlavní zdrojáky..."
"$javac_bin" -encoding UTF-8 -d "$main_out" "${src_files[@]}"

echo "Build dokončen do $main_out"
if [[ "$run_app" == true ]]; then
  "$java_bin" -cp "$main_out" Main
fi
