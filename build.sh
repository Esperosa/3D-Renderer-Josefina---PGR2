#!/usr/bin/env bash
set -euo pipefail

run_app=false
if [[ "${1:-}" == "--run" ]]; then
  run_app=true
fi

profile="safe"
for arg in "$@"; do
  case "$arg" in
    --profile=safe|--profile=balanced|--profile=fast)
      profile="${arg#--profile=}"
      ;;
  esac
done

java_major_from_home() {
  local home="$1"
  if [[ ! -x "$home/bin/java" ]]; then
    echo "0"
    return
  fi
  local first
  first="$($home/bin/java -version 2>&1 | head -n1)"
  if [[ "$first" =~ \"([0-9]+)\. ]]; then
    echo "${BASH_REMATCH[1]}"
    return
  fi
  if [[ "$first" =~ \"([0-9]+)\" ]]; then
    echo "${BASH_REMATCH[1]}"
    return
  fi
  echo "0"
}

pick_best_java_home() {
  local candidates=()
  local roots=(
    "$JAVA_HOME"
    "/usr/lib/jvm"
    "/Library/Java/JavaVirtualMachines"
    "/c/Program Files/Eclipse Adoptium"
    "/c/Program Files/Java"
    "/mnt/c/Program Files/Eclipse Adoptium"
    "/mnt/c/Program Files/Java"
  )

  for root in "${roots[@]}"; do
    [[ -n "$root" && -d "$root" ]] || continue
    while IFS= read -r dir; do
      candidates+=("$dir")
    done < <(find "$root" -maxdepth 1 -mindepth 1 -type d 2>/dev/null)
  done

  if command -v java >/dev/null 2>&1; then
    local java_bin
    java_bin="$(command -v java)"
    local path_home
    path_home="$(cd "$(dirname "$java_bin")/.." && pwd)"
    candidates+=("$path_home")
  fi

  local best_home=""
  local best_major=0
  local home
  for home in "${candidates[@]}"; do
    [[ -x "$home/bin/java" && -x "$home/bin/javac" ]] || continue
    local major
    major="$(java_major_from_home "$home")"
    if [[ "$major" -ge 17 && "$major" -gt "$best_major" ]]; then
      best_major="$major"
      best_home="$home"
    fi
  done

  if [[ -z "$best_home" ]]; then
    echo ""
    return
  fi

  echo "$best_home"
}

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
build_root="$repo_root/build"
main_out="$build_root/classes"

selected_home="$(pick_best_java_home)"
if [[ -z "$selected_home" ]]; then
  echo "Nebyl nalezen JDK 17+ runtime. Nainstalujte JDK 21 (doporučeno) nebo novější JDK 17+." >&2
  exit 1
fi

export JAVA_HOME="$selected_home"
export PATH="$selected_home/bin:$PATH"

javac_bin="$selected_home/bin/javac"
java_bin="$selected_home/bin/java"

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
echo "Používám JDK: $selected_home (major $(java_major_from_home "$selected_home"))"
if [[ "$run_app" == true ]]; then
  run_args=("-XX:+ShowCodeDetailsInExceptionMessages")
  case "$profile" in
    safe)
      run_args+=("-Xint")
      ;;
    balanced)
      run_args+=("-XX:TieredStopAtLevel=1")
      run_args+=("-XX:CompileCommand=exclude,engine/render/ray/core/PathTracerRenderer,tracePreviewPathInternal")
      run_args+=("-XX:CompileCommand=exclude,engine/render/ray/core/PathTracerRenderer,sampleEnvironmentBackground")
      run_args+=("-XX:CompileCommand=exclude,engine/material/MaterialGraphValueEvaluator,evaluateValueOutput")
      ;;
    fast)
      ;;
  esac
  run_args+=("-cp" "$main_out" "Main")
  echo "Run profil: $profile"
  "$java_bin" "${run_args[@]}"
fi
