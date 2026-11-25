# 科文智嘉 · CampusArNav

一个面向江苏师范大学科文学院（潘安湖校区）的 Android 校园助手，包名 `com.campus.arnav`。
纯 Kotlin 手写，**不依赖 ARCore**：AR 箭头用相机内参 + `ROTATION_VECTOR` 姿态矩阵自己做针孔投影，
箭头是躺在地面平面上的真实几何体（箭杆 3 m、箭头 1.8×2.3 m，视点高度 1.35 m），随手机俯仰自然透视变形。

## 四个页面

| 栏 | 页面 | 能力 |
|---|---|---|
| ① 课程表 | 默认页 | 7 天 × 12 节自绘网格、周次切换、文本/JSON 批量导入、导出分享、手动增删改、单周停课 |
| ② 智嘉 | 通知采集 | 粘贴文字 / 选截图 / 贴网址，抽取活动与课程做完冲突三选一后入库 |
| ③ 导航 | 校园导航 | 22+ 楼栋搜索、距南门直线距离与步行时间、**AR 步行导航**、高德 URI 跳转、现场取点校准 |
| ④ 对话 | 智嘉 · 对话 | 离线知识库 BM25（106 篇）+ 大模型回答，支持 Agent 动作（加课/录活动/设闹钟/导航）、图片识图、会话持久化 |

## 技术要点

- **AR 叠加**：CameraX 预览 + 透明 Canvas；方位角 `bearing = atan2(Δ东, Δ北)`，相对角 `rel = normalize(bearing − heading)`；
  地面箭头逐顶点做 ENU → 设备 → 相机坐标变换后投影，`f = (w/2)/tan(hFov/2)`，视场外的箭头自动让位给屏幕边缘角度提示。
- **罗盘误差处理**：`ROTATION_VECTOR` 优先，退化时加速度计+磁力计兜底；对旋转**向量**而非角度做低通（避免 359°→0° 跳变），40 ms 节流。
- **FOV 实测**：从 `SENSOR_INFO_PHYSICAL_SIZE`（`SizeF`）与 `LENS_INFO_AVAILABLE_FOCAL_LENGTHS`（`FloatArray`）算竖屏水平视场，本机实测 58.2°。
- **离线优先**：Gradle 依赖全部本地缓存构建（`--offline`），知识库打包进 `assets/kb.json`，无网也能查。
- **Key 安全**：DeepSeek / 通义 VL 的 Key 只存本机 SharedPreferences，关闭 `allowBackup`，内部页面不导出。

## 构建

```powershell
$env:JAVA_HOME='E:\Android\AndroidStudio\android-studio\jbr'   # 换成你的 JDK 17/21
$env:ANDROID_HOME='E:\Android\Sdk'
$env:GRADLE_USER_HOME='E:\Android\gradle-home'
.\gradlew.bat assembleDebug --offline
```

产物：`app\build\outputs\apk\debug\app-debug.apk`

## 更多

功能细节、版本变更记录、首次使用配置方法见 [README-智嘉.md](README-智嘉.md)。