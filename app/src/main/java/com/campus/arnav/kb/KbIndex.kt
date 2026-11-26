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
        val text: String
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
            cs.addAll(split(title, path, content))
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

    fun search(ctx: Context, query: String, topK: Int = 5): List<Hit> {
        ensureLoaded(ctx)
        val q = tokenize(query).distinct()
        if (q.isEmpty() || chunks.isEmpty()) return emptyList()
        val n = chunks.size
        val scored = ArrayList<Pair<Int, Double>>()
        for (i in termFreqs.indices) {
            val tf = termFreqs[i]
            val len = lengths[i]
            var score = 0.0
            for (term in q) {
                val f = tf[term] ?: continue
                val dfi = df[term] ?: continue
                val idf = ln(1.0 + (n - dfi + 0.5) / (dfi + 0.5))
                val k1 = 1.3
                val b = 0.75
                score += idf * (f * (k1 + 1)) / (f + k1 * (1 - b + b * len / avgLen))
            }
            if (score > 0) {
                // 标题命中加权
                if (chunks[i].title.lowercase(Locale.ROOT).contains(query.lowercase(Locale.ROOT))) score *= 1.35
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

    private fun split(title: String, path: String, content: String): List<Chunk> {
        val out = ArrayList<Chunk>()
        var buf = StringBuilder()
        fun flush() {
            if (buf.length > 40) out.add(Chunk(title, path, buf.toString().trim()))
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
        val docs = chunks.map { it.path }.distinct().size
        return "知识库 $docs 篇 / ${chunks.size} 块；图书馆 ${a.size} 命中；宿舍门禁 ${b.size} 命中"
    }
}
