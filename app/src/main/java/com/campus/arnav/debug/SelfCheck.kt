package com.campus.arnav.debug

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.campus.arnav.agent.AgentEngine
import com.campus.arnav.agent.NoticeExtractor
import com.campus.arnav.agent.OfflineExtractor
import com.campus.arnav.core.CampusProjection
import com.campus.arnav.core.CoordTransform
import com.campus.arnav.core.GeoUtils
import com.campus.arnav.data.AppDb
import com.campus.arnav.data.AuthStore
import com.campus.arnav.data.ChatMsg
import com.campus.arnav.data.ChatStore
import com.campus.arnav.data.CourseStore
import com.campus.arnav.data.Activity
import com.campus.arnav.data.ScheduleLogic
import com.campus.arnav.data.SettingsStore
import com.campus.arnav.kb.KbIndex
import com.campus.arnav.net.JwxtClient
import java.io.File
import org.json.JSONObject

/**
 * 离线自检：不联网、不需要测试框架，直接验证课表解析、周次/作息计算、
 * 活动时间归一化和知识库/数据库可用性。结果展示在「设置 → 运行离线自检」。
 */
object SelfCheck {

    /** 一条自检结果；[skipped] 表示环境未就绪（如标定文件还没生成），不计入失败。 */
    data class Check(val name: String, val ok: Boolean, val detail: String, val skipped: Boolean = false)

    fun run(ctx: Context): List<Check> {
        val out = ArrayList<Check>()
        fun check(name: String, block: () -> Boolean) {
            out.add(
                try {
                    val ok = block()
                    Check(name, ok, if (ok) "通过" else "断言未通过")
                } catch (skip: Skip) {
                    Check(name, true, "跳过：" + skip.why, skipped = true)
                } catch (fail: Fail) {
                    Check(name, false, fail.why)
                } catch (e: Exception) {
                    Check(name, false, e.message ?: e.javaClass.simpleName)
                }
            )
        }

        check("日期解析") {
            val d = ScheduleLogic.parseDate("2026-09-07")
            d != null && ScheduleLogic.formatDate(d) == "2026-09-07"
        }
        check("非法日期拒绝") {
            ScheduleLogic.parseDate("2026-13-01") == null && ScheduleLogic.parseDate("abc") == null
        }
        check("周次与星期") {
            val start = ScheduleLogic.semesterStartText()
            ScheduleLogic.dateOf(1, 1) == start &&
                ScheduleLogic.weekOf(start) == 1 &&
                ScheduleLogic.weekOf(ScheduleLogic.dateOf(2, 1)) == 2 &&
                ScheduleLogic.weekdayOf(ScheduleLogic.dateOf(2, 7)) == 7
        }
        check("周次规则") {
            ScheduleLogic.weekMatches("1-16", 10) &&
                !ScheduleLogic.weekMatches("1-8", 9) &&
                ScheduleLogic.weekMatches("1-16单", 3) &&
                !ScheduleLogic.weekMatches("1-16单", 4) &&
                ScheduleLogic.weekMatches("1-8,10-16", 12) &&
                !ScheduleLogic.weekMatches("1-8,10-16", 9)
        }
        check("作息节次") {
            ScheduleLogic.periodTime(1, "2026-10-10") == "08:15-08:55" &&
                ScheduleLogic.periodTime(6, "2026-10-10") == "13:50-14:30" &&
                ScheduleLogic.periodTime(12, "2026-10-10") == "20:00-20:40" &&
                // 科文作息全年一套：5 月与 10 月同一节次时间一致
                ScheduleLogic.periodTime(1, "2026-05-10") == "08:15-08:55" &&
                ScheduleLogic.periodTime(6, "2026-05-10") == "13:50-14:30"
        }
        check("节次定位") {
            ScheduleLogic.periodForTime("08:15", "2026-10-10") == 1 &&
                ScheduleLogic.periodForTime("09:30", "2026-10-10") == 2 &&
                ScheduleLogic.periodForTime("10:30", "2026-10-10") == 3 &&
                ScheduleLogic.periodForTime("10:40", "2026-10-10") == 4 &&
                ScheduleLogic.periodForTime("23:00", "2026-10-10") == 12
        }
        check("课程行·竖线") {
            val p = CourseStore.parseCourseLine("高等数学|周三|1|2|1-16|格物楼101")
            p != null && p.weekday == 3 && p.start == 1 && p.end == 2 &&
                p.weeks == "1-16" && p.location == "格物楼101"
        }
        check("课程行·教务导出") {
            val p = CourseStore.parseCourseLine("大学物理\t周二\t3-4节\t1-16周\t博学楼305")
            p != null && p.weekday == 2 && p.start == 3 && p.end == 4 &&
                p.weeks == "1-16" && p.location == "博学楼305"
        }
        check("课程行·合并写法") {
            val p = CourseStore.parseCourseLine("大学英语 周一第5-6节 1-16周 明志楼")
            p != null && p.weekday == 1 && p.start == 5 && p.end == 6 &&
                p.weeks == "1-16" && p.location == "明志楼"
        }
        check("课程行·非法输入") {
            CourseStore.parseCourseLine("") == null &&
                CourseStore.parseCourseLine("只有课名") == null
        }
        check("周次边界") {
            ScheduleLogic.weekMatches("1-16", 1) &&
                ScheduleLogic.weekMatches("1-16", 16) &&
                !ScheduleLogic.weekMatches("1-16", 0) &&
                !ScheduleLogic.weekMatches("1-16", 17) &&
                ScheduleLogic.weekOf(ScheduleLogic.dateOf(ScheduleLogic.totalWeeks(), 7)) == ScheduleLogic.totalWeeks()
        }
        check("冲突判断") {
            val c = com.campus.arnav.data.Course(
                id = 99L, name = "自检课程", teacher = "", location = "",
                weekday = 3, startPeriod = 3, endPeriod = 4,
                weeks = "1-16", color = 0, source = "selfcheck"
            )
            val d = ScheduleLogic.dateOf(1, 3)
            val hit = ScheduleLogic.conflicts(listOf(c), emptySet(), d, "10:00", "10:20")
            val miss = ScheduleLogic.conflicts(listOf(c), emptySet(), d, "12:00", "12:30")
            val skipped = ScheduleLogic.conflicts(listOf(c), setOf(99L), d, "10:00", "10:20")
            hit.size == 1 && miss.isEmpty() && skipped.isEmpty()
        }
        check("时间归一") {
            NoticeExtractor.normalizeTime("下午3点") == "15:00" &&
                NoticeExtractor.normalizeTime("晚上7点半") == "19:30" &&
                NoticeExtractor.normalizeTime("中午12:30") == "12:30" &&
                NoticeExtractor.normalizeTime("凌晨1点") == "01:00" &&
                NoticeExtractor.normalizeTime("第3点要求") == "第3点要求"
        }
        check("日期归一") {
            NoticeExtractor.normalizeDate("2026年9月25日") == "2026-09-25" &&
                NoticeExtractor.normalizeDate("2026/9/25") == "2026-09-25" &&
                NoticeExtractor.normalizeDate("2026-13-01") == "2026-13-01"
        }
        check("时间合法性") {
            ScheduleLogic.validClock("08:00") && ScheduleLogic.validClock("23:59") &&
                !ScheduleLogic.validClock("24:00") && !ScheduleLogic.validClock("8点") &&
                !ScheduleLogic.validClock("08:60")
        }
        check("知识库可读") {
            val s = KbIndex.selfCheck(ctx)
            s.contains("知识库") && s.contains("篇")
        }
        check("数据库可用") {
            AppDb.get(ctx).readableDatabase != null
        }
        check("动作确认文案") {
            val a = JSONObject()
                .put("type", "add_activity")
                .put("title", "讲座")
                .put("date", "2026-10-01")
                .put("start_time", "19:00")
            AgentEngine.describe(a).contains("录入活动「讲座」")
        }
        check("备份 JSON") {
            val j = CourseStore.backupJson(
                emptyList(),
                listOf(Activity(title = "讲座", date = "2026-10-01", startTime = "19:00", endTime = "20:00"))
            )
            val acts = j.optJSONArray("activities")
            j.optInt("version") == 2 &&
                j.optJSONArray("courses")?.length() == 0 &&
                acts?.length() == 1 &&
                acts.optJSONObject(0)?.optString("title") == "讲座"
        }
        check("外网时段") {
            // 外网开放 09:00—18:00：窗口内不提示，窗口外只提示、不拦请求
            AuthStore.wanBlockedHour(8) && !AuthStore.wanBlockedHour(9) &&
                !AuthStore.wanBlockedHour(13) && !AuthStore.wanBlockedHour(17) &&
                AuthStore.wanBlockedHour(18) && AuthStore.wanBlockedHour(0) &&
                AuthStore.wanBlockedHour(23)
        }
        check("教务登录编码") {
            // 正方 encodeInp：base64(学号) + "%%%" + base64(密码)；"2026010101" / "abc123"
            val enc = JwxtClient.encodeLogin("2026010101", "abc123")
            JwxtClient.b64("abc") == "YWJj" &&
                JwxtClient.b64("").isEmpty() &&
                enc == "MjAyNjAxMDEwMQ==%%%YWJjMTIz" &&
                enc.split("%%%").size == 2 &&
                !enc.contains("\n")
        }
        check("教务课表解析") {
            // 强智教务真实结构：table#timetable + td 里 div.kbcontent，多门课用「------」分隔
            val list = JwxtClient.parseTimetable(SelfCheckSample.timetable())
            val math = list.firstOrNull { it.name.startsWith("高等数学") && it.weeks == "6-9" }
            val english = list.firstOrNull { it.name.startsWith("大学英语") }
            list.size == 3 &&
                math?.weekday == 1 && math.startPeriod == 3 && math.endPeriod == 5 &&
                math.location == "致广楼-303" &&
                english?.weekday == 2 && english.startPeriod == 6 && english.endPeriod == 7 &&
                list.all { it.source == "jwxt" }
        }
        check("教务原文保留") {
            val c = JwxtClient.parseTimetable(SelfCheckSample.timetable())
                .firstOrNull { it.name.startsWith("高等数学") }
            c != null && c.raw.contains("高等数学ⅠA") && c.raw.contains("潘安湖致广楼-303") &&
                c.weekText.contains("周") && c.periodText.contains("节") &&
                c.raw.contains("通知单编号")
        }
        check("考试解析") {
            val e = JwxtClient.parseExams(SelfCheckSample.exam())
            e.size == 1 && e[0].course.startsWith("高等数学") &&
                e[0].time.contains("2027-01-08") && e[0].location.contains("致广") &&
                e[0].seat == "12" && e[0].raw.contains("致广楼-303")
        }
        check("成绩解析") {
            val g = JwxtClient.parseGrades(SelfCheckSample.grade())
            g.size == 1 && g[0].term.contains("2026") && g[0].course.startsWith("高等数学") &&
                g[0].credit == "5" && g[0].score == "88" && g[0].gpa == "3.8"
        }
        check("学生信息解析") {
            val html = "<td>姓名：张三</td><td>学号：2026010101</td><td>学院：电气与智能制造学院</td>"
            val p = JwxtClient.parseProfile(html)
            p.name == "张三" && p.sid == "2026010101" && p.college.contains("电气")
        }
        check("活动冲突三选一语义") {
            com.campus.arnav.ui.ActivityRecord.KEEP_OLD == 0 &&
                com.campus.arnav.ui.ActivityRecord.NEW_ONLY == 1 &&
                com.campus.arnav.ui.ActivityRecord.BOTH == 2
        }
        check("看图 JSON 解析") {
            val one = NoticeExtractor.parse(
                """{"is_event":true,"kind":"群聊截图","items":[{"title":"防艾主题活动","date":"2026-12-01",
                "start_time":"10:30","end_time":"12:30","location":"思齐广场","note":"可获加分",
                "evidence":"12月1日(周二)10:30-12:30"}]}"""
            )
            val none = NoticeExtractor.parse("""{"is_event":false,"items":[]}""")
            one.size == 1 &&
                one[0].title == "防艾主题活动" &&
                one[0].date == "2026-12-01" &&
                one[0].startTime == "10:30" &&
                one[0].evidence.contains("10:30") &&
                none.isEmpty()
        }
        check("活动文字解析") {
            // 真实通知（三条，群聊里常见的一行一条）走本机规则路径：不配任何 API Key
            // 也必须能拆出活动。0.11.0 曾把文字提取指向 deepseek 直连通道（无内置 Key，
            // 必然失败，智嘉页「提取不了文字」），此条防回归。
            val text = """
                通知一：关于举办校园歌手大赛初赛的通知 11月25日19:00 大学生活动中心
                通知二：专题讲座《全国大学生市场调查与分析大赛》11月26日下午15:30-16:30博学楼102教室，本次活动1个PU分，欢迎同学们参加。
                通知三：防艾主题班会 11月27日10:00 思齐广场
            """.trimIndent()
            val items = OfflineExtractor.extract(text, "2026-11-26")
            val hit = items.firstOrNull { it.date == "2026-11-26" }
            items.isNotEmpty() && hit != null &&
                hit.startTime == "15:30" && hit.endTime == "16:30" &&
                hit.location.contains("博学楼")
        }
        check("内置通道就绪") {
            // 内置百炼 Key 在构建时从 local.properties 编进 BuildConfig：
            // 用户不填任何 Key，chatProvider 与 aiApiReady 都应为 true。
            val chat = SettingsStore.chatProvider(ctx)
            chat.ready() && SettingsStore.aiApiReady(ctx) && chat.model.isNotBlank()
        }
        check("旧通道已废弃") {
            // deepseek 直连通道不再内置 Key（0.11.0 起）：只确认调用不抛异常。
            // ready() 为 false 属正常状态，不作为失败条件。
            val legacy = SettingsStore.deepseek(ctx)
            legacy.model.isNotBlank() && legacy.base.isNotBlank()
        }
        check("底图标定可读") {
            // 导航底图换成官方图后，楼点/路线全靠 assets/campus_calib.json 里的单应矩阵投影。
            // 标定还没落盘时记为「跳过」（不算失败），落盘后自动生效；结构坏了才判失败。
            val imgs = parseCalib(calibText(ctx) ?: throw Skip("$CALIB_ASSET 还没生成（等标定落盘后自动生效）"))
            if (imgs.isEmpty()) throw Skip("$CALIB_ASSET 里没有 images 条目（标定未完成）")
            if (!CampusProjection.load(ctx)) {
                throw Fail("$CALIB_ASSET 存在，但 CampusProjection.load(ctx) 返回 false（JSON 解析失败？）")
            }
            val kinds = CampusProjection.kinds()
            if (!kinds.contains(ORTHO_KIND)) {
                throw Fail("kinds()=$kinds，里面没有 $ORTHO_KIND（卫星底图缺标定）")
            }
            val ortho = imgs.firstOrNull { it.name == ORTHO_KIND }
                ?: throw Fail("标定里有 $kinds，但缺 $ORTHO_KIND 这一张")
            val size = CampusProjection.size(ORTHO_KIND)
            if (size != null && (size.first != ortho.w || size.second != ortho.h)) {
                throw Fail("size($ORTHO_KIND)=${size.first}×${size.second}，和 calib 里写的 ${ortho.w}×${ortho.h} 不一致")
            }
            // calib 里写的底图文件得真的在 res 里，否则地图页只会画空白
            for (img in imgs) {
                val draw = img.file.substringBeforeLast('.').ifEmpty { img.name }
                if (ctx.resources.getIdentifier(draw, "drawable", ctx.packageName) == 0) {
                    throw Fail("标定引用的底图 $draw 不在 res 里（${img.file} 找不到对应图片）")
                }
            }
            true
        }
        check("投影残差达标") {
            val imgs = parseCalib(calibText(ctx) ?: throw Skip("$CALIB_ASSET 还没生成（等标定落盘后自动生效）"))
                .filter { it.control.isNotEmpty() }
            if (imgs.isEmpty()) throw Skip("$CALIB_ASSET 里没有 control 控制点（标定未完成）")
            if (!CampusProjection.load(ctx)) throw Fail("$CALIB_ASSET 存在，但 CampusProjection.load(ctx) 返回 false")
            // 判据不是「绝对像素小」，而是「矩阵实测残差 ≈ 标定文件自报 residualPx」：
            // 27 个 repo 经纬度本身是估算值（与官图钉点差 30–50m），绝对残差天然有几十 px，
            // 拿 8px 卡必然假失败。这条要抓的是「矩阵 / 控制点 / 自报值三者不同步」——
            // 有人只改了一处，实测就会明显偏离自报值，界面上的楼点随即整体偏掉。
            for (img in imgs) {
                var worst = 0.0
                var worstName = ""
                for (c in img.control) {
                    val p = CampusProjection.project(img.name, c.lat, c.lon)
                        ?: throw Fail("${img.name}：控制点「${c.name}」投影返回 null（经纬度可能不在本图范围）")
                    val d = Math.hypot((p.first - c.px).toDouble(), (p.second - c.py).toDouble())
                    if (d > worst) {
                        worst = d
                        worstName = c.name
                    }
                }
                // 图幅 10% 的硬顶：再大就说明矩阵整体错（不是坐标精度问题）
                val absCap = maxOf(img.w, img.h) * 0.10
                val declared = img.residualPx?.takeIf { !it.isNaN() && it > 0.0 }
                    ?: throw Fail(
                        "${img.name} 有 ${img.control.size} 个控制点却没有自报 residualPx（标定文件缺字段，" +
                            "改标定的人得把拟合残差一并写进来）"
                    )
                if (declared > absCap) {
                    throw Fail(
                        "${img.name} 自报残差 ${num(declared)}px 已超图幅 10%（${num(absCap)}px）：" +
                            "标定本身就不可信，先查控制点像素是不是抄错了"
                    )
                }
                // 余量：3px 起步 + 自报值的 15%（拟合口径差异 / 浮点舍入）
                val cap = maxOf(declared + 3.0, declared * 1.15)
                if (worst > cap) {
                    throw Fail(
                        "${img.name} 实测残差最差「$worstName」${num(worst)}px，" +
                            "超出允许的 ${num(cap)}px（自报 ${num(declared)}px + 3px/15% 余量）：" +
                            "矩阵或控制点有一处被改过，楼点会整体偏掉"
                    )
                }
                }
            true
        }
        check("四角落外推合理") {
            if (CampusProjection.residualPx(ORTHO_KIND) == null) {
                throw Skip("$ORTHO_KIND 还没标定（matrix 为空，CampusProjection 正在走外接框线性兜底）")
            }
            val size = CampusProjection.size(ORTHO_KIND)
                ?: throw Skip("$ORTHO_KIND 还没标定（CampusProjection.size 返回 null）")
            val graph = parseGraph(graphText(ctx) ?: throw Skip("$GRAPH_ASSET 读不出来"))
            val dests = graph.destinations.mapNotNull { graph.nodes[it] }
            if (dests.size < 20) throw Fail("campus_graph.json 里目的地只剩 ${dests.size} 个（应 ≥ 20），先查路网文件")
            // 目的地经纬度外接框的四个角：控制点都在校内，四角必然要「外推」，最容易暴露矩阵错
            val north = dests.maxByOrNull { it.lat }!!
            val south = dests.minByOrNull { it.lat }!!
            val east = dests.maxByOrNull { it.lon }!!
            val west = dests.minByOrNull { it.lon }!!
            val w = size.first.toFloat()
            val h = size.second.toFloat()
            // 控制点来自 repo 的估算坐标（实测可差 30–50m），外推四角会跟着偏几十 px，
            // 所以容差放到 5%（约 64/87px）：仍能抓住「尺度错 15% 以上」这种矩阵级错误。
            val tolX = w * 0.05f
            val tolY = h * 0.05f
            val corners = listOf(
                Triple("西北角", north.lat, west.lon),
                Triple("东北角", north.lat, east.lon),
                Triple("西南角", south.lat, west.lon),
                Triple("东南角", south.lat, east.lon)
            )
            for ((label, lat, lon) in corners) {
                val p = CampusProjection.project(ORTHO_KIND, lat, lon)
                    ?: throw Fail("$label 外推到图外（project 返回 null）：纬度 $lat、经度 $lon")
                if (p.first < -tolX || p.first > w + tolX || p.second < -tolY || p.second > h + tolY) {
                    throw Fail(
                        "$label 投影落在底图外：(${num(p.first.toDouble())}, ${num(p.second.toDouble())})，" +
                            "底图只有 ${w.toInt()}×${h.toInt()}（最北 ${north.name}、最南 ${south.name}、最东 ${east.name}、最西 ${west.name}）"
                    )
                }
            }
            true
        }
        check("路网仍在路上") {
            if (CampusProjection.residualPx(ORTHO_KIND) == null) {
                throw Skip("$ORTHO_KIND 还没标定（matrix 为空，线性兜底不保证 px/米 一致）")
            }
            val size = CampusProjection.size(ORTHO_KIND)
                ?: throw Skip("$ORTHO_KIND 还没标定（CampusProjection.size 返回 null）")
            val graph = parseGraph(graphText(ctx) ?: throw Skip("$GRAPH_ASSET 读不出来"))
            if (graph.edges.isEmpty()) throw Skip("campus_graph.json 里没有 edges")
            val w = size.first.toFloat()
            val h = size.second.toFloat()
            val tolX = w * 0.05f
            val tolY = h * 0.05f
            // 等距抽 10 条边，别只抽前 10 条（那一片恰好都在中轴上）
            val picks = minOf(10, graph.edges.size)
            val ratios = ArrayList<Double>()
            val rows = ArrayList<Pair<String, Double>>()
            for (k in 0 until picks) {
                val (a, b) = graph.edges[k * graph.edges.size / picks]
                val na = graph.nodes[a] ?: continue
                val nb = graph.nodes[b] ?: continue
                val meters = GeoUtils.distanceMeters(na.lat, na.lon, nb.lat, nb.lon)
                if (meters < 5.0) continue
                val pa = CampusProjection.project(ORTHO_KIND, na.lat, na.lon)
                    ?: throw Fail("${na.name} 投影返回 null（标定没覆盖到这个位置）")
                val pb = CampusProjection.project(ORTHO_KIND, nb.lat, nb.lon)
                    ?: throw Fail("${nb.name} 投影返回 null（标定没覆盖到这个位置）")
                if (pa.first < -tolX || pa.first > w + tolX || pa.second < -tolY || pa.second > h + tolY ||
                    pb.first < -tolX || pb.first > w + tolX || pb.second < -tolY || pb.second > h + tolY
                ) {
                    throw Fail("${na.name}→${nb.name} 这段路的端点被投影到底图外（标定原点或比例偏了）")
                }
                val dots = Math.hypot((pa.first - pb.first).toDouble(), (pa.second - pb.second).toDouble())
                if (dots <= 0.0) continue
                ratios += dots / meters
                rows += "${na.name}→${nb.name}" to dots / meters
            }
            if (ratios.size < 5) throw Fail("可用的路网边只有 ${ratios.size} 条（投影回 null 太多，标定没覆盖全图）")
            val median = ratios.sorted()[ratios.size / 2]
            var worst = rows.first()
            for (r in rows) if (Math.abs(r.second - median) > Math.abs(worst.second - median)) worst = r
            val dev = Math.abs(worst.second - median) / median
            if (dev > 0.50) {
                throw Fail(
                    "${worst.first} 的像素/米比 ${num(worst.second)} 偏离全校中位 ${num(median)} 达 ${num(dev * 100)}%（上限 50%：" +
                        "底图比例或旋转标定错了）"
                )
            }
            true
        }
        check("坐标系转换") {
            // 校园锚点：WGS-84 的手机 GPS 值 vs 它在 GCJ-02 底图上的位置（两者差 ~562m，方向 282°）。
            val wLat = 34.345479
            val wLon = 117.374960
            val g = CoordTransform.wgs2gcj(wLat, wLon)
            val gLat = 34.344399
            val gLon = 117.380937
            val shift = GeoUtils.distanceMeters(wLat, wLon, g.first, g.second)
            val anchorErr = GeoUtils.distanceMeters(g.first, g.second, gLat, gLon)
            val back = CoordTransform.gcj2wgs(g.first, g.second)
            val backErr = GeoUtils.distanceMeters(wLat, wLon, back.first, back.second)
            if (shift < 550.0 || shift > 575.0) {
                throw Fail("WGS→GCJ 锚点位移 ${num(shift)}m，应为 561.8m 左右（偏移算法/椭球参数被改坏了）")
            }
            if (anchorErr > 2.0) {
                throw Fail(
                    "WGS→GCJ 得到 (${num(g.first)}, ${num(g.second)})，与基准点差 ${num(anchorErr)}m（上限 2m：" +
                        "算法被改过或经纬顺序反了）"
                )
            }
            if (backErr > 1.0) {
                throw Fail("GCJ→WGS 往返差 ${num(backErr * 100)}cm（上限 1m：反解迭代次数或符号错了，取点会漂）")
            }
            if (CoordTransform.outOfChina(0.0, 0.0) != true || CoordTransform.outOfChina(34.34, 117.37)) {
                throw Fail("境外判定错：境外不偏移（差 560m），校区内被当成境外则蓝点直接跑出校园")
            }
            true
        }
        check("GPS 入口已转换") {
            // 不依赖真 GPS：把「手机给的一对 WGS 值」喂进入口转换，看偏移是否仍是校园标定值。
            // 入口链（UserLocator → ArNavActivity → 业务层）只需调用一次 wgs2gcj；
            // 经纬写反 / 忘了转 / 转两次，这里都会跑偏。
            val wLat = 34.345479
            val wLon = 117.374960
            val gcj = CoordTransform.wgs2gcj(wLat, wLon)
            val back = CoordTransform.gcj2wgs(gcj.first, gcj.second)
            val shift = GeoUtils.distanceMeters(wLat, wLon, gcj.first, gcj.second)
            // 「把 GCJ 当 WGS 用」时相对真实位置偏 282°：这就是蓝点跑到校外东南侧的那个方向。
            val dir = GeoUtils.bearingDeg(gcj.first, gcj.second, back.first, back.second)
            if (Math.abs(shift - 561.8) > 5.0) {
                throw Fail("入口转换位移 ${num(shift)}m，不是校园标定值 561.8m±5m（wgs2gcj 没被调用，或经纬参数顺序反了）")
            }
            if (Math.abs(GeoUtils.normalize180(dir - 282.0)) > 3.0) {
                throw Fail("偏移方向 ${num(dir)}°，应为 282°±3°（反向或沿子午线说明经纬写反/只转了一维）")
            }
            true
        }
        check("会话增删查") {
            // 用 cacheDir 里的临时库跑真实的迁移与 ChatStore 实现，绝不碰用户真实聊天库。
            val f = File(ctx.cacheDir, "selfcheck_chat_a.db")
            SQLiteDatabase.deleteDatabase(f)   // 连 -journal/-wal 一起清掉，避免上次自检中断留下的半截库
            val d = SQLiteDatabase.openOrCreateDatabase(f, null)
            try {
                // 1) 造一个 v2 形状的老库（chats 没有 sid 列），跑 v3 迁移
                d.execSQL(
                    "CREATE TABLE chats(_id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT NOT NULL, " +
                        "content TEXT NOT NULL, ts INTEGER NOT NULL)"
                )
                d.execSQL("INSERT INTO chats(role,content,ts) VALUES('user','老消息一',5000)")
                d.execSQL("INSERT INTO chats(role,content,ts) VALUES('assistant','老回答二',7000)")
                AppDb.migrateChatV3(d)
                val legacyRows = d.rawQuery("SELECT COUNT(*) FROM chats WHERE sid='legacy'", null).use {
                    if (it.moveToFirst()) it.getInt(0) else 0
                }
                if (legacyRows != 2) {
                    throw Fail("v3 迁移没把老消息挂到 legacy 会话（应 2 条，实际 $legacyRows 条：升级后历史对话会丢）")
                }
                val legacy = ChatStore.sessionList(d).firstOrNull { it.id == ChatStore.LEGACY_SID }
                    ?: throw Fail("v3 迁移没建出「历史记录」会话（聊天记录页会看不到旧对话）")
                if (legacy.title != "历史记录" || legacy.createdAt != 5000L || legacy.updatedAt != 7000L) {
                    throw Fail(
                        "「历史记录」会话不对：标题「${legacy.title}」时间 ${legacy.createdAt}-${legacy.updatedAt}" +
                            "（应 历史记录 / 5000-7000：会话时间要取老消息的最早与最晚）"
                    )
                }
                // 2) 两个会话各写一条：验证列表排序、消息不串、删干净
                val s1 = ChatStore.createSession(d, 8000L)
                ChatStore.addTo(d, s1, ChatMsg("user", "第一句"), 8100L)
                val s2 = ChatStore.createSession(d, 9000L)
                ChatStore.addTo(d, s2, ChatMsg("user", "第二句"), 9100L)
                val list = ChatStore.sessionList(d)
                if (list.firstOrNull()?.id != s2) {
                    throw Fail("会话列表没按更新时间倒序（最近说话的「第二句」不在最前：列表会乱序）")
                }
                val m1 = ChatStore.messagesIn(d, s1)
                if (m1.size != 1 || m1[0].content != "第一句") {
                    throw Fail("会话消息串了：s1 里应只有 1 条「第一句」，实际 ${m1.size} 条")
                }
                if (ChatStore.messagesIn(d, s2).firstOrNull()?.content != "第二句") {
                    throw Fail("会话消息串了：s2 里读不到「第二句」（sid 过滤失效）")
                }
                ChatStore.deleteIn(d, s1)
                if (ChatStore.messagesIn(d, s1).isNotEmpty() || ChatStore.sessionList(d).any { it.id == s1 }) {
                    throw Fail("删除会话后消息或会话行还在（删不干净，聊天记录越积越多）")
                }
                true
            } finally {
                runCatching { d.close() }
                runCatching { SQLiteDatabase.deleteDatabase(f) }
            }
        }
        check("会话标题截断") {
            val f = File(ctx.cacheDir, "selfcheck_chat_b.db")
            SQLiteDatabase.deleteDatabase(f)   // 连 -journal/-wal 一起清掉，避免上次自检中断留下的半截库
            val d = SQLiteDatabase.openOrCreateDatabase(f, null)
            try {
                d.execSQL(
                    "CREATE TABLE chats(_id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT NOT NULL, " +
                        "content TEXT NOT NULL, ts INTEGER NOT NULL, sid TEXT DEFAULT '')"
                )
                AppDb.createChatSchema(d)
                val long = "请帮我看看明天下午的课表和活动安排有没有时间冲突谢谢"
                if (long.length <= ChatStore.TITLE_MAX) {
                    throw Fail("测试文本只有 ${long.length} 字，不足以验证 ${ChatStore.TITLE_MAX} 字截断")
                }
                val sid = ChatStore.createSession(d, 1000L)
                ChatStore.addTo(d, sid, ChatMsg("user", long), 1100L)
                val title = ChatStore.sessionList(d).first { it.id == sid }.title
                if (title.length != ChatStore.TITLE_MAX) {
                    throw Fail(
                        "标题长度 ${title.length}，应为 ${ChatStore.TITLE_MAX}" +
                            "（第 ${ChatStore.TITLE_MAX + 1} 个字起要截掉，否则列表行撑爆）"
                    )
                }
                if (title != long.substring(0, ChatStore.TITLE_MAX)) {
                    throw Fail("标题不是首条 user 消息的前 ${ChatStore.TITLE_MAX} 字：「$title」")
                }
                ChatStore.addTo(d, sid, ChatMsg("assistant", "后面这条长得多的回答不应该改掉会话标题"), 1200L)
                if (ChatStore.sessionList(d).first { it.id == sid }.title != title) {
                    throw Fail("assistant 消息把会话标题改了（标题只该由首条用户消息定）")
                }
                true
            } finally {
                runCatching { d.close() }
                runCatching { SQLiteDatabase.deleteDatabase(f) }
            }
        }
        return out
    }

    fun format(ctx: Context): String {
        val list = run(ctx)
        val passed = list.count { it.ok && !it.skipped }
        val skipped = list.count { it.skipped }
        val sb = StringBuilder("离线自检：$passed/${list.size} 通过")
        if (skipped > 0) sb.append("（$skipped 项跳过：标定/路网文件还没就绪）")
        sb.append('\n')
        list.forEach {
            sb.append(
                when {
                    it.skipped -> "○ "
                    it.ok -> "✓ "
                    else -> "✗ "
                }
            ).append(it.name)
            if (!it.ok || it.skipped) sb.append(" — ").append(it.detail)
            sb.append('\n')
        }
        return sb.toString().trim()
    }

    // ---- 地图标定 / 路网自检用的小工具 ----

    private const val CALIB_ASSET = "campus_calib.json"
    private const val GRAPH_ASSET = "campus_graph.json"
    private const val ORTHO_KIND = "campus_ortho"

    /** 环境未就绪（对应资产还没生成）：记为跳过，不算失败。 */
    private class Skip(val why: String) : Exception(why)

    /** 断言失败：[why] 就是给人看的中文原因，format() 原样显示。 */
    private class Fail(val why: String) : Exception(why)

    /** 一个控制点：底图上的像素 + 对应经纬度。 */
    private class Control(val name: String, val px: Double, val py: Double, val lat: Double, val lon: Double)

    /** calib 里的一张底图标定。 */
    private class CalibImage(
        val name: String,
        val file: String,
        val w: Int,
        val h: Int,
        val residualPx: Double?,
        val control: List<Control>
    )

    /** 路网节点。 */
    private class GNode(val id: String, val name: String, val lat: Double, val lon: Double)

    /** 路网：节点 + 边（只关心两端 id）+ 目的地 id。 */
    private class GraphData(
        val nodes: Map<String, GNode>,
        val edges: List<Pair<String, String>>,
        val destinations: List<String>
    )

    /** 小数只留一位，失败文案好读。 */
    private fun num(v: Double): String = (Math.round(v * 10.0) / 10.0).toString()

    private fun assetText(ctx: Context, name: String): String? = try {
        ctx.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (e: Exception) {
        null
    }

    private fun calibText(ctx: Context): String? = assetText(ctx, CALIB_ASSET)

    private fun graphText(ctx: Context): String? = assetText(ctx, GRAPH_ASSET)

    private fun parseCalib(text: String): List<CalibImage> {
        val images = try {
            JSONObject(text).optJSONArray("images")
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        val out = ArrayList<CalibImage>(images.length())
        for (i in 0 until images.length()) {
            val o = images.optJSONObject(i) ?: continue
            val control = ArrayList<Control>()
            val arr = o.optJSONArray("control")
            if (arr != null) {
                for (k in 0 until arr.length()) {
                    val c = arr.optJSONObject(k) ?: continue
                    val ctl = Control(
                        c.optString("name"),
                        c.optDouble("px", Double.NaN),
                        c.optDouble("py", Double.NaN),
                        c.optDouble("lat", Double.NaN),
                        c.optDouble("lon", Double.NaN)
                    )
                    if (ctl.px.isNaN() || ctl.py.isNaN() || ctl.lat.isNaN() || ctl.lon.isNaN()) continue
                    control += ctl
                }
            }
            // 高度字段：CampusProjection 写的是 "height"（"h" 是被 3×3 矩阵占用的旧键），
            // 这里两种都认，避免标定文件写法一变就把尺寸读成 0。
            val hVal = o.optInt("height", 0).let { if (it > 0) it else o.optInt("h", 0) }
            out += CalibImage(
                name = o.optString("name"),
                file = o.optString("file"),
                w = o.optInt("w"),
                h = hVal,
                residualPx = if (o.has("residualPx") && !o.isNull("residualPx")) {
                    o.optDouble("residualPx", Double.NaN)
                } else {
                    null
                },
                control = control
            )
        }
        return out
    }

    private fun parseGraph(text: String): GraphData {
        val doc = try {
            JSONObject(text)
        } catch (e: Exception) {
            return GraphData(emptyMap(), emptyList(), emptyList())
        }
        val nodes = LinkedHashMap<String, GNode>()
        doc.optJSONArray("nodes")?.let { raw ->
            for (i in 0 until raw.length()) {
                val o = raw.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isEmpty()) continue
                nodes[id] = GNode(
                    id,
                    o.optString("name", id),
                    o.optDouble("lat", Double.NaN),
                    o.optDouble("lon", Double.NaN)
                )
            }
        }
        val edges = ArrayList<Pair<String, String>>()
        doc.optJSONArray("edges")?.let { raw ->
            for (i in 0 until raw.length()) {
                val o = raw.optJSONObject(i) ?: continue
                val a = o.optString("from")
                val b = o.optString("to")
                if (nodes.containsKey(a) && nodes.containsKey(b)) edges += a to b
            }
        }
        val dests = ArrayList<String>()
        doc.optJSONArray("destinations")?.let { raw ->
            for (i in 0 until raw.length()) {
                val id = raw.optString(i)
                if (nodes.containsKey(id)) dests += id
            }
        }
        return GraphData(nodes, edges, dests)
    }
}
