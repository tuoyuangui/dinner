#!/usr/bin/env bash
# 一键产出 debug 与 release 两个 APK，统一放到 apk/。
#
# 用法：
#   scripts/package.sh              # debug + release
#   scripts/package.sh debug        # 只出 debug
#   scripts/package.sh release      # 只出 release
#   scripts/package.sh all --clean  # 先 clean 再全量构建
#
# 产物：
#   apk/dinner-<versionName>-debug.apk      applicationId 带 .debug 后缀，可与正式版共存
#   apk/dinner-<versionName>-release.apk    正式签名，用于日常使用或分发
#
# 签名：
#   debug 用仓库内 .android-keystore/debug.keystore。
#   release 用 .android-keystore/release.keystore + keystore.properties（均不入库）。
#   首次执行且缺少 release 签名材料时，会自动调用 scripts/create-release-keystore.sh 生成。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-/usr}"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$ROOT/.toolchain/sdk}}"

usage() {
  sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

VARIANTS=()
DO_CLEAN=0
for arg in "$@"; do
  case "$arg" in
    debug|release) VARIANTS+=("$arg") ;;
    all) VARIANTS+=(debug release) ;;
    --clean) DO_CLEAN=1 ;;
    -h|--help) usage; exit 0 ;;
    *)
      echo "错误：未知参数 $arg" >&2
      echo "用法：scripts/package.sh [debug|release|all] [--clean]" >&2
      exit 1
      ;;
  esac
done

if [[ ${#VARIANTS[@]} -eq 0 ]]; then
  VARIANTS=(debug release)
fi

# 去重，保持 debug 在前的顺序。
DEDUPED=()
for v in "${VARIANTS[@]}"; do
  duplicate=0
  for existing in "${DEDUPED[@]:-}"; do
    if [[ "$existing" == "$v" ]]; then duplicate=1; fi
  done
  if [[ "$duplicate" -eq 0 ]]; then DEDUPED+=("$v"); fi
done
VARIANTS=("${DEDUPED[@]}")

VERSION_NAME="$(sed -n 's/^[[:space:]]*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts | head -1)"
VERSION_CODE="$(sed -n 's/^[[:space:]]*versionCode = \([0-9]\+\).*/\1/p' app/build.gradle.kts | head -1)"
VERSION_NAME="${VERSION_NAME:-0.0.0}"
VERSION_CODE="${VERSION_CODE:-0}"

want_debug=0
want_release=0
for v in "${VARIANTS[@]}"; do
  [[ "$v" == "debug" ]] && want_debug=1
  [[ "$v" == "release" ]] && want_release=1
done

# release 必须有签名材料，否则产物是 unsigned 包，装不上。
if [[ "$want_release" -eq 1 && ! -f "$ROOT/keystore.properties" ]]; then
  echo "未找到 release 签名材料，正在生成（一次性）..."
  "$ROOT/scripts/create-release-keystore.sh"
  echo
fi

TASKS=()
if [[ "$DO_CLEAN" -eq 1 ]]; then TASKS+=(clean); fi
if [[ "$want_debug" -eq 1 ]]; then TASKS+=(assembleDebug); fi
if [[ "$want_release" -eq 1 ]]; then TASKS+=(assembleRelease); fi

echo "版本   : $VERSION_NAME ($VERSION_CODE)"
echo "变体   : ${VARIANTS[*]}"
echo "任务   : ${TASKS[*]}"
echo

"$ROOT/scripts/build.sh" "${TASKS[@]}"

mkdir -p "$ROOT/apk"

apk_size() {
  local path="$1"
  local bytes
  bytes="$(stat -c %s "$path")"
  awk -v b="$bytes" 'BEGIN { printf "%.1f MB", b / 1048576 }'
}

APKSIGNER=""
if [[ -d "$SDK/build-tools" ]]; then
  APKSIGNER="$(find "$SDK/build-tools" -maxdepth 2 -type f -name apksigner 2>/dev/null | sort -V | tail -1)"
fi

verify_signed() {
  local path="$1"
  if [[ -z "$APKSIGNER" || ! -x "$APKSIGNER" ]]; then
    echo "未找到 apksigner，跳过签名校验"
    return 0
  fi
  if "$APKSIGNER" verify --print-certs "$path" >/dev/null 2>&1; then
    echo "签名校验通过"
  else
    echo "签名校验失败：$path" >&2
    return 1
  fi
}

echo
echo "================ 产物 ================"

RESULT=0

if [[ "$want_debug" -eq 1 ]]; then
  SRC="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
  if [[ ! -f "$SRC" ]]; then
    echo "错误：找不到 debug 产物 $SRC" >&2
    RESULT=1
  else
    DST="$ROOT/apk/dinner-${VERSION_NAME}-debug.apk"
    cp -f "$SRC" "$DST"
    echo "debug   : $DST  ($(apk_size "$DST"))"
    verify_signed "$DST" || RESULT=1
  fi
fi

if [[ "$want_release" -eq 1 ]]; then
  REL_DIR="$ROOT/app/build/outputs/apk/release"
  SRC="$REL_DIR/app-release.apk"
  if [[ ! -f "$SRC" ]]; then
    if [[ -f "$REL_DIR/app-release-unsigned.apk" ]]; then
      echo "错误：release 产物未签名（只有 app-release-unsigned.apk）。" >&2
      echo "      检查 keystore.properties 是否完整，或重建：scripts/create-release-keystore.sh --force" >&2
    else
      echo "错误：找不到 release 产物（$REL_DIR）。" >&2
    fi
    RESULT=1
  else
    DST="$ROOT/apk/dinner-${VERSION_NAME}-release.apk"
    cp -f "$SRC" "$DST"
    echo "release : $DST  ($(apk_size "$DST"))"
    verify_signed "$DST" || RESULT=1
  fi
fi

echo "======================================"
if [[ "$RESULT" -ne 0 ]]; then
  echo "有产物未生成或校验失败，见上方错误。" >&2
  exit 1
fi

echo
echo "安装：scripts/install.sh            # debug"
echo "      scripts/install.sh --release  # release"
