# 安装包交付目录

构建产物名：`local-fitness-1.0.0-debug.apk`，对应 SHA-256 文件为 `local-fitness-1.0.0-debug.apk.sha256`。

只在真实构建成功后复制 APK 到本目录。若文件未出现，请查看 `../android/docs/测试报告.md` 的实际构建结果，不要把本说明当作构建成功证明。

这是调试签名的可安装测试包，不是应用商店正式发行包。GitHub Actions 构建的包可在工作流的 `local-fitness-debug-apk` Artifact 下载。不同机器的默认调试签名可能不同，无法保证覆盖安装；更换签名前请导出数据，避免卸载导致数据丢失。

完整 Android Studio 工程压缩包：`local-fitness-android-source.zip`（包含 `android/`、工作流与 LICENSE，不包含 SDK、Gradle 缓存、Python 运行环境或重复的 APK）。解压后用 Android Studio 打开 `android/`。原 Python 项目完整保留在 Git 仓库中。
