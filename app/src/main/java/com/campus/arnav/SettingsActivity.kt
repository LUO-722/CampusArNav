package com.campus.arnav

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import com.campus.arnav.data.ChatMsg
import com.campus.arnav.data.ChatStore
import com.campus.arnav.data.ScheduleLogic
import com.campus.arnav.data.SettingsStore
import com.campus.arnav.kb.KbIndex
import com.campus.arnav.net.LlmClient
import com.campus.arnav.ui.applySystemBarInsets
import com.campus.arnav.ui.button
import com.campus.arnav.ui.color
import com.campus.arnav.ui.dp
import com.campus.arnav.ui.editText
import com.campus.arnav.ui.textView

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = ScrollView(this)
        root.setBackgroundColor(color(R.color.bg_dark))
        root.isFillViewport = true
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }
        root.addView(box)
        setContentView(root)
        root.applySystemBarInsets { top, bottom, _, _ ->
            box.setPadding(dp(20), dp(20) + top, dp(20), dp(24) + bottom)
        }

        box.addView(textView(this, "设置", 26f, color(R.color.text_primary), bold = true))
        box.addView(textView(this, "API Key 只存在本机 SharedPreferences，不上传。", 12f, color(R.color.text_secondary)))

        val ds = SettingsStore.deepseek(this)
        val vl = SettingsStore.vision(this)

        box.addView(section("DeepSeek · 文本"))
        val dsBase = field(box, "接口地址", ds.base)
        val dsKey = field(box, "API Key", ds.key).apply { maskKey() }
        val dsModel = field(box, "模型名", ds.model)

        box.addView(section("通义 VL · 识图"))
        val vlBase = field(box, "接口地址", vl.base)
        val vlKey = field(box, "API Key", vl.key).apply { maskKey() }
        val vlModel = field(box, "模型名", vl.model)

        box.addView(section("学期设置"))
        val semStart = field(box, "学期开始日（yyyy-MM-dd，应为第 1 周周一）", SettingsStore.semesterStart(this))
        val semWeeks = field(box, "总教学周数", SettingsStore.semesterWeeks(this).toString())
        val semName = field(box, "学期名称", SettingsStore.semesterName(this))

        val visionSwitch = CheckBox(this).apply {
            text = "聊天优先用通义 VL 模型（可发图识图）"
            isChecked = SettingsStore.chatUseVision(this@SettingsActivity)
            setTextColor(color(R.color.text_primary))
            buttonTintList = android.content.res.ColorStateList.valueOf(color(R.color.accent))
        }
        box.addView(visionSwitch)

        val status = textView(this, "", 12f, color(R.color.text_secondary))
        val save = button(this, "保存设置", color(R.color.accent), Color.parseColor("#0E1116")).apply {
            setOnClickListener {
                SettingsStore.saveDeepSeek(this@SettingsActivity, dsBase.text.toString(), dsKey.text.toString(), dsModel.text.toString())
                SettingsStore.saveVision(this@SettingsActivity, vlBase.text.toString(), vlKey.text.toString(), vlModel.text.toString())
                SettingsStore.setChatUseVision(this@SettingsActivity, visionSwitch.isChecked)
                val startText = semStart.text.toString().trim()
                val weeksInt = semWeeks.text.toString().trim().toIntOrNull()
                if (ScheduleLogic.parseDate(startText) == null) {
                    status.text = "已保存 Key；学期设置未保存：开始日应为 yyyy-MM-dd。"
                } else {
                    SettingsStore.saveSemester(
                        this@SettingsActivity,
                        startText,
                        (weeksInt ?: ScheduleLogic.DEFAULT_TOTAL_WEEKS).coerceIn(1, 30),
                        semName.text.toString().trim().ifBlank { ScheduleLogic.DEFAULT_SEMESTER_NAME }
                    )
                    SettingsStore.applySemester(this@SettingsActivity)
                    status.text = "已保存。"
                }
                setResult(RESULT_OK)
            }
        }
        box.addView(save, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(12) })

        val test = button(this, "测试 DeepSeek 连接", color(R.color.bg_card), color(R.color.text_primary)).apply {
            setOnClickListener {
                status.text = "测试中…"
                SettingsStore.saveDeepSeek(
                    this@SettingsActivity,
                    dsBase.text.toString(), dsKey.text.toString(), dsModel.text.toString()
                )
                val p = SettingsStore.deepseek(this@SettingsActivity)
                Thread {
                    try {
                        val r = LlmClient.chat(p, listOf(ChatMsg("user", "只回复两个字：正常")))
                        runOnUiThread { status.text = "连接成功：${r.take(60)}" }
                    } catch (e: Exception) {
                        runOnUiThread { status.text = "连接失败：${e.message}" }
                    }
                }.start()
            }
        }
        box.addView(test, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(8) })

        val testVl = button(this, "测试通义 VL 连接", color(R.color.bg_card), color(R.color.text_primary)).apply {
            setOnClickListener {
                status.text = "测试中…"
                SettingsStore.saveVision(
                    this@SettingsActivity,
                    vlBase.text.toString(), vlKey.text.toString(), vlModel.text.toString()
                )
                val p = SettingsStore.vision(this@SettingsActivity)
                Thread {
                    try {
                        val r = LlmClient.chat(p, listOf(ChatMsg("user", "只回复两个字：正常")))
                        runOnUiThread { status.text = "VL 连接成功：${r.take(60)}" }
                    } catch (e: Exception) {
                        runOnUiThread { status.text = "VL 连接失败：${e.message}" }
                    }
                }.start()
            }
        }
        box.addView(testVl, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(8) })

        val check = button(this, "检查离线知识库", color(R.color.bg_card), color(R.color.text_primary)).apply {
            setOnClickListener {
                status.text = "检查中…"
                Thread {
                    try {
                        val s = KbIndex.selfCheck(this@SettingsActivity)
                        runOnUiThread { status.text = s }
                    } catch (e: Exception) {
                        runOnUiThread { status.text = "知识库读取失败：${e.message}" }
                    }
                }.start()
            }
        }
        box.addView(check, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(8) })

        val selfTest = button(this, "运行离线自检", color(R.color.bg_card), color(R.color.text_primary)).apply {
            setOnClickListener {
                status.text = "自检中…"
                Thread {
                    try {
                        val report = com.campus.arnav.debug.SelfCheck.format(this@SettingsActivity)
                        runOnUiThread {
                            status.text = report.lineSequence().firstOrNull() ?: "自检完成"
                            androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                                .setTitle("离线自检结果")
                                .setMessage(report)
                                .setPositiveButton("知道了", null)
                                .show()
                        }
                    } catch (e: Exception) {
                        runOnUiThread { status.text = "自检失败：${e.message}" }
                    }
                }.start()
            }
        }
        box.addView(selfTest, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(8) })

        val clearChat = button(this, "清空对话记录", color(R.color.bg_card), color(R.color.text_secondary)).apply {
            setOnClickListener {
                ChatStore.clear(this@SettingsActivity)
                status.text = "对话记录已清空。"
                setResult(RESULT_OK)
            }
        }
        box.addView(clearChat, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(8) })
        box.addView(status)

        box.addView(textView(this, "说明", 14f, color(R.color.text_primary), bold = true).apply { setPadding(0, dp(20), 0, dp(6)) })
        box.addView(textView(this,
            "· DeepSeek 填 https://api.deepseek.com，模型 deepseek-chat。\n" +
                "· 通义 VL 填 https://dashscope.aliyuncs.com/compatible-mode/v1，模型 qwen-vl-max-latest。\n" +
                "· 识图需要通义 Key；纯文字问答只要 DeepSeek。\n" +
                "· 高德导航走 uri.amap.com，不需要 Key；手机装高德会直接调起。",
            12f, color(R.color.text_secondary)
        ))
    }

    private fun section(title: String) = textView(this, title, 15f, color(R.color.text_primary), bold = true).apply {
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun field(parent: LinearLayout, label: String, value: String): EditText {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        wrap.addView(textView(this, label, 12f, color(R.color.text_secondary)))
        val e = editText(this, label, android.text.InputType.TYPE_CLASS_TEXT, value)
        wrap.addView(e)
        parent.addView(wrap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return e
    }

    private fun EditText.maskKey() {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        transformationMethod = PasswordTransformationMethod.getInstance()
    }
}
