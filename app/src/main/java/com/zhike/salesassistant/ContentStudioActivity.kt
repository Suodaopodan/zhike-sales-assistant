package com.zhike.salesassistant

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.zhike.salesassistant.ai.ReplyClient
import com.zhike.salesassistant.core.Prefs
import com.zhike.salesassistant.core.content.ContentRecord
import com.zhike.salesassistant.core.content.ContentStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class ContentStudioActivity : AppCompatActivity() {

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var contentStore: ContentStore
    private lateinit var historyBox: LinearLayout
    private lateinit var platformSpinner: Spinner
    private lateinit var outputModeSpinner: Spinner
    private lateinit var contentTypeSpinner: Spinner
    private lateinit var audienceEdit: EditText
    private lateinit var topicEdit: EditText
    private lateinit var factsEdit: EditText
    private lateinit var resultView: TextView
    private var currentRecord: ContentRecord? = null
    private val accent = Color.parseColor("#3A7AFE")
    private val ink = Color.parseColor("#111827")
    private val sub = Color.parseColor("#6B7280")
    private val danger = Color.parseColor("#DC2626")

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        contentStore = ContentStore(this)
        window.decorView.setBackgroundColor(Color.parseColor("#F2F3F5"))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
            padForSystemBars()
        }
        root.addView(text("公域内容工坊", 24f, ink, true))
        root.addView(text("把真实课程资料沉淀下来，持续复用高表现的内容结构。",
            13f, sub).apply { setPadding(0, dp(6), 0, dp(14)) })

        val drafts = getSharedPreferences(DRAFT_PREFS, MODE_PRIVATE)
        platformSpinner = selector(listOf("小红书", "朋友圈"),
            drafts.getString("platform", "小红书") ?: "小红书")
        outputModeSpinner = selector(listOf("完整图文", "只要标题", "只要正文", "评论回复", "私信承接"),
            drafts.getString("output_mode", "完整图文") ?: "完整图文")
        contentTypeSpinner = selector(listOf("干货科普", "痛点共鸣", "课程介绍", "活动通知"),
            drafts.getString("content_type", "干货科普") ?: "干货科普")
        audienceEdit = edit(drafts.getString("audience", "") ?: "", "例如：初二数学基础薄弱的家长")
        topicEdit = edit(drafts.getString("topic", "") ?: "", "例如：期中后如何找到真正的失分原因")
        factsEdit = edit(drafts.getString("facts", "") ?: "",
            "只填写真实的课程特点、适用人群与服务内容").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            gravity = Gravity.TOP
        }
        resultView = text("生成结果会显示在这里", 14f, sub).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = round(dp(8), Color.WHITE)
        }

        root.addView(label("常用场景"))
        root.addView(quickScenes(audienceEdit, topicEdit))
        root.addView(label("发布平台")); root.addView(platformSpinner)
        root.addView(label("输出模式")); root.addView(outputModeSpinner)
        root.addView(label("内容类型")); root.addView(contentTypeSpinner)
        root.addView(label("目标人群")); root.addView(audienceEdit)
        root.addView(label("内容主题")); root.addView(topicEdit)
        root.addView(label("真实课程资料（会记住，下次可直接复用）")); root.addView(factsEdit)

        lateinit var generateButton: TextView
        generateButton = button("生成合规文案") {
            val prefs = Prefs(this)
            if (prefs.effectiveReplyKey().isBlank()) {
                toast("请先在 AI 与合规设置中配置回复接口密钥")
                return@button
            }
            if (audienceEdit.text.isBlank() || topicEdit.text.isBlank()) {
                toast("请填写目标人群和内容主题")
                return@button
            }
            val selectedPlatform = platformSpinner.selectedItem.toString()
            val selectedMode = outputModeSpinner.selectedItem.toString()
            val selectedType = contentTypeSpinner.selectedItem.toString()
            val selectedAudience = audienceEdit.text.toString().trim()
            val selectedTopic = topicEdit.text.toString().trim()
            val selectedFacts = factsEdit.text.toString().trim()
            resultView.text = "正在生成..."
            resultView.setTextColor(sub)
            generateButton.isEnabled = false
            generateButton.alpha = 0.6f
            saveDraft(drafts, selectedPlatform, selectedMode, selectedType,
                selectedAudience, selectedTopic, selectedFacts)
            worker.execute {
                val output = runCatching {
                    ReplyClient(prefs).generateContent(
                        selectedPlatform, selectedType, selectedAudience, selectedTopic,
                        selectedFacts, selectedMode
                    )
                }
                runOnUiThread {
                    generateButton.isEnabled = true
                    generateButton.alpha = 1f
                    resultView.setTextColor(if (output.isSuccess) ink else danger)
                    if (output.isSuccess) {
                        val value = output.getOrThrow()
                        resultView.text = value
                        currentRecord = ContentRecord(
                            id = ContentStore.newId(),
                            platform = selectedPlatform,
                            outputMode = selectedMode,
                            contentType = selectedType,
                            audience = selectedAudience,
                            topic = selectedTopic,
                            facts = selectedFacts,
                            content = value
                        )
                        contentStore.save(currentRecord!!)
                        renderHistory(historyBox, platformSpinner, outputModeSpinner, contentTypeSpinner,
                            audienceEdit, topicEdit, factsEdit, resultView)
                    } else {
                        resultView.text = "生成失败：${output.exceptionOrNull()?.message ?: "未知错误"}"
                    }
                }
            }
        }
        root.addView(generateButton)
        root.addView(resultView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        actions.addView(actionButton("复制", true) { copyResult(resultView) })
        actions.addView(actionButton("收藏", false) { toggleFavorite() })
        root.addView(actions)
        root.addView(label("二次改写"))
        val rewrites = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("精简", "更口语", "换角度").forEach { direction ->
            rewrites.addView(actionButton(direction, false) {
                rewrite(resultView, direction, platformSpinner, outputModeSpinner, contentTypeSpinner,
                    audienceEdit, topicEdit, factsEdit)
            })
        }
        root.addView(rewrites)
        root.addView(label("历史与收藏"))
        historyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(historyBox)
        renderHistory(historyBox, platformSpinner, outputModeSpinner, contentTypeSpinner,
            audienceEdit, topicEdit, factsEdit, resultView)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun rewrite(
        result: TextView,
        direction: String,
        platform: Spinner,
        outputMode: Spinner,
        contentType: Spinner,
        audience: EditText,
        topic: EditText,
        facts: EditText
    ) {
        val record = currentRecord
        val value = result.text.toString()
        if (record == null || value.startsWith("生成结果") || value.startsWith("生成失败") ||
            value.startsWith("正在")) {
            toast("请先生成一份内容")
            return
        }
        val prefs = Prefs(this)
        if (prefs.effectiveReplyKey().isBlank()) {
            toast("请先配置回复接口密钥")
            return
        }
        worker.execute {
            val output = runCatching { ReplyClient(prefs).rewriteContent(value, direction) }
            runOnUiThread {
                if (output.isSuccess) {
                    val rewritten = output.getOrThrow()
                    result.text = rewritten
                    currentRecord = record.copy(
                        id = ContentStore.newId(),
                        content = rewritten,
                        sourceId = record.id
                    )
                    contentStore.save(currentRecord!!)
                    renderHistory(historyBox, platform, outputMode, contentType, audience, topic, facts, result)
                } else {
                    toast("改写失败：${output.exceptionOrNull()?.message ?: "未知错误"}")
                }
            }
        }
    }

    private fun renderHistory(
        box: LinearLayout,
        platform: Spinner,
        outputMode: Spinner,
        contentType: Spinner,
        audience: EditText,
        topic: EditText,
        facts: EditText,
        result: TextView
    ) {
        box.removeAllViews()
        val records = contentStore.records().take(12)
        if (records.isEmpty()) {
            box.addView(text("生成后的内容会保存在这里，最多保留 80 条。", 12f, sub))
            return
        }
        records.forEach { record ->
            val row = card()
            row.addView(text(
                "${if (record.favorite) "已收藏 · " else ""}${record.platform} · ${record.outputMode} · " +
                    formatTime(record.createdAt),
                12f, if (record.favorite) accent else sub, true
            ))
            row.addView(text(record.content.replace("\n", " ").take(100), 13f, ink)
                .apply { setPadding(0, dp(5), 0, 0) })
            row.setOnClickListener {
                platform.setSelection(listOf("小红书", "朋友圈").indexOf(record.platform).coerceAtLeast(0))
                outputMode.setSelection(listOf("完整图文", "只要标题", "只要正文", "评论回复", "私信承接")
                    .indexOf(record.outputMode).coerceAtLeast(0))
                contentType.setSelection(listOf("干货科普", "痛点共鸣", "课程介绍", "活动通知")
                    .indexOf(record.contentType).coerceAtLeast(0))
                audience.setText(record.audience)
                topic.setText(record.topic)
                facts.setText(record.facts)
                result.text = record.content
                result.setTextColor(ink)
                currentRecord = record
                toast("已复用这条内容")
            }
            row.setOnLongClickListener {
                contentStore.delete(record.id)
                renderHistory(box, platform, outputMode, contentType, audience, topic, facts, result)
                toast("已删除")
                true
            }
            box.addView(row)
        }
    }

    private fun toggleFavorite() {
        val record = currentRecord
        if (record == null) {
            toast("请先生成内容")
            return
        }
        val updated = record.copy(favorite = !record.favorite)
        if (contentStore.save(updated)) {
            currentRecord = updated
            renderHistory(historyBox, platformSpinner, outputModeSpinner, contentTypeSpinner,
                audienceEdit, topicEdit, factsEdit, resultView)
            toast(if (updated.favorite) "已收藏" else "已取消收藏")
        }
    }

    private fun copyResult(result: TextView) {
        val value = result.text.toString()
        if (value.startsWith("生成结果") || value.startsWith("正在") || value.startsWith("生成失败")) {
            toast("暂无可复制内容")
            return
        }
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("zhike_content", value))
        toast("已复制")
    }

    private fun saveDraft(
        drafts: android.content.SharedPreferences,
        platform: String,
        outputMode: String,
        contentType: String,
        audience: String,
        topic: String,
        facts: String
    ) {
        drafts.edit()
            .putString("platform", platform)
            .putString("output_mode", outputMode)
            .putString("content_type", contentType)
            .putString("audience", audience)
            .putString("topic", topic)
            .putString("facts", facts)
            .apply()
    }

    private fun label(value: String) = text(value, 13f, ink, true).apply {
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun edit(value: String, hintText: String) = EditText(this).apply {
        setText(value); hint = hintText; textSize = 14f; setTextColor(ink)
        setHintTextColor(Color.parseColor("#9CA3AF"))
        background = round(dp(8), Color.WHITE)
        setPadding(dp(10), dp(10), dp(10), dp(10))
    }

    private fun selector(options: List<String>, selected: String) = Spinner(this).apply {
        adapter = ArrayAdapter(
            this@ContentStudioActivity,
            android.R.layout.simple_spinner_dropdown_item,
            options
        )
        setSelection(options.indexOf(selected).coerceAtLeast(0))
        background = round(dp(8), Color.WHITE)
        setPadding(dp(8), dp(4), dp(8), dp(4))
    }

    private fun quickScenes(audience: EditText, topic: EditText): HorizontalScrollView {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            Triple("期中复盘", "期中考试后需要定位失分原因的家长", "期中后如何定位失分原因"),
            Triple("小升初", "正在规划小升初衔接的家长", "小升初阶段应该提前准备什么"),
            Triple("学习习惯", "孩子学习效率不高的家长", "如何建立可持续的学习习惯")
        ).forEach { scene ->
            row.addView(actionButton(scene.first, false) {
                audience.setText(scene.second); topic.setText(scene.third)
            })
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun button(label: String, outlined: Boolean = false, onClick: () -> Unit) =
        actionButton(label, outlined, onClick).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        }

    private fun actionButton(label: String, primary: Boolean, onClick: () -> Unit) =
        TextView(this).apply {
            text = label; textSize = 13f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (primary) Color.WHITE else accent)
            background = round(dp(8), if (primary) accent else Color.WHITE, !primary)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(7) }
            setOnClickListener { onClick() }
        }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(dp(10), Color.WHITE)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
    }

    private fun round(radius: Int, color: Int, stroke: Boolean = false) =
        GradientDrawable().apply {
            cornerRadius = radius.toFloat(); setColor(color)
            if (stroke) setStroke(dp(1), accent)
        }

    private fun formatTime(time: Long): String =
        SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(time))

    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val DRAFT_PREFS = "content_studio_draft"
    }
}
