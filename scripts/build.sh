#!/usr/bin/env bash
# 出包入口：统一走根目录 Gradle + .toolchain 里的 JDK / SDK，无需 Android Studio。
#
# 用法：
#   ./scripts/build.sh :app:assembleDebug      # debug 包
#   ./scripts/build.sh :app:assembleRelease    # release 包
#   ./scripts/build.sh test                    # 单元测试
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Gradle 用户主目录必须放在 ext4，不能留在 NTFS，否则 native 库解压/加载会偶发失败。
GRADLE_HOME_DIR="${GRADLE_USER_HOME:-${XDG_CACHE_HOME:-$HOME/.cache}/dinner-gradle-home}"
mkdir -p "$GRADLE_HOME_DIR"
export GRADLE_USER_HOME="$GRADLE_HOME_DIR"

# 构建产物目录同理，NTFS 上 aapt2 / utime 会报「不允许的操作」。
export DINNER_BUILD_DIR="${DINNER_BUILD_DIR:-$HOME/.cache/dinner-build/app}"

GRADLE="${GRADLE:-$ROOT/.toolchain/gradle-8.9/bin/gradle}"
if [[ ! -x "$GRADLE" ]]; then
  GRADLE="$(command -v gradle || true)"
fi
if [[ -z "$GRADLE" ]]; then
  echo "错误：找不到 Gradle（优先 .toolchain/gradle-8.9/bin/gradle）" >&2
  exit 1
fi

export JAVA_HOME="${JAVA_HOME:-$ROOT/.toolchain/jdk17}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ROOT/.toolchain/sdk}"

# 本机 seccomp 会拦截 utimensat 等调用，用 patch-module 关掉「拷属性失败就当错误」。
# 注意：路径后不能有空格，否则 JDK_JAVA_OPTIONS 切分错误，补丁静默失效。
JDK_PATCH="$ROOT/.toolchain/jdk-patch"
if [[ -d "$JDK_PATCH" ]]; then
  export JDK_JAVA_OPTIONS="--patch-module java.base=$JDK_PATCH${JDK_JAVA_OPTIONS:+ $JDK_JAVA_OPTIONS}"
fi

# 可选：显式开启 LD_PRELOAD shim（默认关闭，仅换机器仍撞 EPERM 时用）。
NOUTIME_SO="$HOME/.cache/noutime/noutime.so"
if [[ "${DINNER_NOUTIME_PRELOAD:-0}" == "1" && -f "$NOUTIME_SO" ]]; then
  export LD_PRELOAD="$NOUTIME_SO"
fi

# 禁用 Gradle native 集成，避免 libnative-platform.so 加载失败。
# 必须用 GRADLE_OPTS 传，命令行 -D 在参数解析后生效，来不及。
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.native=false"

GRADLE_ARGS=(--no-daemon --console=plain -g "$GRADLE_HOME_DIR")

# 去掉调用方重复的 --console，避免 Gradle 报 "Multiple arguments were provided"。
ARGS=()
seen_console=false
for arg in "$@"; do
  case "$arg" in
    --console=*|--console)
      if [[ $seen_console == false ]]; then
        ARGS+=("$arg")
        seen_console=true
      fi
      ;;
    *) ARGS+=("$arg") ;;
  esac
done

# 兜底重试：native 库加载失败是偶发问题，且发生在构建最开始，重跑无浪费。
run_gradle() {
  local attempt=1 max_attempt=3 log_file status
  while true; do
    log_file="$(mktemp)"
    set +e
    "$GRADLE" "${GRADLE_ARGS[@]}" "${ARGS[@]}" 2>&1 | tee "$log_file"
    status=${PIPESTATUS[0]}
    set -e
    if [[ $status -eq 0 ]] || ! grep -q "Failed to load native library" "$log_file"; then
      rm -f "$log_file"
      return $status
    fi
    rm -f "$log_file"
    if [[ $attempt -ge $max_attempt ]]; then
      echo "[build] native 库加载连续失败 ${attempt} 次，放弃" >&2
      return $status
    fi
    attempt=$((attempt + 1))
    echo "[build] native 库加载失败，重试（第 ${attempt}/${max_attempt} 次）…" >&2
    sleep 2
  done
}

run_gradle "$@"