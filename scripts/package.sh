#!/usr/bin/env bash
# 出成品包：debug / release / 两个都要。
#
# 用法：
#   ./scripts/package.sh              # debug + release
#   ./scripts/package.sh debug        # 只出 debug
#   ./scripts/package.sh release      # 只出 release
#   ./scripts/package.sh --clean      # 先 clean 再全量构建（默认 all）
#   ./scripts/package.sh all --clean  # 同上，显式写 all
#
# 产物落在 apk/，两种名字同时给：
#   apk/dinner-<版本>-debug.apk   带版本号，多版本可并存
#   apk/dinner.apk                固定名字，永远是最新那份，直接发人安装
#
# 构建细节（JDK 补丁、SDK 路径、构建目录重定向）都在 build.sh 里，
# 这里只负责「跑哪些任务」和「跑完把产物摊出来」，不重复造一遍环境适配。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_SH="$ROOT/scripts/build.sh"
APK_DIR="$ROOT/apk"

usage() {
  cat <<'EOF'
用法: ./scripts/package.sh [debug|release|all] [--clean]

  debug      只出 debug 包（applicationId 带 .debug 后缀，可与正式包并存）
  release    只出 release 包（去掉 .debug 后缀，正式签名）
  all        两个都出（默认）
  --clean    先执行 clean，再全量构建。改了资源 / 图标 / 签名之后务必带上

参数顺序不限，例如：./scripts/package.sh --clean release
EOF
}

TARGET="all"
CLEAN=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    debug | release | all)
      TARGET="$1"
      ;;
    --clean)
      CLEAN=true
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      echo "未知参数: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
  shift
done

if [[ ! -x "$BUILD_SH" ]]; then
  echo "错误：找不到可执行的 $BUILD_SH" >&2
  exit 1
fi

mkdir -p "$APK_DIR"

# clean 单独跑一次，不和 assemble 混在同一条命令里：
# 混着跑时任务顺序依赖 Gradle 的调度，一旦并行就成了「一边删一边编译」。
if [[ "$CLEAN" == true ]]; then
  echo "==> clean"
  "$BUILD_SH" clean
fi

case "$TARGET" in
  debug)
    TASKS=(":app:exportDebugApk" ":app:exportDebugApkAlias")
    ;;
  release)
    TASKS=(":app:exportReleaseApk" ":app:exportReleaseApkAlias" ":app:exportVersionedSpacedApk" ":app:exportVersionedReleaseApk")
    ;;
  all)
    TASKS=(":app:exportApks")
    ;;
esac

echo "==> 构建 $TARGET"
"$BUILD_SH" "${TASKS[@]}"

# 清掉上一版的产物：apk/ 里堆着 *.*.* 时，
# 用户很容易把旧包当成新的发出去（文件名只差一个版本号，肉眼很难分清）。
# 只删带版本号的那些，固定别名（dinner.apk 等）留着——它们刚被覆盖成最新的。
#
# 版本号从 app/build.gradle.kts 里读 `appVersionName`，
# 不用 gradle properties（那里的 version 是 project 版本，恒为 unspecified）。
CURRENT_VERSION="$(sed -n 's/^val appVersionName = "\([^"]*\)".*/\1/p' \
  "$ROOT/app/build.gradle.kts" | head -1)"
if [[ -z "$CURRENT_VERSION" ]]; then
  echo "警告：读不到 appVersionName，跳过清理旧产物" >&2
else
  find "$APK_DIR" -maxdepth 1 -type f -name "dinner*.apk" \
    ! -name "dinner.apk" ! -name "dinner-release.apk" \
    ! -name "dinner-${CURRENT_VERSION}.apk" \
    ! -name "dinner ${CURRENT_VERSION}.apk" \
    ! -name "dinner-${CURRENT_VERSION}-debug.apk" \
    ! -name "dinner-${CURRENT_VERSION}-release.apk" \
    -print -delete 2>/dev/null || true
fi

echo
echo "==> 产物"
ls -lh "$APK_DIR" | tail -n +2