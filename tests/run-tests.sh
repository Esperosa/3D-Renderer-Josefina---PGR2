#!/usr/bin/env bash
set -euo pipefail

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

script_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_root/.." && pwd)"
build_root="$repo_root/build"
test_build_root="$build_root/tests"
main_out="$test_build_root/classes"
test_out="$test_build_root/test-classes"
test_class_list="$script_root/test-class-list.txt"

javac_bin="$(get_java_tool javac)"
java_bin="$(get_java_tool java)"

rm -rf "$test_build_root"
mkdir -p "$main_out" "$test_out"

mapfile -t src_files < <(find "$repo_root/src" -type f -name '*.java' | sort)
mapfile -t test_files < <(find "$repo_root/tests" -type f -name '*.java' | sort)

if [[ "${#src_files[@]}" -eq 0 ]]; then
  echo "Ve složce src nebyly nalezeny žádné Java zdrojáky." >&2
  exit 1
fi
if [[ "${#test_files[@]}" -eq 0 ]]; then
  echo "Ve složce tests nebyly nalezeny žádné Java testy." >&2
  exit 1
fi

echo "Kompiluji hlavní zdrojáky..."
"$javac_bin" -encoding UTF-8 -d "$main_out" "${src_files[@]}"

echo "Kompiluji testy..."
"$javac_bin" -encoding UTF-8 -cp "$main_out" -d "$test_out" "${test_files[@]}"

classpath="$main_out:$test_out"
while IFS= read -r test_class; do
  if [[ -z "${test_class// }" ]] || [[ "$test_class" =~ ^[[:space:]]*# ]]; then
    continue
  fi
  echo "Spouštím $test_class ..."
  "$java_bin" -cp "$classpath" "$test_class"
done < "$test_class_list"

echo "Všechny testy prošly."
