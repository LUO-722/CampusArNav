# 科文智嘉 · CampusArNav

一个面向江苏师范大学科文学院（潘安湖校区）的 Android 校园助手，包名 `com.campus.arnav`。
纯 Kotlin 手写，**不依赖 ARCore**：AR 箭头用相机内参 + `ROTATION_VECTOR` 姿态矩阵自己做针孔投影，
箭头是躺在地面平面上的真实几何体（箭杆 3 m、箭头 1.8×2.3 m，视点高度 1.35 m），随手机俯仰自然透视变形。

## 五个页面

| 栏 | 页面 | 能力 |
|---|---|---|
| ① 课程表 | 默认页 | 7 天 × 12 节自绘网格、周次切换、文本/JSON 批量导入、导出分享、手动增删改、单周停课、活动直接画进网格 |
| ② 智嘉 | 通知采集 | 粘贴文字 / 选截图 / 贴网址，云端看图或本机 OCR 识别，抽成活动草稿，与课程冲突三选一后入库 |
| ③ 导航 | 校园导航 | **27 个目的地**搜索、距南门直线距离与沿路步行时间、**AR 步行导航**、卫星实景底图 + 745 节点真实道路 A* 算路、系统 URI 拉起本机地图 App（不依赖地图 SDK）、现场取点校准 |
| ④ 对话 | 智嘉 · 对话 | 离线知识库 BM25（108 篇）+ 内置云端模型回答、联网搜索实时信息、Agent 动作（加课/录活动/设闹钟/导航）、图片识图、会话持久化 |
| ⑤ 我的 | 个人页面 | 教务同步学籍卡、学期/周次、课程与考试/成绩统计、教务数据卡、设置与诊断入口（登录教务是可选的第六个入口，不再是启动页） |

## 技术要点

- **AR 叠加**：CameraX 预览 + 透明 Canvas；方位角 `bearing = atan2(Δ东, Δ北)`，相对角 `rel = normalize(bearing − heading)`；
  地面箭头逐顶点做 ENU → 设备 → 相机坐标变换后投影，`f = (w/2)/tan(hFov/2)`，视场外的箭头自动让位给屏幕边缘角度提示。
- **罗盘误差处理**：`ROTATION_VECTOR` 优先，退化时加速度计+磁力计兜底；对旋转**向量**而非角度做低通（避免 359°→0° 跳变），40 ms 节流。
- **FOV 实测**：从 `SENSOR_INFO_PHYSICAL_SIZE`（`SizeF`）与 `LENS_INFO_AVAILABLE_FOCAL_LENGTHS`（`FloatArray`）算竖屏水平视场，本机实测 58.2°。
- **离线优先**：Gradle 依赖全部本地缓存构建（`--offline`），知识库打包进 `assets/kb.json`，无网也能查；地图底图与路网都在 APK 内。
- **坐标统一 GCJ-02**：手机 GPS 给 WGS-84，进入 App 后统一转 GCJ-02，与卫星底图、楼栋坐标、地图 URI 同一口径（`core/CoordTransform.kt`）。
- **Key 安全**：内置通道的 Key 只在 `local.properties`（已 `.gitignore`），构建期以 `buildConfigField` 注入；本轮已移除第三方地图 SDK 与 `amap.key`，源码与仓库里搜不到任何 Key。
- **签名稳定**：0.21.0 起使用固定 release keystore（`keystore.properties` / `zhijia-release.jks` 均不入库），同一签名可持续覆盖升级。

## 构建

```powershell
$env:JAVA_HOME='E:\Android\AndroidStudio\android-studio\jbr'   # 换成你的 JDK 17/21
$env:ANDROID_HOME='E:\Android\Sdk'
$env:GRADLE_USER_HOME='E:\Android\gradle-home'
.\gradlew.bat assembleDebug --offline
```

产物：`app\build\outputs\apk\debug\app-debug.apk`

## 更多

功能细节、版本变更记录、首次使用与数据限制见 [README-智嘉.md](README-智嘉.md)；
本版改动与升级须知见 [发布说明-0.21.0.md](发布说明-0.21.0.md)。