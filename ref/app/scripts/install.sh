#!/usr/bin/env bash
# 构建并把 APK 安装到已连接的 Android 设备上。
#
# 用法：
#   scripts/install.sh              # 构建并安装 debug 包
#   scripts/install.sh --release    # 构建并安装 release 包
#   scripts/install.sh --no-build   # 只安装已有 APK，不重新构建
#   scripts/install.sh --release --no-build
#
# debug 与 release 的 applicationId 不同（.debug 后缀），可以同时装在设备上。
#
# 设备准备：
#   - USB：手机开启「开发者选项 -> USB 调试」，插线后 adb devices 能看到设备。
#   - 无线：同一局域网下先 `adb pair <ip>:<pair-port>`，再 `adb connect <ip>:<port>`。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$ROOT/.toolchain/sdk}}"

VARIANT="debug"
DO_BUILD=1
for arg in "$@"; do
  case "$arg" in
    --release) VARIANT="release" ;;
    --debug) VARIANT="debug" ;;
    --no-build) DO_BUILD=0 ;;
    -h|--help)
      sed -n '2,16p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "错误：未知参数 $arg" >&2
      echo "用法：scripts/install.sh [--release] [--no-build]" >&2
      exit 1
      ;;
  esac
done

ADB="${ADB:-}"
if [[ -z "$ADB" ]]; then
  if command -v adb >/dev/null 2>&1; then
    ADB="$(command -v adb)"
  elif [[ -x "$SDK/platform-tools/adb" ]]; then
    ADB="$SDK/platform-tools/adb"
  else
    echo "错误：找不到 adb。安装 platform-tools 或把 adb 加进 PATH。" >&2
    exit 1
  fi
fi

if [[ "$DO_BUILD" -eq 1 ]]; then
  if [[ "$VARIANT" == "release" ]]; then
    "$ROOT/scripts/build.sh" assembleRelease
  else
    "$ROOT/scripts/build.sh" assembleDebug
  fi
fi

# 选 APK 的顺序很重要：**刚构建出来的那份必须优先**。
#
# 早先 debug 分支把 "$ROOT"/apk/dinner-*-debug.apk 排在第一位，而通配是按
# 文件名字典序展开的：apk/ 里同时躺着 dinner-1.0.0-debug.apk（旧）与
# 刚构建的 app-debug.apk 时，通配先命中 **1.0.0 的旧包**，
# 于是「install.sh 装上了、但装的不是刚改的代码」——调试时看到的现象全对不上，
# 而且从输出里看不出装的是旧包（只打印文件名）。
#
# 现在：Gradle 默认产物（一定是本次构建的）排第一，带版本号的归档包兜底。
# release 分支同理。
APK=""
if [[ "$VARIANT" == "release" ]]; then
  PACKAGE_ID="com.guolongli.dinner"
  CANDIDATES=(
    "$ROOT/app/build/outputs/apk/release/app-release.apk"
  )
else
  PACKAGE_ID="com.guolongli.dinner.debug"
  CANDIDATES=(
    "$ROOT/app/build/outputs/apk/debug/app-debug.apk"
  )
fi
# 兜底：package.sh 产出的带版本号归档包，取**最新**的那个（按 mtime，不是字典序）。
FALLBACK="$(ls -t "$ROOT"/apk/dinner-*-"$VARIANT".apk "$ROOT"/apk/app-"$VARIANT".apk 2>/dev/null | head -1 || true)"
if [[ -n "$FALLBACK" ]]; then
  CANDIDATES+=("$FALLBACK")
fi

for candidate in "${CANDIDATES[@]}"; do
  if [[ -f "$candidate" ]]; then
    APK="$candidate"
    break
  fi
done

if [[ -z "$APK" ]]; then
  echo "错误：找不到 $VARIANT APK，先运行 scripts/package.sh $VARIANT。" >&2
  exit 1
fi

# 打印版本号，避免「装上去的其实是旧包」这种只能靠猜的情况。
AAPT2="$(ls "$SDK"/build-tools/*/aapt2 2>/dev/null | head -1 || true)"
if [[ -n "$AAPT2" && -x "$AAPT2" ]]; then
  echo "APK 版本：$("$AAPT2" dump badging "$APK" 2>/dev/null | head -1 | cut -d' ' -f2-3)"
fi

DEVICES="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')"
if [[ -z "$DEVICES" ]]; then
  echo "错误：没有已授权的设备。先开启 USB 调试，或 adb connect 无线调试。" >&2
  "$ADB" devices >&2
  exit 1
fi

COUNT="$(printf '%s\n' "$DEVICES" | wc -l | tr -d ' ')"
ADB_ARGS=()
if [[ "$COUNT" -gt 1 ]]; then
  echo "检测到多台设备，使用第一台：$(printf '%s\n' "$DEVICES" | head -1)"
  ADB_ARGS=(-s "$(printf '%s\n' "$DEVICES" | head -1)")
fi

echo "安装 $VARIANT：$APK"
# -r 覆盖安装，-d 允许降级；debug 与 release 包名不同，互不影响。
"$ADB" "${ADB_ARGS[@]}" install -r -d "$APK"
"$ADB" "${ADB_ARGS[@]}" shell am start -n "$PACKAGE_ID/com.guolongli.dinner.MainActivity" >/dev/null 2>&1 || true
echo "完成。"
