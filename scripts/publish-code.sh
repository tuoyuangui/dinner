#!/usr/bin/env bash
# 一键备份代码到托管平台（默认 gitcode.com/tuoyuangui/dinner）。
#
# 只提交「有效代码与文档」：apk/、**/build/、.toolchain/ 等产物由 .gitignore
# 挡在门外，所以这里可以放心用 `git add -A`。
#
# 用法：
#   ./scripts/publish-code.sh                  # 提交（说明自动带版本号）并推送
#   ./scripts/publish-code.sh "只改了 README"   # 用自定义提交说明
#   ./scripts/publish-code.sh --dry-run        # 只看会提交什么，不提交不推送
#
# 鉴权：优先用环境变量 $GITCODE_TOKEN（托管平台的访问令牌）；没有则走
# git 自己的凭据配置（首次会提示输入用户名 + 令牌，密码方式已被平台禁用）。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

REMOTE="${REMOTE:-origin}"
BRANCH="${BRANCH:-main}"
DRY_RUN=0
MESSAGE=""
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    -m) ;;                      # 允许 `-m "说明"` 写法，说明本身当位置参数
    *) if [ -z "$MESSAGE" ]; then MESSAGE="$arg"; fi ;;
  esac
done

# 版本号只从 app/build.gradle.kts 的 appVersionName 读：
# `gradle :app:properties` 拿到的是 project version（恒 unspecified），靠不住。
VERSION="$(sed -n 's/^[[:space:]]*val appVersionName[[:space:]]*=[[:space:]]*"\(.*\)".*/\1/p' app/build.gradle.kts | head -1)"
VERSION="${VERSION:-unknown}"

if [ ! -d .git ]; then
  git init -b "$BRANCH" -q
  git remote add origin https://gitcode.com/tuoyuangui/dinner.git
fi
[ -n "$(git remote)" ] || git remote add origin https://gitcode.com/tuoyuangui/dinner.git

git add -A

if git diff --cached --quiet; then
  echo "工作区没有需要提交的改动（版本 $VERSION）。"
else
  echo "== 将要提交的改动 =="
  git diff --cached --stat | tail -20
fi

if [ "$DRY_RUN" = "1" ]; then
  echo "--dry-run：未提交、未推送。"
  exit 0
fi

if ! git diff --cached --quiet; then
  [ -n "$MESSAGE" ] || MESSAGE="chore: 备份 食伴 $VERSION 源码"
  git commit -q -m "$MESSAGE"
  echo "已提交：$(git log -1 --oneline)"
fi

# 令牌只在这一条命令的 credential helper 里临时展开，不落盘到 .git/config。
if [ -n "${GITCODE_TOKEN:-}" ]; then
  GIT_TERMINAL_PROMPT=0 git -c credential.helper='!f() { echo username=tuoyuangui; echo password=$GITCODE_TOKEN; }; f' \
    push -u "$REMOTE" "$BRANCH"
else
  git push -u "$REMOTE" "$BRANCH"
fi

echo "完成：$BRANCH 与 $REMOTE/$BRANCH 已同步（$(git rev-parse --short HEAD)）。"
