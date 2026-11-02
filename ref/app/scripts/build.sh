#!/usr/bin/env bash
# 无需 Android Studio 的命令行构建入口。
#
# 用法：
#   scripts/build.sh                 # 等价于 assembleDebug，并复制 APK 到 apk/
#   scripts/build.sh clean assembleDebug
#   scripts/build.sh testDebugUnitTest
#
# 约定（详见 README「命令行构建」）：
#   - JDK 17+，默认用 JAVA_HOME，未设置时回退系统 /usr。
#   - Android SDK 由 ANDROID_SDK_ROOT / ANDROID_HOME 指定，
#     未设置时回退仓库内 .toolchain/sdk。
#   - Gradle 优先用仓库根目录自带的 gradle-x.y.z/bin/gradle，
#     其次 gradlew，最后 PATH 里的 gradle。
#   - Gradle home 优先用仓库内 .gradle-home（libnative-platform.so 依赖此目录）。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-/usr}"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$ROOT/.toolchain/sdk}}"
export ANDROID_SDK_ROOT="$SDK"
if [[ ! -d "$SDK/platforms" ]]; then
  echo "错误：找不到 Android SDK（$SDK）。" >&2
  echo "请安装 cmdline-tools 后执行 sdkmanager 安装 platform 与 build-tools，或设置 ANDROID_SDK_ROOT。" >&2
  exit 1
fi

# local.properties 不入库；缺失时按当前 SDK 路径补一份。
if [[ ! -f local.properties ]]; then
  printf 'sdk.dir=%s\n' "$SDK" > local.properties
  echo "已生成 local.properties（sdk.dir=$SDK）"
fi

GRADLE_BIN=""
if [[ -x "$ROOT/gradlew" ]]; then
  GRADLE_BIN="$ROOT/gradlew"
else
  for candidate in "$ROOT"/gradle-*/bin/gradle; do
    if [[ -x "$candidate" ]]; then
      GRADLE_BIN="$candidate"
      break
    fi
  done
fi
if [[ -z "$GRADLE_BIN" ]]; then
  GRADLE_BIN="$(command -v gradle || true)"
fi
if [[ -z "$GRADLE_BIN" ]]; then
  echo "错误：找不到 Gradle。把 gradle 加进 PATH，或在仓库根目录放一份 gradle-x.y.z/。" >&2
  exit 1
fi

GRADLE_HOME_ARGS=()
if [[ -d "$ROOT/.gradle-home" ]]; then
  GRADLE_HOME_ARGS=(-g "$ROOT/.gradle-home")
fi

# 受限容器 / 沙箱有时只放行 utimensat(2)，拦截 futimens(3) / utimes(3) 等旧接口。
# AGP 的 stripDebugDebugSymbols、transformClassesWithAsm 在拷贝文件后会用这些接口
# 写时间戳，于是整条构建以 EPERM（"不允许的操作"）失败。
# 本机若编了 .toolchain/utime-shim.so（属本机工具链，不入库），这里自动预加载。
# 普通桌面环境探测会通过，不会走这个分支。
UTIME_PROBE="$ROOT/.utime-probe"
if ! touch "$UTIME_PROBE" 2>/dev/null; then
  UTIME_SHIM="$ROOT/.toolchain/utime-shim.so"
  if [[ -f "$UTIME_SHIM" ]]; then
    export LD_PRELOAD="$UTIME_SHIM${LD_PRELOAD:+:$LD_PRELOAD}"
    echo "注意：当前环境禁止写文件时间戳，已预加载 .toolchain/utime-shim.so"
  else
    echo "警告：当前环境禁止写文件时间戳，原生库 strip 等任务可能失败。" >&2
    echo "      如需兜底，可编译 .toolchain/utime-shim.c 后重试。" >&2
  fi
fi
rm -f "$UTIME_PROBE" 2>/dev/null || true

TASKS=("$@")
if [[ ${#TASKS[@]} -eq 0 ]]; then
  TASKS=(assembleDebug)
fi

echo "Gradle : $GRADLE_BIN"
echo "JDK    : $JAVA_HOME"
echo "SDK    : $ANDROID_SDK_ROOT"
echo "Tasks  : ${TASKS[*]}"

"$GRADLE_BIN" --no-daemon --console=plain "${GRADLE_HOME_ARGS[@]}" "${TASKS[@]}"

APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
if [[ -f "$APK" ]]; then
  mkdir -p "$ROOT/apk"
  cp -f "$APK" "$ROOT/apk/app-debug.apk"
  echo
  echo "APK: $APK"
  echo "副本: $ROOT/apk/app-debug.apk"
  echo "安装: scripts/install.sh"
fi
