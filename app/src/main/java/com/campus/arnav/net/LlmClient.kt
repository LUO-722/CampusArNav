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
 * OpenAI 兼容的 Chat Completions 客户端（DeepSeek / 通义千问 VL 等）。
 * 只用 HttpURLConnection，不引入第三方网络库。
 */
object LlmClient {

    class LlmException(message: String) : Exception(message)

    fun chat(provider: Provider, messages: List<ChatMsg>, jsonMode: Boolean = false): String {
        if (!provider.ready()) throw LlmException("尚未配置 API Key，请到「设置」里填写。")
        val body = JSONObject().apply {
            put("model", provider.model)
            put("temperature", 0.3)
            put("stream", false)
            val arr = JSONArray()
            for (m in messages) {
                arr.put(JSONObject().put("role", m.role).put("content", m.content))
            }
            put("messages", arr)
            if (jsonMode) put("response_format", JSONObject().put("type", "json_object"))
        }
        val text = post(provider, body.toString())
        return parseContent(text)
    }

    /** 图片理解：传入 base64（不含 data: 前缀）与 mime。 */
    fun vision(provider: Provider, prompt: String, imageBase64: String, mime: String = "image/jpeg"): String {
        if (!provider.ready()) throw LlmException("尚未配置识图模型 Key，请到「设置」里填写通义 VL。")
        val content = JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", prompt))
            put(
                JSONObject().put("type", "image_url").put(
                    "image_url",
                    JSONObject().put("url", "data:$mime;base64,$imageBase64")
                )
            )
        }
        val body = JSONObject().apply {
            put("model", provider.model)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "user").put("content", content))
            })
        }
        val text = post(provider, body.toString())
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
        if (!provider.ready()) throw LlmException("识图需要先在「设置」里填写通义 VL 的 API Key。")
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
        val body = JSONObject().apply {
            put("model", provider.model)
            put("temperature", 0.3)
            put("stream", false)
            put("messages", arr)
            put("response_format", JSONObject().put("type", "json_object"))
        }
        return parseContent(post(provider, body.toString()))
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

    private fun post(provider: Provider, body: String): String {
        val base = provider.base.trimEnd('/')
        if (!base.startsWith("http://", true) && !base.startsWith("https://", true)) {
            throw LlmException("接口地址应以 http:// 或 https:// 开头，例如 https://api.deepseek.com")
        }
        val conn = try {
            URL("$base/chat/completions").openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw LlmException("接口地址格式不对：${provider.base}")
        }
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 20_000
            conn.readTimeout = 120_000
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Authorization", "Bearer ${provider.key}")
            conn.setRequestProperty("Accept", "application/json")
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = if (stream == null) "" else {
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
            }
            if (code !in 200..299) {
                throw LlmException(friendlyError(code, text))
            }
            return text
        } catch (e: java.net.SocketTimeoutException) {
            throw LlmException("请求超时：网络慢或模型响应太久，请稍后重试。")
        } catch (e: java.net.UnknownHostException) {
            throw LlmException("连不上服务器：请检查网络或接口地址（${provider.base}）。")
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

    private fun friendlyError(code: Int, body: String): String {
        val hint = when (code) {
            401, 403 -> "API Key 无效或没有权限"
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
