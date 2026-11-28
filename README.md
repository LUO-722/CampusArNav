# 科文智嘉 · CampusArNav

面向江苏师范大学科文学院（潘安湖校区）的 Android 校园助手，包名 `com.campus.arnav`，Kotlin 编写。课程表、通知录活动、校园导航、对话问答都在一个应用里，课程与聊天记录只存在本机，不登录也能用。不依赖 ARCore，也不依赖第三方地图 SDK。

## 五个页面

- **课程表**：7 天 × 12 节自绘网格，周视图 / 日视图；示例课表、文本与 JSON 导入、导出分享、活动直接画进网格。
- **智嘉**：通知截图 / 文字 / 网址提取活动草稿，勾选后入库；与课程冲突时三选一。
- **导航**：卫星实景底图 + 校内真实路网 A* 算路，27 个目的地，AR 步行导航，现场取点校准。
- **对话**：本机知识库优先、需要时联网检索并标注来源，可执行加课、录活动、设闹钟等动作（写操作先确认），聊天记录可回看。
- **我的**：学籍卡、学期与当前周、课表 / 考试 / 成绩统计、教务同步入口与设置。

## 构建

本机 Gradle 依赖已缓存，需要离线构建：

```powershell
$env:JAVA_HOME='E:\Android\AndroidStudio\android-studio\jbr'
$env:ANDROID_HOME='E:\Android\Sdk'
$env:GRADLE_USER_HOME='E:\Android\gradle-home'
.\gradlew.bat assembleDebug --offline
```

产物：`app\build\outputs\apk\debug\app-debug.apk`。

## 文档

- [README-智嘉.md](README-智嘉.md)：功能说明、首次使用、教务登录、导航、数据与隐私、已知限制、开发说明
- [CHANGELOG.md](CHANGELOG.md)：版本历史
- [地图对照表.md](地图对照表.md)：官方导览图编号 / 别名与 App 楼名对照