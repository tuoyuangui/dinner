#!/usr/bin/env bash
# 出包入口：统一走根目录 Gradle + .toolchain 里的 JDK / SDK，无需 Android Studio。
#
# 用法：
#   ./scripts/build.sh :app:assembleDebug      # debug 包
#   ./scripts/build.sh :app:assembleRelease    # release 包
#   ./scripts/build.sh test                    # 单元测试
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Gradle 用户主目录（缓存 / native 库 / daemon 状态）。
#
# **必须放在 ext4 上，不能跟着工程留在 NTFS 里。**
# 工程本身在 /mnt/E_TEST（fuseblk），Gradle 会把 libnative-platform.so
# 解压到 $GRADLE_USER_HOME/native/ 并 dlopen 它；在 fuseblk 上这个解压 +
# 加锁的过程不稳定，表现为构建刚开始就挂：
#   Could not initialize native services.
#   > Failed to load native library 'libnative-platform.so' for Linux amd64
# 而且是**偶发**的——同一条命令重跑一次又好了，非常难定位。
# 挪到 ext4 的用户缓存目录后连续构建稳定通过。
GRADLE_HOME_DIR="${GRADLE_USER_HOME:-${XDG_CACHE_HOME:-$HOME/.cache}/dinner-gradle-home}"
mkdir -p "$GRADLE_HOME_DIR"
# 同一个目录也要用环境变量指一次：Gradle 早期（还没解析命令行参数时）
# 会自己去读 GRADLE_USER_HOME / HOME 来定位用户目录，
# 只给 `-g` 的话，那一步仍可能去找别处（这里就撞到过 /root/.gradle）。
export GRADLE_USER_HOME="$GRADLE_HOME_DIR"

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

# ---------------------------------------------------------------------------
# 本机环境适配（与业务代码无关，换个正常机器可以整段删掉）
#
# 这台机器的 seccomp 对 utimensat / futimens / utimes 一律返回 EPERM，而
# 「连属性一起拷贝」是 JDK 内建行为，AGP 合并资源、KSP 增量缓存都会走它，
# 于是整个构建会以 FileSystemException: 不允许的操作 失败。两层兜底：
#
#   1. --patch-module：把 sun.nio.fs.UnixCopyFile$Flags 里
#      “拷属性失败就当错误” 的开关关掉（tools/patch/PatchJdkCopyFlags.java）；
#      文件内容照旧完整拷贝，只是不再因为时间戳拷不过去而报错。
#   2. LD_PRELOAD：让剩余的直接 libc 调用（touch/timestamp）静默成功。
# ---------------------------------------------------------------------------
JDK_PATCH="$ROOT/.toolchain/jdk-patch"
if [[ -d "$JDK_PATCH" ]]; then
  # 注意：**末尾不能有多余空格**。
  # JDK_JAVA_OPTIONS 按空白切分参数，`--patch-module java.base=<路径> ` 里的
  # 路径会带上那个尾随空格，于是模块路径指向一个不存在的目录，
  # **补丁静默失效**——「拷属性失败就当错误」的开关又变回打开，
  # 而 Gradle 解压 libnative-platform.so 正是要拷属性的那一步：
  # 属性拷不过去 → 解压出来的 .so 不可执行 → dlopen 失败。
  # 表现就是那句 "Failed to load native library"，而且时好时坏
  # （取决于那一次解压有没有命中）。
  export JDK_JAVA_OPTIONS="--patch-module java.base=$JDK_PATCH${JDK_JAVA_OPTIONS:+ $JDK_JAVA_OPTIONS}"
fi

#
# LD_PRELOAD 的 shim 默认**不启用**。
#
# 原因（踩过的坑）：Gradle 启动时要 dlopen libnative-platform.so，
# 那一步依赖解压出来的 .so 具有可执行权限。而 shim 一旦拦掉 chmod，
# 解压出来的库就变成「返回成功但权限没落」的不可执行文件，
# 于是每次构建都在最开始就挂：
#   Could not initialize native services.
#   > Failed to load native library 'libnative-platform.so' for Linux amd64
#
# 而 JDK 侧的 --patch-module 已经把「拷属性失败就当错误」的开关关掉了，
# 资源合并、KSP 增量缓存都不再依赖那些被 seccomp 拦掉的时间戳调用，
# 因此完全不需要 LD_PRELOAD。
#
# 万一换台机器还是撞上 utimensat 的 EPERM，用
#   DINNER_NOUTIME_PRELOAD=1 ./scripts/build.sh ...
# 显式打开；且该 shim 已不再拦截 chmod（见 noutime.c 里的注释）。
NOUETIME_SO="/home/liguolong/.cache/noutime/noutime.so"
if [[ "${DINNER_NOUTIME_PRELOAD:-0}" == "1" && -f "$NOUETIME_SO" ]]; then
  export LD_PRELOAD="$NOUETIME_SO"
fi

# ---------------------------------------------------------------------------
# 禁用 Gradle 的 native 集成
#
# Gradle 启动时要 dlopen libnative-platform.so（用于文件监听和平台探测）。
# 这台机器上这一步会失败：
#   Could not initialize native services.
#   > Failed to load native library 'libnative-platform.so' for Linux amd64
#
# 根因是解压出来的 .so 没有执行位：
# 上面的 --patch-module 把 JDK「拷属性失败就当错误」的开关关掉了，
# 副作用是 Gradle 从 jar 里解压 native 库时，条目上的执行位被**静默丢弃**，
# 得到 644 的不可执行文件，dlopen 直接失败。
#
# **必须用 GRADLE_OPTS 传，不能用命令行 -D**：
# `org.gradle.native` 是通过 `System.getProperty()` 读的（见
# NativeServices$NativeServicesMode.fromSystemProperties），
# 也就是「启动器 JVM 的系统属性」。
# 命令行 `-Dorg.gradle.native=false` 是在**参数解析之后**才生效的，
# 而 native services 恰恰在参数解析**之前**初始化——
# 于是这个开关对要禁用的那一步根本没生效。
# GRADLE_OPTS 会被拼进启动器 JVM 的命令行，是唯一正确的传法。
# 实测：改用 GRADLE_OPTS 后，这句报错彻底消失。
#
# 关掉 native 集成不影响构建正确性——它只负责文件监听与平台探测，
# 关掉后每次构建多花一两秒重新扫描。
# ---------------------------------------------------------------------------
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.native=false"

GRADLE_ARGS=(--no-daemon --console=plain -g "$GRADLE_HOME_DIR")

# 调用方可能自带 --console=plain / --stacktrace 之类，
# 重复给同一个选项 Gradle 会直接报 "Multiple arguments were provided" 并打印帮助。
# 这里把重复的 --console 去掉，只保留脚本里这一份。
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

# ---------------------------------------------------------------------------
# 兜底重试
#
# 上面那个 native 库报错在这台机器上仍是**偶发**的（同样的命令，
# 连跑 6 次可能挂 1 次），而且失败发生在构建最开始——
# 意味着这一轮构建**一个字都还没编译**，重跑一次没有任何浪费。
#
# 所以：碰到这个报错就自动重试，最多 3 次。
# 只针对这一个报错；其它失败照常抛出来，不掩盖真问题。
# ---------------------------------------------------------------------------
run_gradle() {
  local attempt=1
  local max_attempt=3
  local log_file
  local status
  while true; do
    log_file="$(mktemp)"
    # **注意**：`local status` 这一行本身就是个命令，
    # 它执行之后 `PIPESTATUS` 会被覆盖成这个命令的状态（0）。
    # 所以 `local` 声明必须在读 PIPESTATUS **之前**做完，
    # 读 PIPESTATUS[0] 的那一行不能再夹任何别的命令。
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
