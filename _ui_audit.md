# UI 审查报告 · Ins 风「粉 + 浅蓝」浅色皮肤

审查范围：ui/ChatTab.kt、ui/ZhijiaTab.kt、ui/ProfileTab.kt、ui/NavTab.kt、ui/Ui.kt、ui/ArrowOverlayView.kt、ui/CompassRoseView.kt、LoginActivity.kt、SettingsActivity.kt、MainShellActivity.kt、CompassTestActivity.kt、CalibrateActivity.kt、ArNavActivity.kt、MainActivity.kt（TimetableGridView.kt / TimetableTab.kt / JwxtClient.kt / colors.xml 未改动）。
参考色板：bg_dark #FFF7FB、bg_card #FFFFFF、accent #E8447A、accent_soft #FFE3EE、accent_blue #7FC4E8、blue_ink #2E7FA8、blue_soft #EAF6FD、border #F4DEE8、text_primary #3D3340、text_secondary #9C8AA3；页面底渐变 #FFF1F7→#EDF7FF。

## 一、已修复

- app/src/main/java/com/campus/arnav/ui/ArrowOverlayView.kt:174 箭头外发光仍是旧青绿 rgb(55,224,200)，在浅色叠层上偏灰绿、与新 accent 主体脱节 → 改为 accent rgb(232,68,122)，alpha 55 保持不变
- app/src/main/java/com/campus/arnav/ui/ArrowOverlayView.kt:176 第二层旧青绿 rgb(55,224,200) → 改为 accent rgb(232,68,122)，alpha 110 不变
- app/src/main/java/com/campus/arnav/ui/ArrowOverlayView.kt:263 地面柔光描边旧青绿 rgb(55,224,200) → 改为 accent rgb(232,68,122)，alpha/5 不变
- app/src/main/java/com/campus/arnav/ui/ArrowOverlayView.kt:270 地面箭头主体旧青绿 rgb(55,224,200) → 改为 accent rgb(232,68,122)，alpha*0.72 不变
- app/src/main/java/com/campus/arnav/ui/ArrowOverlayView.kt:274 箭头描边用旧薄荷白 rgb(230,255,250)，在白路面/亮草地上发绿、边界不清 → 改为纯白 rgb(255,255,255)，alpha 不变
- app/src/main/java/com/campus/arnav/LoginActivity.kt:261 外网状态提示两个分支同色（都是 #E8447A），「开放时段内」无法与「不在时段」区分 → 非阻断分支改 blue_ink #2E7FA8，告警分支保留 #E8447A
- app/src/main/java/com/campus/arnav/SettingsActivity.kt:401 徽标「已配置 / 未配置」两个分支同色（都是 #E8447A，批量替换把旧双色压平），而底色分别是 accent_soft / blue_soft → 「未配置」改 blue_ink #2E7FA8，与浅蓝底成对、状态可辨
- app/src/main/java/com/campus/arnav/ui/ChatTab.kt:41 整页铺纯色 bg_dark #FFF7FB，把 MainShell 的 #FFF1F7→#EDF7FF 渐变盖死 → 改为 Color.TRANSPARENT，透出页面渐变
- app/src/main/java/com/campus/arnav/ui/ZhijiaTab.kt:47 同上，纯色 bg_dark 盖掉渐变 → 改为 Color.TRANSPARENT
- app/src/main/java/com/campus/arnav/ui/NavTab.kt:35 同上，纯色 bg_dark 盖掉渐变 → 改为 Color.TRANSPARENT（同时 NavTab.kt:6 补 `import android.graphics.Color`，该文件原先没有这个 import）

## 二、检查通过、未改

- 深底白字（accent 实心底 + 白字，全部正确）：ChatTab.kt:100 发送按钮、ChatTab.kt:228 用户气泡、ZhijiaTab.kt:39/40 录入按钮、ZhijiaTab.kt:112 选中 chip、ProfileTab.kt:69 头像、LoginActivity.kt:210 登录按钮、SettingsActivity.kt:185 保存按钮。
- 浅底深字：LoginActivity.kt:277 未选中 chip 底 bg_dark + text_secondary；SettingsActivity.kt:449、ProfileTab.kt:85/307、MainShellActivity、CalibrateActivity 按钮均为 bg_dark/bg_card + text_primary，无白字压浅底。
- 无残留「白/近白文字画在浅底」：目标文件内 Color.WHITE、#FFFFFF、0xFFFFFFFF、0xCCFFFFFF、Color.argb(2xx,255,255,255) 仅出现在 AR 相机叠层（ArrowOverlayView.kt:58/146/306/326/355）与 accent 实心底按钮，属正确用法。
- 状态栏：MainActivity、LoginActivity、SettingsActivity、MainShellActivity、CompassTestActivity、CalibrateActivity、ArNavActivity 七个 Activity 的 onCreate 全部调用 insLightBars()（7/7），浅色皮肤下状态栏图标为深色。
- 页面背景：LoginActivity.kt:99、SettingsActivity.kt:93、MainShellActivity.kt:109 用 insBackground(this)；MainActivity / CompassTestActivity / ArNavActivity 用 XML @color/bg_dark（windowBackground 也是 bg_dark）；CalibrateActivity.kt:57 用纯色 bg_dark（为 ScrollView 铺底，与渐变端点色差极小，未改）。
- 旧暖褐/深色值（#1A120C、#2A1E15、#3A2A1E、#4A3524、#3D2C20、#3A2A00、#FFB067、#FF6F9C、#37E0C8、0xFF1E1610、0xFFC7A98C）在全部 Kotlin 源文件中已无残留（含范围外文件）。
- AR 页面（ArNavActivity / CompassTestActivity / ArrowOverlayView.kt）的半透明深色卡片 + 白字是相机画面上的合理叠层，未按浅色页面处理；其 weak-signal 黄条 argb(200,255,200,40) + text_primary、罗盘带 argb(90,0,0,0)、转向卡 argb(185,16,20,26) 均保留。
- CompassRoseView.kt：画布自绘背景 #FFF7FB，N/accent、方位文字 #3D3340 / argb(220,61,51,64)，可读；L30 `color = Color.WHITE` 只是 Paint 初始值、每次 drawText 前都被覆盖，非缺陷，未改。
- res XML（未在可改范围，仅核对）：activity_main.xml、activity_ar_nav.xml、activity_compass_test.xml、drawable/bg_card.xml、themes.xml 全部走 @color 且已浅色化（compass_test 底部面板 #CCFFFFFF 白玻璃配 text_secondary/accent 可读），无需修改。

## 三、跨文件提醒（不在我可改范围，请负责人决定）

- app/src/main/java/com/campus/arnav/ui/TimetableTab.kt:57 仍 `setBackgroundColor(ctx.color(R.color.bg_dark))` 铺纯色，与本次改为透明的其它三个 tab 不一致：切到课表 tab 时页面底会由渐变变为纯 #FFF7FB（两色差很小但可辨）。建议由负责该文件的同学同步改为 Color.TRANSPARENT；若希望全部 tab 保持纯色，则本次三处透明也可回退，二者取其一即可。
- 附：7 个 Activity 的 import 区存在批量脚本副产物——insLightBars / insBackground 反复插入（Login 26 行、Settings 29 行、MainShell 32 行、CompassTest 5 行、Calibrate 2 行、ArNav 8 行、Main 2 行，共 104 行重复 import，另 4 处未使用的 insBackground 导入）。重复 import 合法（仅 IDE 警告），已按「保留首次出现、保持原顺序」去重，并删掉确实未使用的 insBackground 导入；未改动任何业务代码。

## 合计修改 10 处

（颜色值 7 处：ArrowOverlayView 5 + LoginActivity 1 + SettingsActivity 1；页面背景 3 处：ChatTab / ZhijiaTab / NavTab。另补 import 1 行、清理重复 import 104 行。）