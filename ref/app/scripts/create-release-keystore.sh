#!/usr/bin/env bash
# 生成本机 release 签名材料（一次性，产物不入库）。
#
# 用法：
#   scripts/create-release-keystore.sh           # 已存在则跳过
#   scripts/create-release-keystore.sh --force   # 覆盖重建
#
# 产物：
#   .android-keystore/release.keystore   签名密钥库
#   keystore.properties                  storeFile / 密码 / alias，权限 600
#
# 两者都在 .gitignore 里，禁止提交。密码随机生成，只写在本机。
# 注意：release 包一旦对外分发，请另行备份 keystore 与密码；
# 丢失后无法为已安装用户提供可覆盖升级的新版本。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-/usr}"

KEYTOOL="$JAVA_HOME/bin/keytool"
if [[ ! -x "$KEYTOOL" ]]; then
  KEYTOOL="$(command -v keytool || true)"
fi
if [[ -z "$KEYTOOL" || ! -x "$KEYTOOL" ]]; then
  echo "错误：找不到 keytool。安装 JDK 或设置 JAVA_HOME。" >&2
  exit 1
fi

STORE_DIR="$ROOT/.android-keystore"
STORE_FILE="$STORE_DIR/release.keystore"
PROPS_FILE="$ROOT/keystore.properties"
KEY_ALIAS="dinner"

FORCE=0
if [[ "${1:-}" == "--force" ]]; then
  FORCE=1
fi

if [[ -f "$STORE_FILE" && -f "$PROPS_FILE" && "$FORCE" -eq 0 ]]; then
  echo "release 签名已存在，跳过："
  echo "  $STORE_FILE"
  echo "  $PROPS_FILE"
  echo "需要重建请加 --force（会作废现有 release 包的升级能力）。"
  exit 0
fi

mkdir -p "$STORE_DIR"

PASSWORD="$(head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)"
if [[ ${#PASSWORD} -lt 12 ]]; then
  echo "错误：随机密码生成失败。" >&2
  exit 1
fi

echo "生成 release keystore：$STORE_FILE"
"$KEYTOOL" -genkeypair \
  -keystore "$STORE_FILE" \
  -storetype PKCS12 \
  -alias "$KEY_ALIAS" \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -storepass "$PASSWORD" \
  -keypass "$PASSWORD" \
  -dname "CN=Guolong Li, OU=dinner, O=dinner, L=Qingdao, ST=Shandong, C=CN" \
  2>&1 | sed 's/^/  /'

umask 077
cat > "$PROPS_FILE" <<EOF
# 由 scripts/create-release-keystore.sh 生成，禁止提交（见 .gitignore）。
# 路径相对仓库根目录。
storeFile=.android-keystore/release.keystore
storePassword=$PASSWORD
keyAlias=$KEY_ALIAS
keyPassword=$PASSWORD
EOF
chmod 600 "$PROPS_FILE"
chmod 600 "$STORE_FILE"

echo
echo "完成："
echo "  keystore : $STORE_FILE"
echo "  属性文件 : $PROPS_FILE（权限 600，不入库）"
echo "  别名     : $KEY_ALIAS"
echo
echo "提示：请自行备份上面两个文件。丢失后无法为已安装用户提供可覆盖升级的新版本。"
