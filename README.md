# 食伴 (dinner)

家人与饭搭子共用的一本小册子：记一日三餐，也记风景、学习、运动、创作。一条可以放 0 到 9 张照片或视频，写一句备注，打几颗星，别人还能在下面评论。数据直接存在你们自己的百度网盘里，不经过任何第三方服务器。

- 版本：3.8.0（versionCode 10）
- 设计稿：/设计稿/
- 包名：`com.guolongli.dinner`（debug 包为 `com.guolongli.dinner.debug`）
- 技术栈：Kotlin + Jetpack Compose 
- 授权：百度网盘授权已实现，
- 存储：默认目录 `/apps/dinner/`（只允许 `/apps/` 之下；不存在会自动创建）
- 开发者：Guolong Li <guolongli_upc@foxmail.com>
- 协议：MIT

## 功能

| 功能 | 说明 |
|---|---|
| 时间线 | 按天分组，顶部显示「一共记了多少条、跨越多少天」；卡片展示九宫格媒体、备注、评分与评论预览 |
| 索引 | 顶部「全部 / 星标 / 九种类型」单选筛选（筛选条常驻，空结果也能一键回「全部」）；跨月时出现月份条，点一下滚到那个月 |
| 日历 | 时间线右上角或摘要卡进入月历，实心圆标出哪天有记录，点一天只看那天；只读本地，不耗接口配额 |
| 记录类型 | 早 / 午 / 晚 / 加餐，加上风景 / 学习 / 运动 / 创作 / 其他——不只装得下吃饭 |
| 多媒体 | 一条记录 0..9 段媒体，照片视频可混排；照片统一转 JPEG（HEIF / AVIF 也能读） |
| 视频 | 九宫格用封面帧 + 时长角标，点开全屏播放 |
| 压缩存储 | 「我的 → 本地数据」一个开关同时管照片和视频，默认关：原图原视频直存。打开后照片长边压到 1920、视频降到 1280；视频转码失败就保留原片，不会为了省空间把素材弄坏 |
| 评论 | 每条记录下面可以评论，删除同步给所有人；卡片上直接预览最近两条 |
| 成员 | 管理员建群时设两个口令（管理员口令 + 动态入群密码）。成员填对管理员手机号与入群密码即可进群，上限 10 人；每人独立身份与头像 |
| 口令 | 两份口令都以**明文**存在云端 `config/passwd.json`（产品要求，不改哈希）：`admin_password` 与 `join_password`。入群只验后者；管理员要改 / 删别人的内容、换入群密码、转让管理权，必须先在「群组」页输入管理员口令，解锁只在本次运行的内存里有效，别人在别处换了口令会立刻失效 |
| 删除 | 只删本地 / 只删云端 / 都删三档，选完档位还要再确认一次；只能删自己上传的云端文件 |
| 管理员 | 组内一位管理员可修改、删除所有人的记录与评论；可转让，默认是最早加入的人。这些动作在后端一律走 `AdminGate.requireAdmin()`，藏按钮不算权限 |
| 应用锁 | 打开应用时用指纹 / 人脸 / 锁屏密码验证，应用本身不保存任何口令 |
| 统计 | 「我的」顶部三个数字可点：总记录 / 我记的 / 已同步，点进去是那批记录的明细列表 |
| 本地保留 | 默认只留最近 30 天（可改 7 / 30 / 90 / 180 / 永久）；清理前逐个确认云端有副本，确认不了就不删。只清本机副本，记录本身与云端都不动 |
| 区间取回 | 选一段时间，从云端把记录与评论补回本地；也可以「取回全部」。照片视频点开时再下载，不烧接口配额 |
| 网盘重试 | 「我的 → 网盘连接」可设 1 / 2 / 3 / 5 次，默认 3 次；只重试频控与断网这类瞬时故障，都失败才报错 |
| 自动取回 | 授权成功、配对加入、绑定手机号都会自动拉一次云端历史；进时间线也会先拉后推，别人的新记录不用等自己编辑才出现 |
| 离线优先 | 先写本地数据库，联网后自动补传；失败如实标记，可手动重试 |

## 环境要求

| 软件 | 版本 | 说明 |
|---|---|---|
| JDK | 17 或更高 | AGP 9.x 需要（Temurin / OpenJDK 17 均可） |
| Android SDK | platform 36 + build-tools 36 | `compileSdk 36`，`minSdk 28`（Android 9+） |
| Gradle | 8.9 或更高 | `scripts/build.sh` 优先用 `.toolchain/gradle-8.9`，没有则回退到系统 `gradle`，无需 Android Studio |

不需要安装 Android Studio：只要有 JDK、Android SDK 命令行工具和 Gradle，就能出包并安装。

## 命令行构建与安装

### 第 1 步：准备 Android SDK（只需一次）

```bash
# 目录可以随意换，下文以 ~/android-sdk 为例
mkdir -p ~/android-sdk/cmdline-tools && cd ~/android-sdk/cmdline-tools
# 从 https://developer.android.com/studio#command-tools 下载 commandlinetools-linux-*.zip 后：
unzip commandlinetools-linux-*.zip
mv cmdline-tools latest   # 让 sdkmanager 位于 cmdline-tools/latest/bin

export ANDROID_SDK_ROOT=~/android-sdk
yes | $ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager --licenses
$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager \
  "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

把 `export ANDROID_SDK_ROOT=...` 写进 `~/.bashrc`，后续就不用每次设置。

### 第 2 步 打包 debug 与 release

日常开发用 `build.sh` 就够。要出两个可安装的成品包（自己用一份、发给对方一份），用 `package.sh`：

```bash
./scripts/package.sh              # debug + release
./scripts/package.sh release      # 只出 release
./scripts/package.sh debug        # 只出 debug
./scripts/package.sh all --clean  # 先 clean 再全量构建
```

产物按版本号命名，多个版本可以并存：

```
apk/dinner-3.1.0-debug.apk       applicationId: com.guolongli.dinner.debug
apk/dinner-3.1.0-release.apk     applicationId: com.guolongli.dinner
```

每种构建还会额外留一份固定名字的副本，方便直接发给人安装（永远指向最新一次构建）：

```
apk/dinner.apk                   debug 包
apk/dinner-release.apk           release 包
```

debug 与 release 的包名不同，可以同时装在手机上；桌面图标相同，认名字里的版本号区分。

release 目前不做代码混淆与资源压缩（`isMinifyEnabled = false`），只做正式签名与去掉 debug 后缀，便于对照排查问题。

首次安装需在系统设置里允许「安装未知来源应用」。


## 开发约定

- 只写 Kotlin 与 Compose，不引入 XML 主 UI、Java 源码、自建 WebView UI。
  百度 OAuth SDK 自带的授权 WebViewActivity 是官方组件，不属于「自建 WebView UI」。
- Composable 不直接访问网络或文件；远端媒体一律先经 `MediaCache` 落到本机。
- 所有颜色、间距、圆角、字号取自 `ui/theme`，不写魔法数字。
- 详细的红线、接口约定、同步策略与数据模型以源码注释为准：
  `data/netdisk/`（网盘接口与频控）、`data/sync/`（先拉后推与发件箱）、
  `data/local/`（Room 实体与 Dao）、`data/auth/`（凭据与 Keystore 密钥隔离）。

## 代码仓库

本仓库是**可重新构建出安装包的有效代码备份**：只存源码、脚本与文档，构建产物一律不入库。

| 入库 | 内容 |
|---|---|
| `app/src/`、`app/schemas/` | Kotlin / Compose 源码、资源、Room 导出 schema |
| `build.gradle.kts`、`settings.gradle.kts`、`gradle.properties`、`gradle/libs.versions.toml` | 构建脚本与依赖版本目录 |
| `scripts/`、`tools/` | 出包脚本、JDK 补丁、调试签名证书 |
| `设计稿/`、`ref/` | 视觉目标与百度网盘接口文档存档 |
| `README.md`、`LICENSE`、`.gitignore`、`.gitattributes` | 说明、协议与忽略规则 |

| 不入库（见 `.gitignore`） | 原因 |
|---|---|
| `apk/`、`*.apk`、`*.aab` | 安装包是产物，随时可由源码重新构建 |
| `.gradle/`、`.kotlin/`、`**/build/`、`.cxx/` | 构建缓存与中间产物，体积大且与本机环境绑定 |
| `.toolchain/`、`local.properties` | 本机 JDK / Android SDK / Gradle 路径，工具链约 4.6 GB |
| `.workbuddy/`、`check/`、`.Trash-0/` | 本地工具会话、临时校验工程、回收站残留 |

克隆后先装好 JDK 17 与 Android SDK 36（`compileSdk 36`），再按上一节出包：

```bash
./scripts/package.sh all --clean   # 全量构建，产物落在 apk/
```

## 许可

MIT License，见 [`LICENSE`](LICENSE)。
