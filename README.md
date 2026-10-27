# LiNoRr

轻量的 Android 本地 TXT 阅读器。支持 Android 8.0 及以上，无网络权限，无广告，无账号。

## 安装

在 [Releases](https://github.com/Zachary116699/LiNoRr/releases/latest) 下载 APK 并安装。

## 功能

- 批量导入 TXT 和文件夹，可将多个文件合为一本书，导入时自动备份和去重。
- 多书架分类、搜索、排序、批量管理，支持网格和列表显示。
- 自动识别章节，可按书籍调整匹配规则；保存阅读进度、书签和分页结果。
- 支持左右翻页、滚动翻页、音量键翻页和文字选择复制。
- 调整字体、字号、行距、段距和阅读背景，支持日间与夜间模式。
- 自动生成封面，也可从相册导入封面。

## 数据

默认存放在 `Download/LiNoRr`，可在设置中更换位置。TXT 和封面保存在 `book/`，书籍记录、进度和书签保存在 `library.sqlite`，分类与偏好保存在 `userdata.json`。

导入后从备份阅读，删除外部源文件不会影响阅读。移除书籍会同时删除应用中的备份与记录，不删除外部源文件。迁移数据时，先暂停导入并退出应用，再复制整个备份文件夹，包括可能存在的 SQLite 辅助文件。

## 技术栈

Java 17、Android 原生 View、Canvas / StaticLayout、SQLite。使用 Gradle 8.9 和 Android Gradle Plugin 8.7.3 构建，compileSdk / targetSdk 为 35。无 WebView，无第三方运行时依赖。

## 构建

安装 JDK 17 和 Android SDK 35，设置 `JAVA_HOME`、`ANDROID_HOME`：

```sh
./gradlew assembleDebug
./gradlew assembleRelease
```

Debug 包可直接安装；Release 构建产物需要自行签名。不同签名不能直接覆盖安装。

测试与实现说明见 [开发文档](docs/DEVELOPMENT.md)，版本记录见 [CHANGELOG.md](CHANGELOG.md)。
