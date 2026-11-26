package com.campus.arnav.net

import com.campus.arnav.data.ChatMsg
import com.campus.arnav.data.Provider
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * OpenAI 兼容的 Chat Completions 客户端（DeepSeek / 通义千问 VL / 自建代理）。
 * 只用 HttpURLConnection，不引入第三方网络库。
 *
 * 通道选择由 [Provider] 决定：`proxyBase` 非空时请求发到代理
 * （`{proxyBase}/chat/completions`，App 内不需要 DeepSeek Key），否则按原直连逻辑走。
 */
object LlmClient {

    private const val NO_CHANNEL =
        "还没有可用的 AI 通道：可在「设置 → AI 服务」填自建代理，或填自己的 DeepSeek Key；" +
            "两者都不填也能用（智嘉走本机规则识别 + 离线知识库）。"

    private const val VISION_NO_CHANNEL =
        "当前不可用：没有网络，或云端识图通道暂时不通；" +
            "只看图片文字的话，选图后本机 OCR 也能用（离线、不需要网络）。"

    open class LlmException(message: String) : Exception(message)

    /** 换个模型就能成功的错误：模型不存在、限流、服务端 5xx。 */
    class RetryableLlmException(message: String) : LlmException(message)

    fun chat(
        provider: Provider,
        messages: List<ChatMsg>,
        jsonMode: Boolean = false,
        search: Boolean = false
    ): String {
        if (!provider.ready()) throw LlmException(NO_CHANNEL)
        val build: (String, Boolean) -> String = { model, web ->
            JSONObject().apply {
                put("model", model)
                put("temperature", 0.3)
                put("stream", false)
                val arr = JSONArray()
                for (m in messages) {
                    arr.put(JSONObject().put("role", m.role).put("content", m.content))
                }
                put("messages", arr)
                if (jsonMode) put("response_format", JSONObject().put("type", "json_object"))
                if (web) {
                    // 百炼服务端联网搜索：不用另外的搜索 Key，结果由模型自己引用
                    put("enable_search", true)
                    put("search_options", JSONObject().apply {
                        put("forced_search", true)
                        put("search_strategy", "standard")
                    })
                }
            }.toString()
        }
        return try {
            parseContent(post(provider) { model -> build(model, search) })
        } catch (e: LlmException) {
            // 某个模型不支持联网搜索时，退化成不搜索再问一次，别让用户看到报错
            if (!search || e is RetryableLlmException) throw e
            parseContent(post(provider) { model -> build(model, false) })
        }
    }

    /** 图片理解：传入 base64（不含 data: 前缀）与 mime。 */
    fun vision(provider: Provider, prompt: String, imageBase64: String, mime: String = "image/jpeg"): String {
        if (!provider.ready()) throw LlmException(VISION_NO_CHANNEL)
        val content = JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", prompt))
            put(
                JSONObject().put("type", "image_url").put(
                    "image_url",
                    JSONObject().put("url", "data:$mime;base64,$imageBase64")
                )
            )
        }
        val text = post(provider) { model ->
            JSONObject().apply {
                put("model", model)
                put("temperature", 0.1)
                put("stream", false)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "user").put("content", content))
                })
                // 看图抽取要的是结构化结果；qwen3-vl 支持 json_object
                put("response_format", JSONObject().put("type", "json_object"))
            }.toString()
        }
        return parseContent(text)
    }

    /**
     * 带图片的多轮对话：system 提示 + 最近历史 + 本轮文字与图片。
     * 用于聊天页的“图片提问”，返回仍是 JSON（Agent 约定）。
     */
    fun visionChat(
        provider: Provider,
        system: String,
        history: List<ChatMsg>,
        userText: String,
        imageBase64: String,
        mime: String = "image/jpeg"
    ): String {
        if (!provider.ready()) throw LlmException(VISION_NO_CHANNEL)
        val arr = JSONArray()
        arr.put(JSONObject().put("role", "system").put("content", system))
        val recent = history.takeLast(8).filter { it.role != "system" }
        var appended = false
        for (m in recent) {
            if (m.role == "user" && m.content == userText && !appended) {
                // 本轮用户文字与图片合并为一条多模态消息
                arr.put(JSONObject().put("role", "user").put("content", multimodal(userText, imageBase64, mime)))
                appended = true
            } else {
                arr.put(JSONObject().put("role", m.role).put("content", m.content))
            }
        }
        if (!appended) {
            arr.put(JSONObject().put("role", "user").put("content", multimodal(userText, imageBase64, mime)))
        }
        return parseContent(post(provider) { model ->
            JSONObject().apply {
                put("model", model)
                put("temperature", 0.3)
                put("stream", false)
                put("messages", arr)
                put("response_format", JSONObject().put("type", "json_object"))
            }.toString()
        })
    }

    private fun multimodal(text: String, imageBase64: String, mime: String): JSONArray = JSONArray().apply {
        put(JSONObject().put("type", "text").put("text", text))
        put(
            JSONObject().put("type", "image_url").put(
                "image_url",
                JSONObject().put("url", "data:$mime;base64,$imageBase64")
            )
        )
    }

    /**
     * 按模型链依次尝试：主模型不存在 / 限流 / 服务端错误时自动换下一个，
     * 全部失败才把最后一个错误抛出去。这样账号里换了模型、某个模型临时限流都能自愈。
     */
    private fun post(provider: Provider, buildBody: (String) -> String): String {
        val chain = provider.modelChain().ifEmpty { listOf(provider.model) }
        var last: LlmException? = null
        for (m in chain) {
            try {
                val text = postOnce(provider, buildBody(m))
                // 推理型模型偶尔把 token 全用在 reasoning 上，content 为空的“成功”也要换下一个
                if (parseContent(text).isBlank()) {
                    last = RetryableLlmException("模型 ${m} 返回了空内容")
                    continue
                }
                return text
            } catch (e: RetryableLlmException) {
                last = e
            }
        }
        throw last ?: LlmException(NO_CHANNEL)
    }

    private fun postOnce(provider: Provider, body: String): String {
        val base = endpointBase(provider.effectiveBase())
        if (!base.startsWith("http://", true) && !base.startsWith("https://", true)) {
            throw LlmException("云端地址异常，请检查网络后重试。")
        }
        val url = "$base/chat/completions"
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw LlmException("接口地址格式不对：${provider.effectiveBase()}")
        }
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 20_000
            conn.readTimeout = 120_000
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            // 代理可以不要令牌（例如只校验 App 内置 token 的服务端），空令牌就不发 Authorization
            val token = provider.effectiveKey().trim()
            if (token.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = if (stream == null) "" else {
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
            }
            if (code !in 200..299) {
                val msg = friendlyError(code, text)
                if (code == 404 || code == 429 || code >= 500) throw RetryableLlmException(msg)
                throw LlmException(msg)
            }
            return text
        } catch (e: java.net.SocketTimeoutException) {
            throw LlmException("请求超时：网络慢或模型响应太久，请稍后重试。")
        } catch (e: java.net.UnknownHostException) {
            throw LlmException("连不上服务器：请检查网络或接口地址（${provider.effectiveBase()}）。")
        } catch (e: javax.net.ssl.SSLException) {
            throw LlmException("HTTPS 连接失败：请检查接口地址与网络环境。")
        } catch (e: java.io.IOException) {
            val m = e.message.orEmpty()
            if (m.contains("cleartext", ignoreCase = true)) {
                throw LlmException("接口地址是 http://，Android 禁止明文请求；请改用 https:// 的接口地址。")
            }
            throw LlmException("网络请求失败：${m.take(160).ifBlank { e.javaClass.simpleName }}")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 归一化接口根地址：去首尾空白与末尾斜杠，避免拼出 `//chat/completions`；
     * 用户把 `/chat/completions` 一起粘进来时也不重复拼接。
     */
    internal fun endpointBase(raw: String): String {
        var b = raw.trim().trimEnd('/')
        while (b.endsWith("/chat/completions")) b = b.removeSuffix("/chat/completions").trimEnd('/')
        return b
    }

    private fun friendlyError(code: Int, body: String): String {
        val hint = when (code) {
            401, 403 -> "Key 或代理令牌无效 / 没有权限"
            404 -> "接口地址不对（应填到 /v1 这一级）"
            429 -> "请求太频繁或额度用完"
            in 500..599 -> "模型服务暂时不可用"
            else -> "请求失败"
        }
        val detail = body.take(240).replace(Regex("\\s+"), " ").trim()
        return "$hint（HTTP $code）" + if (detail.isNotBlank()) "：$detail" else ""
    }

    private fun parseContent(raw: String): String {
        val root = JSONObject(raw)
        root.optJSONObject("error")?.let {
            throw LlmException("模型接口报错：" + it.optString("message").take(240))
        }
        val choices = root.optJSONArray("choices")
            ?: throw LlmException("接口返回格式异常：${raw.take(200)}")
        if (choices.length() == 0) throw LlmException("接口没有返回内容")
        val msg = choices.getJSONObject(0).optJSONObject("message")
            ?: throw LlmException("接口没有 message")
        val content = msg.opt("content")
        return when (content) {
            is String -> content
            is JSONArray -> {
                // 部分多模态模型返回数组
                val sb = StringBuilder()
                for (i in 0 until content.length()) {
                    val o = content.optJSONObject(i) ?: continue
                    sb.append(o.optString("text"))
                }
                sb.toString()
            }
            else -> ""
        }
    }
}
