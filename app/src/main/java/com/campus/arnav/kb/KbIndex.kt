package com.campus.arnav.kb

import android.content.Context
import org.json.JSONArray
import java.util.Locale
import kotlin.math.ln

/**
 * 离线知识库检索：把 assets/kb.json 切成块，用轻量 BM25 打分。
 * 中文按 bigram 切分，英文/数字按单词切分；不依赖向量模型，先保证能跑。
 */
object KbIndex {

    data class Chunk(
        val title: String,
        val path: String,
        val text: String,
        /** 笔记 frontmatter 里的别名，只用于命中加权，不参与正文匹配。 */
        val aliases: String = ""
    )

    data class Hit(
        val title: String,
        val path: String,
        val text: String,
        val score: Double
    )

    private var chunks: List<Chunk> = emptyList()
    private var termFreqs: List<Map<String, Int>> = emptyList()
    private var lengths: IntArray = IntArray(0)
    private var df: Map<String, Int> = emptyMap()
    private var avgLen: Double = 1.0
    private var loaded = false

    private val latinRe = Regex("[a-z0-9_+.]+")
    private val cjkRe = Regex("[\\u4e00-\\u9fff]+")

    @Synchronized
    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        val arr = JSONArray(ctx.assets.open("kb.json").bufferedReader().use { it.readText() })
        val cs = ArrayList<Chunk>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val title = o.optString("title")
            val path = o.optString("path")
            val content = o.optString("content")
            val aliases = o.optJSONArray("aliases")?.let { a ->
                (0 until a.length()).joinToString(" ") { a.optString(it) }
            } ?: ""
            cs.addAll(split(title, path, content, aliases))
        }
        // 预计算每块的词频与长度：检索时不再重复分词，明显降低每次提问的 CPU 开销。
        val tfs = ArrayList<Map<String, Int>>(cs.size)
        val lens = IntArray(cs.size)
        val d = HashMap<String, Int>()
        for ((i, c) in cs.withIndex()) {
            val toks = tokenize(c.title + "\n" + c.text)
            val tf = HashMap<String, Int>(toks.size * 2)
            for (t in toks) tf[t] = (tf[t] ?: 0) + 1
            tfs.add(tf)
            lens[i] = toks.size
            for (t in tf.keys) d[t] = (d[t] ?: 0) + 1
        }
        chunks = cs
        termFreqs = tfs
        lengths = lens
        df = d
        avgLen = if (lens.isEmpty()) 1.0 else lens.sum().toDouble() / lens.size
        loaded = true
    }

    fun size(ctx: Context): Int {
        ensureLoaded(ctx)
        return chunks.size
    }

    /**
     * 同义词 / 口语词扩展：键是学生常说的词，值是知识库里真实出现的说法。
     * 只在查询侧扩展（正文不动），扩展词权重打折，避免冲淡原词。
     */
    private val synonyms: Map<String, List<String>> = linkedMapOf(
        "挂科" to listOf("不及格", "补考", "重修", "学业预警"),
        "补考" to listOf("不及格", "重修", "缓考"),
        "重修" to listOf("不及格", "补考"),
        "绩点" to listOf("平均学分绩点", "学位", "成绩"),
        "门禁" to listOf("熄灯", "宿舍", "公寓", "社区"),
        "熄灯" to listOf("门禁", "宿舍"),
        "宿舍" to listOf("公寓", "学生社区", "住宿"),
        "报修" to listOf("维修", "后勤"),
        "请假" to listOf("请销假", "事假", "病假"),
        "处分" to listOf("违纪", "警告", "记过", "留校察看", "开除学籍"),
        "申诉" to listOf("复查", "申委会", "听证"),
        "奖学金" to listOf("评优", "评奖", "单项奖", "国家奖学金", "国家励志奖学金", "院长奖学金"),
        "助学金" to listOf("资助", "困难认定", "建档", "国家助学金"),
        "贫困生" to listOf("家庭经济困难", "困难认定", "建档", "助学金"),
        "勤工助学" to listOf("勤工", "助学岗位"),
        "贷款" to listOf("助学贷款", "生源地"),
        "医保" to listOf("医疗保险", "报销", "就医"),
        "转专业" to listOf("转专业", "大类", "分流"),
        "校园卡" to listOf("一卡通", "补办", "挂失"),
        "学生证" to listOf("补办", "挂失", "证卡"),
        "图书馆" to listOf("借阅", "书刊", "电子阅读本", "借书"),
        "借书" to listOf("借阅", "书刊"),
        "食堂" to listOf("餐饮", "后勤", "饮食"),
        "快递" to listOf("菜鸟", "驿站", "物流"),
        "辅导员" to listOf("班主任", "学生工作部"),
        "军训" to listOf("国防教育", "军事训练"),
        "心理" to listOf("心理健康", "心理咨询", "心理普查"),
        "考研" to listOf("升学", "研究生"),
        "就业" to listOf("招聘", "三方协议", "就业指导", "双选会"),
        "毕业" to listOf("学士学位", "毕业论文", "优秀毕业生"),
        "学位" to listOf("学士学位", "学位授予", "绩点"),
        "论文" to listOf("毕业论文", "学位论文", "查重", "学术规范"),
        "作弊" to listOf("违规", "考试违规", "违纪", "考场"),
        "学费" to listOf("收费", "缴费", "住宿费"),
        "报到" to listOf("新生", "入学", "注册"),
        "社团" to listOf("学生社团", "校园文化", "第二课堂"),
        "综测" to listOf("综合素质测评", "测评", "加分"),
        "第二课堂" to listOf("学分", "活动学分"),
        "评优" to listOf("奖学金", "三好学生", "优秀学生干部", "先进班集体"),
        "征兵" to listOf("入伍", "兵役", "国防"),
        "校历" to listOf("作息", "开学", "放假", "考试周"),
        "作息" to listOf("上课时间", "课表", "校历"),
        "课表" to listOf("作息", "上课时间", "教学作息"),
        "竞赛" to listOf("学科竞赛", "大赛", "报名时间"),
        "证书" to listOf("等级认定", "竞赛")
    )

    /** 查询词权重：原始词 1.0，同义词扩展词 0.45。 */
    private fun queryWeights(query: String): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        for (t in tokenize(query).distinct()) out[t] = 1.0
        val qLower = query.lowercase(Locale.ROOT)
        for ((k, vs) in synonyms) {
            if (!qLower.contains(k)) continue
            for (v in vs) {
                for (t in tokenize(v)) if (!out.containsKey(t)) out[t] = 0.45
            }
        }
        return out
    }

    /** 标题或别名里是否出现任一查询词（用于给"口语词命中正确笔记"加分）。 */
    private fun titleHit(c: Chunk, terms: Collection<String>): Boolean {
        val t = (c.title + " " + c.aliases).lowercase(Locale.ROOT)
        for (term in terms) {
            if (term.length >= 2 && t.contains(term)) return true
        }
        return false
    }

    /** 扩展词正好是这篇笔记标题/别名里的主题词（如"挂科"→标题含"学业预警"）。 */
    private fun topicHit(c: Chunk, query: String): Boolean {
        val qLower = query.lowercase(Locale.ROOT)
        val t = (c.title + " " + c.aliases).lowercase(Locale.ROOT)
        for ((k, vs) in synonyms) {
            if (!qLower.contains(k)) continue
            for (v in vs) if (v.length >= 3 && t.contains(v)) return true
        }
        return false
    }

    fun search(ctx: Context, query: String, topK: Int = 5): List<Hit> {
        ensureLoaded(ctx)
        if (chunks.isEmpty()) return emptyList()
        val weights = queryWeights(query)
        if (weights.isEmpty()) return emptyList()
        val qLower = query.lowercase(Locale.ROOT)
        val n = chunks.size
        val scored = ArrayList<Pair<Int, Double>>()
        for (i in termFreqs.indices) {
            val tf = termFreqs[i]
            val len = lengths[i]
            var score = 0.0
            for ((term, w) in weights) {
                val f = tf[term] ?: continue
                val dfi = df[term] ?: continue
                val idf = ln(1.0 + (n - dfi + 0.5) / (dfi + 0.5))
                val k1 = 1.3
                val b = 0.75
                score += w * idf * (f * (k1 + 1)) / (f + k1 * (1 - b + b * len / avgLen))
            }
            if (score > 0) {
                val c = chunks[i]
                val titleLower = c.title.lowercase(Locale.ROOT)
                if (titleLower.contains(qLower) ||
                    (c.aliases.isNotEmpty() && c.aliases.lowercase(Locale.ROOT).contains(qLower))
                ) {
                    score *= 1.35
                } else if (topicHit(c, query)) {
                    score *= 1.20
                } else if (titleHit(c, weights.keys)) {
                    score *= 1.12
                }
                scored.add(i to score)
            }
        }
        return scored.sortedByDescending { it.second }.take(topK).map {
            val c = chunks[it.first]
            Hit(c.title, c.path, c.text, it.second)
        }
    }

    /** 给聊天用的上下文片段。 */
    fun contextFor(ctx: Context, query: String, topK: Int = 4): String {
        val hits = search(ctx, query, topK)
        if (hits.isEmpty()) return ""
        val sb = StringBuilder()
        hits.forEachIndexed { i, h ->
            sb.append("[资料 ${i + 1}] ").append(h.title).append("（").append(h.path).append("）\n")
            sb.append(h.text.take(900)).append("\n\n")
        }
        return sb.toString()
    }

    private fun split(title: String, path: String, content: String, aliases: String): List<Chunk> {
        val out = ArrayList<Chunk>()
        var buf = StringBuilder()
        fun flush() {
            if (buf.length > 40) out.add(Chunk(title, path, buf.toString().trim(), aliases))
            buf = StringBuilder()
        }
        for (line in content.split("\n")) {
            if (buf.length + line.length > 700 && buf.isNotEmpty()) flush()
            buf.append(line).append("\n")
        }
        flush()
        return out
    }

    private fun tokenize(s: String): List<String> {
        val out = ArrayList<String>()
        val lower = s.lowercase(Locale.ROOT)
        // 英文/数字
        latinRe.findAll(lower).forEach {
            val w = it.value
            if (w.length >= 2) out.add(w)
        }
        // 中文 bigram
        for (m in cjkRe.findAll(lower)) {
            val run = m.value
            if (run.length == 1) {
                out.add(run)
            } else {
                for (i in 0..run.length - 2) out.add(run.substring(i, i + 2))
            }
        }
        return out
    }

    /** 知识库篇数（按 path 去重），个人页统计用。 */
    fun docCount(ctx: Context): Int {
        ensureLoaded(ctx)
        return chunks.map { it.path }.distinct().size
    }

    /** 检索质量自检：返回命中数。 */
    fun selfCheck(ctx: Context): String {
        ensureLoaded(ctx)
        val a = search(ctx, "图书馆", 3)
        val b = search(ctx, "宿舍门禁", 3)
        val c = search(ctx, "挂科", 3)
        val docs = chunks.map { it.path }.distinct().size
        return "知识库 $docs 篇 / ${chunks.size} 块；图书馆 ${a.size} 命中；宿舍门禁 ${b.size} 命中；挂科 ${c.size} 命中"
    }
}
