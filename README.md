# LNReader

轻量、离线的 Android 原生 TXT 阅读应用。项目位于 `test/LNReader`，Android 8.0 及以上可安装。正式 APK 的书架、阅读页、设置和文件选择器均使用 Android 原生控件，正文使用可选择文字的 TextView。无 WebView、无在线字体、无网络权限，不依赖服务器和第三方运行时库。

## 使用

1. 安装 `artifacts/LNReader-debug.apk`。这是开发签名的测试版本。
2. 点击「导入书籍」，选择多个 TXT，或选择包含 TXT 的文件夹。
3. 可分别建书，也可将本次选择按文件名自然排序（1、2、10）合成一本书。
4. 点击书封阅读，右上角加书签，底部切换目录、书签、主题和排版。

建议在手机上建立 `Download/LNReader`，将 TXT 和封面放进去，再从应用导入这个子文件夹。Android 11 起，系统不允许将整个 Download 根目录授权给应用，但可选择其子目录。应用不会申请「所有文件访问权限」。

## 已实现

- TXT 多选、递归导入文件夹；分别建书／合并成书；UTF-8、GB18030、UTF-16LE/BE（UTF-16 需 BOM）。
- 同目录同名图片优先，其次 `cover`、`folder`、`封面`；支持 JPG、JPEG、PNG、WebP。没有图片则生成文字封面。合并书籍也尝试匹配文件夹同名图片。
- 可新建、重命名、删除分类书架；更改书名、移动书籍；列表／瀑布流、书名和正文搜索、继续阅读和百分比进度。
- 参考 `xs_local_html` 的中文层级章节识别，支持卷、部、篇、章、节、目、话、回、集，英文 Chapter/Part/Book/Volume，序言／楔子／后记等；目录缩进和搜索、章节跳转。
- 没有标题的 TXT 自动分段，过长章节继续切分，避免一次渲染全书。
- 纸白、青竹、夜读；背景色选择器及 R/G/B 数值；字号、行间距、段间距；自动选择明暗文字以保持可读性。
- Android 系统字体列表（系统公开的 `/system/fonts` 字体）及本地 TTF/OTF 导入；浏览器预览提供系统通用字体与字体文件导入。
- 应用内部文件目录保存正文副本、封面和书库 JSON；使用 AtomicFile 原子保存正文与元数据。没有源文件权限也可继续阅读已导入的副本。
- 文件夹手动同步：新增或更新文件，保留同一书籍的进度、书签和重命名；不会自动删除应用内已有书籍。
- 已授权文件夹中保存可读的 `lnreader-library.json`，含书架、设置、进度和书签。在空书库重新导入相同文件夹时尝试恢复。TXT 和图片始终由用户自行管理；应用删除书籍不会删除原文件。

## 文件管理边界

- 单独选 TXT 时，Android 不授权读取相邻图片；请将图片一并多选，或使用文件夹导入。
- 当前只记住最近一次授权的同步文件夹；其他书籍副本仍保留。
- 书架分类保存在 JSON 中，不会移动原始 TXT。外部修改 TXT 后点击「同步本地文件夹」；外部改文件名／路径会被视为新书。文本改动较大时，原字符位置附近的阅读进度可能发生偏移。
- 为避免手机内存耗尽，单本（含合并结果的原文件总大小）最大 80MB，并按本机可用堆内存降低上限；封面限制 15MB，字体限制 40MB。
- `lnreader-library.json` 是分类和进度备份，不包含正文和封面；恢复还需要原文件。未授权文件夹的单文件导入只保存在应用内部，卸载会清除。

## 构建

需要 JDK 17、Android SDK 35；在 Android Studio 打开本目录，或设置 `JAVA_HOME`、`ANDROID_HOME` 后执行：

```sh
./gradlew assembleDebug lintDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。无需安装 Node 或 npm 来构建 Android 应用。

## 本地界面预览与测试

```sh
npm install
npm run preview
# 访问 http://localhost:4173
npm test
npm run test:ui
# 已设置 JDK 17 的 JAVA_HOME 时：
npm run test:native-parser
# 已启动独立 Android 模拟器，且已设置 ANDROID_HOME 时：
npm run test:android
```

浏览器版是单独保留的交互原型，使用 IndexedDB 存储，不打入 APK，其数据与正式 Android 版独立。正式版使用 `NativeActivity.java`、`Library.java` 和 `BookParser.java`。原型 UI 测试默认使用 macOS 已安装的 Google Chrome，可用 `CHROME_PATH` 指定其他 Chromium 可执行文件。

`tests/core.test.mjs` 覆盖原型的编码、目录层级与字符边界、长文本分段、封面优先级、自然排序及现有《蛊真人》参考文本。`tests/ui.mjs` 覆盖原型手机视口下导入、合并、去重、阅读、目录、书签、进度、主题、排版、书架与重开保存。正式版解析器另有 `tests/BookParserTest.java`，可用 JDK 17 的 `javac` 编译，并以 `java -ea` 运行。截图位于 `artifacts/`。

`test:android` 使用 ADB 检查真实原生控件，仅操作独立模拟器并清空模拟器中的 LNReader 测试数据，不操作实体手机。已在 Android 15 / Pixel 5 模拟器验证原生目录、书签、夜读／青竹、设置和进程重启恢复；另通过系统文件选择器实测多选 TXT 与图片、递归文件夹导入、合并、封面、JSON 备份与同步保留进度。真实参考文件《蛊真人》约 23 MB，识别出 2,352 个目录节点。尚未进行实体手机测试。
