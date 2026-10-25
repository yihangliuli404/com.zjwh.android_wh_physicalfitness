# 本地运动实验室（Android）

原生 Kotlin + Jetpack Compose 的离线运动数据工具，Android 10（API 29）及以上可安装，无需 Root。普通用户无需安装 Python、Java、Termux 或 Android Studio。原 Python 项目保留在仓库 `协议/`，本工程独立位于 `android/`。

应用不连接校园跑平台，不登录校园账号，不提交模拟轨迹，也不绕过成绩校验。所有记录均为本地未认证数据。

## 普通用户

### APK 下载与安装

1. 查看仓库根目录 [`release/`](../release/) 中的 `local-fitness-1.0.0-debug.apk`。实际构建状态见[测试报告](docs/测试报告.md)。
2. 或打开 GitHub → Actions →「Android 离线应用构建」→ 成功的运行 → Artifacts → `local-fitness-debug-apk`，下载后解压 ZIP，取出 APK。Artifact 有效期 30 天，下载可能需要 GitHub 登录；本阶段没有创建 GitHub Release 下载页。
3. 将 APK 传到手机，用文件管理器打开。按系统提示，仅为当前文件管理器临时允许「安装未知应用」。
4. 安装后打开「本地运动实验室」。这是 debug 测试包；安装完成后可关闭未知来源安装权限。

### 首次启动与功能教程

- 首次启动数据库为空，不需要账号、服务器地址或开发环境。
- **首页**：查看本地记录数、模拟记录数、非模拟累计距离；入口跳转各功能。
- **添加运动记录**：输入标题、日期（`YYYY-MM-DD`）、距离（米）、时长（整数秒），保存为手动未认证记录。记录页点击详情，可编辑手动记录或确认删除。
- **历史记录**：按运动日期倒序，支持标题搜索、全部/非模拟/模拟筛选。
- **本地模拟**：输入 10–50000 米、60–86400 秒，生成赤道附近人工圆形轨迹。预览显示实际折线距离，点击保存后才进入历史。始终显示模拟标签，与真实运动无关。
- **距离与配速**：工具页输入距离和时长，实时显示公里数、每公里配速及公里/小时。记录中的轨迹距离按 Haversine 球面公式计算。
- **导入导出**：记录页导出全部，详情页导出单条。支持 UTF-8 JSON/CSV；由系统文件选择器指定文件。导入前验证整批数据，任何错误均不写入。重复导入会生成新记录，不覆盖原数据。
- **本地测试**：首页或工具页进入；执行 5 项应用内自检，或在记录详情校验轨迹结构。保存最近 100 条日志。这些日志不等于开发者 JUnit、真机测试或运动真实性证明。
- **设置与帮助**：切换跟随系统/浅色/深色主题，查看格式限制及隐私说明。

### 权限与数据

申请 Android 普通权限 INTERNET，仅用于应用内固定 127.0.0.1 Mock Server 的 HTTP 传输；它不会产生运行时授权弹窗。代码不接受外部地址、不跟随重定向、不使用代理，没有校园平台请求路径。不申请定位、相机、通知或广泛存储权限。读写文件使用 Android 系统文件选择器对所选文件的授权。不访问校园账号，不采集 GPS，没有后台常驻服务；Mock 随应用进程结束，已发出的本地请求可能在切后台后完成。运动档案、测试任务分别保存于独立 DataStore；模拟成绩保存于独立 SQLite 测试数据库，禁用自动备份和设备迁移备份；卸载会删除记录和设置，请先导出。若文件选择器提供云盘，云盘行为由其提供者负责。

### 常见问题

| 问题 | 处理方式 |
| --- | --- |
| 无法安装 | 确认 Android 10+；下载并解压真实 APK；必要时重新下载并核对 SHA-256 |
| 提示签名冲突 | 不同构建机器默认调试密钥不同。先导出数据，再卸载旧版本后安装；卸载会删除私有数据 |
| 导入失败 | 仅支持本应用格式，支持本应用记录、解码后的 Python GPS 数组和纯轨迹 CSV；不支持未解压 OBS 包、任意校园业务响应、GPX/FIT；检查 UTF-8、列名和数据范围 |
| 模拟距离略有偏差 | 显示实际采样折线距离，圆周近似存在微小误差 |
| 未出现真实运动/校园成绩 | 本应用没有真实 GPS 采集和平台对接功能；本地数据不是成绩证明 |
| 文件选择取消/不可写 | 取消不会写入；重新选择允许写入的位置 |
| 本地读取失败 | 应用停止写入以保护原数据；不要直接清除应用数据，保留设备与错误信息供排查 |
| 从早期原型升级后 Mock 出现 EPERM | 本次 Android 10 模拟器曾出现安装权限状态异常，清洁安装后恢复；先导出数据，再卸载重装。不要关闭系统安全校验或申请 Root |

## 协议分析与 Mock 测试功能

首页「协议研究与 Mock 实验」提供六个入口，均为中文界面：

1. **协议模块分析**：查看依据实际 7.3.92 源码生成的九模块类/方法、参数、返回值、依赖及异常说明。只是静态资料，不执行 Python 协议。
2. **GPS 轨迹分析**：选择本地轨迹，查看 GPS 数量、距离、时长、配速、实际点结构；支持应用 JSON/CSV、Python `lat/lon/time` 数组和纯轨迹 CSV，点级毫秒精度不丢弃，总时长要求完整秒。
3. **数据传输测试**：选择预设场景和轨迹快照，或自动生成「测试数据」。创建任务不发送请求，点击第一级按钮才向本机 Mock Server 发送 HTTP PUT。
4. **结算状态分析**：上传确认后可点击第二级按钮。Mock 返回断连、超时、HTTP 拒绝、202 受理、200 业务失败/完成或坏 JSON；HTTP 200 不自动视为业务成功。
5. **三级状态验证**：上传、结算、入账独立展示。第三级独立 GET 查询测试 SQLite，只有匹配任务的有效回执才能显示测试入账成功；绝不是校园成绩。
6. **历史记录与日志**：运动历史仍在底部「记录」，本页单独管理最多 20 个测试任务/25000 个测试点，支持异常筛选、状态回查、时间、响应、错误和变更日志。报告导出为 `testOnly=true` 的 JSON，含任务与本地自检结果，不作为轨迹备份重新导入。

Mock Server 仅绑定 `127.0.0.1` 动态端口，HTTP 明文也只允许该地址；正常离线使用无需设置服务器。上传缓存只保存在当前进程，SQLite 测试回执和任务日志会保留。应用重启后，进行中的请求标记中断；结算可能要求重新上传，独立入账查询仍可读取测试库。超时表示结果未知，不能认定请求一定未被服务端处理。

源码报告：

- [Python 模块分析与业务流程图](docs/Python模块分析报告.md)
- [函数级完整源码索引](docs/源码接口索引.md) / [实际文件依赖图](docs/文件依赖图.md)
- [GPS 数据处理说明](docs/GPS数据处理说明.md)
- [OBS 与结算流程分析](docs/OBS与结算分析.md)
- [三级状态验证说明](docs/三级状态验证说明.md)

## 开发者

### 固定版本

| 组件 | 配置 |
| --- | --- |
| JDK | 17；本地实际构建采用 Eclipse Temurin 17.0.13+11，CI 使用 Temurin 17 |
| Gradle Wrapper | 8.9（分发 ZIP 配置 SHA-256 校验） |
| Android Gradle Plugin | 8.7.3 |
| Kotlin / Compose 编译插件 / Serialization 插件 | 2.0.21 |
| compileSdk / targetSdk | 35 / 35 |
| minSdk | 29（Android 10） |
| Build-tools | 35.0.0 |
| SDK Command-line Tools | 云端安装版本 12.0，下载编号 11076708；CI 由 setup-android 管理 |
| Compose BOM | 2024.12.01 |
| Activity Compose | 1.9.3 |
| Lifecycle | 2.8.7 |
| DataStore Preferences | 1.1.1 |
| kotlinx-serialization-json | 1.7.3 |
| JUnit | 4.13.2 |
| AndroidX Test JUnit / Runner | 1.2.1 / 1.6.2 |

Gradle 8.9 支持 JDK 17，AGP 8.7 需要至少 JDK 17、Gradle 8.9，并支持 API 35；Compose 编译插件与 Kotlin 均固定为 2.0.21。本工程不依赖系统 Java 21。依赖源仅为 Google Maven、Maven Central 和 Gradle Plugin Portal；不使用不明镜像或关闭 TLS 校验。

### 本地开发环境

用 Android Studio 打开 `android/`，选择 JDK 17。安装 Android SDK Platform 35、Build-tools 35.0.0、Platform-tools，并接受对应 SDK 许可。Linux shell 示例：

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/Android/Sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
# 只读 HOME 的云环境需把缓存放到可写目录：
export GRADLE_USER_HOME=/workspace/.android-dev/gradle-home
export ANDROID_USER_HOME=/workspace/.android-dev/android-user
```

必要时创建不提交的 `local.properties`：`sdk.dir=/path/to/Android/Sdk`。构建和运行 APK 无需 Python、Node、Chromium、NDK、CMake、Flutter SDK、账号密钥或平台令牌；可选源码分析索引脚本使用开发机 Python 3.12。

Linux x86_64 云机器也可在 `android/` 执行 `source scripts/bootstrap-linux.sh`：它在可写的 `/workspace/.android-dev` 安装固定 JDK/SDK，校验官方下载文件，并接受本次开发所需 SDK 许可。脚本遵循现有代理，不扩大网络权限，也不关闭 TLS 验证。ADB 37.0.1 另需用户目录下 `.android` 可写，用于本地调试密钥；构建 APK 本身不需要运行 ADB。

在本次云机器中，已安装环境可用 `source /workspace/.android-dev/env.sh` 激活；该文件是机器本地代理、受信任系统证书库与工具路径配置，不应复制为其他机器的网络配置。它不关闭证书验证。未来任务复用当前隔离检出目录，不需要创建 Git worktree。

### 构建、测试和产物

```bash
cd android
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew lintDebug
# 可选：仅在专用测试模拟器或授权测试设备上运行；本次实际使用 Android 10 软件模拟器
./gradlew connectedDebugAndroidTest
# 可选：连接自己的测试设备（无需校园账号）后安装
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- APK 原始输出：`android/app/build/outputs/apk/debug/app-debug.apk`。
- 交付副本：`release/local-fitness-1.0.0-debug.apk`。
- JUnit XML：`android/app/build/test-results/testDebugUnitTest/`。
- JUnit HTML：`android/app/build/reports/tests/testDebugUnitTest/index.html`。
- Lint：`android/app/build/reports/lint-results-debug.html`。
- Android 仪器测试：`android/app/build/outputs/androidTest-results/connected/debug/` 和 `app/build/reports/androidTests/connected/debug/`。
- 本次留存证据、测试状态和限制见 [`docs/测试报告.md`](docs/测试报告.md)。

当前自动化测试覆盖球面距离/配速、GPS 毫秒解析与 JSON/CSV 往返、真实环回 HTTP 的上传/拒绝/超时/断连、202 受理、200 业务失败、畸形响应、独立入账查询、三级状态隔离及异常任务筛选。仅使用本地 Mock Server 和匿名测试数据，不调用校园接口。源码改变后可运行 `python3 scripts/analyze-python.py` 重新生成模块静态索引（开发机 Python 3.12；不属于 APK 运行依赖）。

可用于离线试导入的文件位于 [`docs/examples/`](docs/examples/)。软件模拟器专用界面冒烟脚本为 `python3 scripts/emulator-smoke.py --serial emulator-5554 --reset`；`--reset` 会清空该模拟器内本应用的数据，脚本拒绝真实设备。请只在专用测试模拟器使用。

自动构建工作流为 [`../.github/workflows/android.yml`](../.github/workflows/android.yml)，执行 JUnit、APK 构建、Lint、签名校验并上传 APK 和测试报告。提交/PR 涉及工程时触发，也可手动触发；尚未远端执行时不能将配置文件当作 CI 成功证据。

### 数据格式和实现边界

JSON 顶层为 `{"schemaVersion":1,"records":[...]}`。记录字段为 `id,title,source,startedAt,durationSeconds,distanceMeters,points`；轨迹点字段为 `latitude,longitude,elapsedSeconds`。日期为 Unix 毫秒，时长为整数秒，距离为米；轨迹点可选 elapsedMillis 保留亚秒精度。

CSV 固定表头如下，每个轨迹点占一行，无轨迹记录的末四列为空：

```csv
id,title,source,startedAt,durationSeconds,distanceMeters,pointIndex,latitude,longitude,elapsedSeconds
```

支持引号、逗号、换行及 UTF-8 BOM。导出潜在电子表格公式文本时添加可逆零宽前缀。来源仅允许 `MANUAL`、`SIMULATED`、`IMPORTED`。单次导入最大 8 MiB；最多 500 条记录、每条 5000 点、总计 50000 点。整批校验与 DataStore 事务保证失败不产生部分导入。

本地示意图没有地图底图或测绘投影；不认证导入内容真实性，不对接第三方成绩系统。当前 debug 包不适用于应用商店正式发行，正式发布需另行配置长期签名密钥、版本升级和设备验收。

迁移范围和未移植模块见 [`docs/移植分析.md`](docs/移植分析.md)。
