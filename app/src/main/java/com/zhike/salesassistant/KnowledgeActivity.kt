package com.zhike.salesassistant

import android.app.DatePickerDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ArrayAdapter
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.zhike.salesassistant.ai.ReplyClient
import com.zhike.salesassistant.core.Prefs
import com.zhike.salesassistant.core.kb.Contact
import com.zhike.salesassistant.core.kb.FollowUpRecord
import com.zhike.salesassistant.core.kb.KbStore
import com.zhike.salesassistant.core.kb.LeadRules
import com.zhike.salesassistant.core.kb.Note
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Knowledge base manager: the notes the assistant may quote, and the contacts
 * that tie a conversation title (across apps) to a person.
 *
 * Everything shown here lives in the app-private `filesDir/kb` directory and
 * nowhere else. Plain code-built views, same card/pill vocabulary as
 * [SettingsActivity].
 */
class KnowledgeActivity : AppCompatActivity() {

    private lateinit var store: KbStore
    private lateinit var container: LinearLayout
    private val worker = Executors.newSingleThreadExecutor()

    /** 0 = notes, 1 = contacts. */
    private var tab = 0
    private var leadFilter = LeadRules.ALL
    private var leadQuery = ""

    private val accent = Color.parseColor("#3A7AFE")
    private val ink = Color.parseColor("#111827")
    private val sub = Color.parseColor("#6B7280")
    private val pillOff = Color.parseColor("#EEF1F5")
    private val red = Color.parseColor("#DC2626")

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = KbStore.get(this)
        if (intent.getBooleanExtra(EXTRA_OPEN_LEADS, false)) tab = 1
        leadFilter = intent.getStringExtra(EXTRA_LEAD_FILTER)
            ?.takeIf { it in LeadRules.filters } ?: LeadRules.ALL
        window.decorView.setBackgroundColor(Color.parseColor("#F2F3F5"))

        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        container.padForSystemBars()   // edge-to-edge: keep the title off the status bar
        scroll.addView(container)
        setContentView(scroll)
        render()
    }

    // --------------------------------------------------------------- screens

    private fun render() {
        container.removeAllViews()
        container.addView(text("知识库与客户线索", 24f, ink, bold = true))
        container.addView(text("客户档案与跟进信息只存在本机。分析时按会话标题匹配线索、按关键词命中课程资料。",
            12f, sub).apply { setPadding(0, dp(6), 0, dp(4)) })
        container.addView(tabs())
        if (tab == 0) renderNotes() else renderContacts()
    }

    private fun tabs(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        listOf("课程资料", "客户线索").forEachIndexed { i, name ->
            val pill = TextView(this).apply {
                text = name; textSize = 13f; gravity = Gravity.CENTER
                setPadding(dp(18), dp(8), dp(18), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) }
                setTextColor(if (i == tab) Color.WHITE else sub)
                setTypeface(typeface, if (i == tab) Typeface.BOLD else Typeface.NORMAL)
                background = round(dp(9), if (i == tab) accent else pillOff)
                setOnClickListener { tab = i; render() }
            }
            row.addView(pill)
        }
        return row
    }

    // ----------------------------------------------------------------- notes

    private fun renderNotes() {
        val notes = store.notes().sortedByDescending { it.updatedAt }
        container.addView(twoButtons("新建笔记", { editNoteDialog(null) },
            "从文本导入", { importNotesDialog() }))
        if (notes.isEmpty()) {
            container.addView(emptyCard("还没有课程资料。先录入课程适用人群、服务内容、价格规则与常见异议。"))
            return
        }
        notes.forEach { container.addView(noteRow(it)) }
        container.addView(text("点条目编辑，长按删除。命中规则：任一标签或标题出现在会话标题或最近 6 条消息里。",
            11f, sub).apply { setPadding(dp(2), dp(12), 0, 0) })
    }

    private fun noteRow(n: Note): View {
        val c = card()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val head = n.title.ifBlank { "（无标题）" } + if (n.alwaysOn) "  · 常驻" else ""
        left.addView(text(head, 15f, ink, bold = true))
        left.addView(text(
            if (n.tags.isEmpty()) "无标签" else "标签：" + n.tags.joinToString("、"),
            12f, sub).apply { setPadding(0, dp(3), 0, 0) })
        left.addView(text(n.content.replace("\n", " ").take(46), 12f, sub)
            .apply { setPadding(0, dp(3), 0, 0) })
        row.addView(left)
        row.addView(smallToggle(n.enabled) {
            store.saveNote(n.copy(enabled = !n.enabled)); render()
        })
        c.addView(row)
        c.setOnClickListener { editNoteDialog(n) }
        c.setOnLongClickListener {
            confirm("删除笔记", "删除「${n.title}」？不可恢复。") {
                store.deleteNote(n.id); render()
            }
            true
        }
        return c
    }

    private fun editNoteDialog(existing: Note?) {
        val box = dialogBox()
        val titleEdit = edit(existing?.title ?: "", "标题，例如：口味忌口")
        val contentEdit = edit(existing?.content ?: "", "正文，写清楚事实本身").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4; gravity = Gravity.TOP
        }
        val tagsEdit = edit(existing?.tags?.joinToString("，") ?: "", "逗号分隔，例如：吃饭，周末")
        val alwaysRow = toggleRow("常驻（每次分析都带上）", existing?.alwaysOn ?: false)
        val enabledRow = toggleRow("启用", existing?.enabled ?: true)
        box.addView(label("标题")); box.addView(titleEdit)
        box.addView(label("正文")); box.addView(contentEdit)
        box.addView(label("标签")); box.addView(tagsEdit)
        box.addView(alwaysRow); box.addView(enabledRow)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "新建笔记" else "编辑笔记")
            .setView(wrapScroll(box))
            .setPositiveButton("保存") { _, _ ->
                val title = titleEdit.text.toString().trim()
                val content = contentEdit.text.toString().trim()
                if (title.isBlank() && content.isBlank()) {
                    toast("标题和正文不能都空着"); return@setPositiveButton
                }
                store.saveNote(Note(
                    id = existing?.id ?: KbStore.newId(),
                    title = title,
                    content = content,
                    tags = splitTags(tagsEdit.text.toString()),
                    alwaysOn = (alwaysRow.tag as? Boolean) ?: false,
                    enabled = (enabledRow.tag as? Boolean) ?: true
                ))
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun importNotesDialog() {
        val box = dialogBox()
        box.addView(text("按空行分段，每段第一行当标题，其余当正文。", 12f, sub))
        val input = edit("", "口味忌口\n不吃香菜，海鲜过敏\n\n项目代号\n内部叫小蓝").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 8; gravity = Gravity.TOP
        }
        box.addView(input)
        AlertDialog.Builder(this)
            .setTitle("从文本导入")
            .setView(wrapScroll(box))
            .setPositiveButton("导入") { _, _ ->
                val chunks = input.text.toString().split(Regex("\\r?\\n[ \\t]*\\r?\\n"))
                var n = 0
                chunks.forEach { chunk ->
                    val lines = chunk.trim().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                    if (lines.isNotEmpty()) {
                        store.saveNote(Note(
                            id = KbStore.newId(),
                            title = lines.first(),
                            content = lines.drop(1).joinToString("\n")
                        ))
                        n++
                    }
                }
                toast(if (n == 0) "没解析出内容" else "已导入 $n 条")
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun splitTags(raw: String): List<String> =
        raw.split(",", "，", "、").map { it.trim() }.filter { it.isNotEmpty() }

    // -------------------------------------------------------------- contacts

    private fun renderContacts() {
        val all = store.contacts()
        val (todayStart, tomorrowStart) = dayBounds()
        val contacts = all.filter {
            LeadRules.matches(it, leadFilter, todayStart, tomorrowStart) &&
                matchesQuery(it, leadQuery)
        }
            .sortedWith(
                compareByDescending<Contact> { LeadRules.priority(it, todayStart) }
                    .thenByDescending { it.updatedAt }
            )
        container.addView(twoButtons("新建客户线索", { editContactDialog(null) }, null, null))
        container.addView(searchRow())
        container.addView(leadFilters(all))
        if (all.isEmpty()) {
            container.addView(emptyCard(
                "还没有客户线索。也可以在聊天里长按悬浮球，把当前会话存为客户。"))
            return
        }
        if (contacts.isEmpty()) {
            container.addView(emptyCard("当前筛选下没有客户线索。"))
            return
        }
        contacts.forEach { container.addView(contactRow(it)) }
        container.addView(text("点条目编辑，长按删除。会话标题等于名字或任一别名即算命中（忽略大小写与群人数后缀）。",
            11f, sub).apply { setPadding(dp(2), dp(12), 0, 0) })
    }

    private fun leadFilters(contacts: List<Contact>): View {
        val (todayStart, tomorrowStart) = dayBounds()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        LeadRules.filters.forEach { filter ->
            val count = contacts.count { LeadRules.matches(it, filter, todayStart, tomorrowStart) }
            row.addView(TextView(this).apply {
                text = "$filter $count"
                textSize = 12.5f
                gravity = Gravity.CENTER
                setTypeface(typeface, if (filter == leadFilter) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (filter == leadFilter) Color.WHITE else sub)
                background = round(dp(8), if (filter == leadFilter) accent else pillOff)
                setPadding(dp(13), dp(8), dp(13), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(7) }
                setOnClickListener {
                    leadFilter = filter
                    render()
                }
            })
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
    }

    private fun searchRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        val input = edit(leadQuery, "搜索姓名、来源、需求或下一步").apply {
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(input)
        row.addView(TextView(this).apply {
            text = if (leadQuery.isBlank()) "搜索" else "清除"
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = round(dp(8), accent)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(8) }
            setOnClickListener {
                leadQuery = if (leadQuery.isBlank()) input.text.toString().trim() else ""
                render()
            }
        })
        return row
    }

    private fun contactRow(c0: Contact): View {
        val c = card()
        c.addView(text(c0.name.ifBlank { "（无名）" }, 15f, ink, bold = true))
        c.addView(text("${c0.intentLevel}意向 · ${c0.leadStage}", 12f,
            if (c0.intentLevel == "高") Color.parseColor("#15803D") else accent, bold = true)
            .apply { setPadding(0, dp(4), 0, 0) })
        if (c0.aliases.isNotEmpty())
            c.addView(text("别名：" + c0.aliases.joinToString("、"), 12f, sub)
                .apply { setPadding(0, dp(3), 0, 0) })
        val source = c0.source.ifBlank {
            c0.apps.joinToString("、") { appLabel(it) }
        }
        if (source.isNotBlank())
            c.addView(text("来源：$source", 12f, sub)
                .apply { setPadding(0, dp(3), 0, 0) })
        if (c0.relationship.isNotBlank())
            c.addView(text("关系：" + c0.relationship.replace("\n", " ").take(40), 12f, sub)
                .apply { setPadding(0, dp(3), 0, 0) })
        if (c0.notes.isNotBlank())
            c.addView(text("备注：" + c0.notes.replace("\n", " ").take(40), 12f, sub)
                .apply { setPadding(0, dp(3), 0, 0) })
        if (c0.nextAction.isNotBlank())
            c.addView(text("下一步：" + c0.nextAction.replace("\n", " ").take(48), 12f, ink, bold = true)
                .apply { setPadding(0, dp(5), 0, 0) })
        if (c0.nextFollowUpAt > 0)
            c.addView(text("跟进日期：" + formatDate(c0.nextFollowUpAt), 12f,
                if (LeadRules.isOverdue(c0, dayBounds().first)) red else sub, bold = true)
                .apply { setPadding(0, dp(4), 0, 0) })

        val logN = store.logSize(c0.id)
        if (LeadRules.isPending(c0)) {
            c.addView(TextView(this).apply {
                text = "记录跟进结果"
                textSize = 12.5f
                setTextColor(accent)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(10), 0, dp(2))
                setOnClickListener {
                    followUpDialog(c0)
                }
            })
        }
        c.addView(TextView(this).apply {
            text = "AI 复盘建议"
            textSize = 12.5f
            setTextColor(accent)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(2))
            setOnClickListener { leadAssist(c0) }
        })
        store.followUps(c0.id).take(3).forEach { record ->
            c.addView(text(
                "${formatDateTime(record.createdAt)}  ${record.scene}：${record.summary}",
                12f, sub
            ).apply { setPadding(0, dp(6), 0, 0) })
        }
        val clear = TextView(this).apply {
            text = "清空此人历史（$logN 条）"
            textSize = 12.5f; setTextColor(red); setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(2))
            setOnClickListener {
                if (logN == 0) { toast("本来就没有历史"); return@setOnClickListener }
                confirm("清空历史", "删掉「${c0.name}」的 $logN 条聊天历史？联系人档案保留。") {
                    store.clearLog(c0.id); render()
                }
            }
        }
        c.addView(clear)
        c.setOnClickListener { editContactDialog(c0) }
        c.setOnLongClickListener {
            confirm("删除联系人", "删除「${c0.name}」及其全部历史？不可恢复。") {
                store.deleteContact(c0.id); render()
            }
            true
        }
        return c
    }

    private fun followUpDialog(contact: Contact) {
        val box = dialogBox()
        val scene = selector(listOf("首次沟通", "需求确认", "试听回访", "价格异议", "未回复唤醒", "其他"), "其他")
        val summary = edit("", "记录客户反馈、已承诺事项和下一步").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            gravity = Gravity.TOP
        }
        box.addView(label("跟进场景")); box.addView(scene)
        box.addView(label("结果记录")); box.addView(summary)
        AlertDialog.Builder(this)
            .setTitle("记录「${contact.name}」的跟进")
            .setView(wrapScroll(box))
            .setPositiveButton("保存并完成") { _, _ ->
                val value = summary.text.toString().trim()
                if (value.isBlank()) {
                    toast("请先写下本次跟进结果")
                    return@setPositiveButton
                }
                val ok = store.addFollowUp(FollowUpRecord(
                    id = KbStore.newId(),
                    contactId = contact.id,
                    scene = scene.selectedItem.toString(),
                    summary = value
                ))
                if (ok) {
                    store.saveContact(contact.copy(nextAction = "", nextFollowUpAt = 0L))
                    toast("已记录，本次跟进已完成")
                    render()
                } else {
                    toast("保存失败，请重试")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun leadAssist(contact: Contact) {
        val prefs = Prefs(this)
        if (prefs.effectiveReplyKey().isBlank()) {
            toast("请先在 AI 与合规设置中配置回复接口密钥")
            return
        }
        val loading = AlertDialog.Builder(this)
            .setTitle("AI 复盘中")
            .setMessage("正在结合本地聊天记录分析，请稍候")
            .setNegativeButton("取消", null)
            .create()
        loading.show()
        worker.execute {
            val result = runCatching {
                ReplyClient(prefs).suggestLead(contact, store.recentLog(contact.id, 20))
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                loading.dismiss()
                result.onSuccess { suggestion -> leadSuggestionDialog(contact, suggestion) }
                    .onFailure { toast("复盘失败：${it.message ?: "未知错误"}") }
            }
        }
    }

    private fun leadSuggestionDialog(
        contact: Contact,
        suggestion: ReplyClient.LeadSuggestion
    ) {
        val box = dialogBox()
        val stage = selector(
            listOf("新线索", "已沟通", "已试听", "待成交", "已成交", "暂缓"),
            suggestion.leadStage
        )
        val intent = selector(listOf("待判断", "高", "中", "低"), suggestion.intentLevel)
        val next = edit(suggestion.nextAction, "下一步动作").apply { setSingleLine(true) }
        box.addView(text("判断依据：" + suggestion.reason, 12f, sub)
            .apply { setPadding(0, 0, 0, dp(6)) })
        box.addView(label("建议阶段")); box.addView(stage)
        box.addView(label("建议意向")); box.addView(intent)
        box.addView(label("下一步动作")); box.addView(next)
        box.addView(text("保存前可直接修改；点击保存后才会写入客户档案。", 11f, sub)
            .apply { setPadding(0, dp(10), 0, 0) })
        AlertDialog.Builder(this)
            .setTitle("确认 AI 复盘结果")
            .setView(wrapScroll(box))
            .setPositiveButton("确认并保存") { _, _ ->
                val action = next.text.toString().trim()
                store.saveContact(contact.copy(
                    leadStage = stage.selectedItem.toString(),
                    intentLevel = intent.selectedItem.toString(),
                    nextAction = action,
                    nextFollowUpAt = if (action.isBlank()) 0L else contact.nextFollowUpAt
                ))
                toast("客户档案已更新")
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun editContactDialog(existing: Contact?) {
        val box = dialogBox()
        val nameEdit = edit(existing?.name ?: "", "名字，一般就是会话标题")
        val aliasEdit = edit(existing?.aliases?.joinToString("\n") ?: "", "每行一个，例如另一个 App 里的昵称").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3; gravity = Gravity.TOP
        }
        val relEdit = edit(existing?.relationship ?: "", "例如：同事，带我做项目的组长")
        val sourceEdit = edit(existing?.source ?: "", "例如：小红书私信、微信转介绍")
        val stageEdit = selector(
            listOf("新线索", "已沟通", "已试听", "待成交", "已成交", "暂缓"),
            existing?.leadStage ?: "新线索"
        )
        val intentEdit = selector(
            listOf("待判断", "高", "中", "低"),
            existing?.intentLevel ?: "待判断"
        )
        val nextEdit = edit(existing?.nextAction ?: "", "例如：周三 19:00 回访试听反馈")
        var followUpAt = existing?.nextFollowUpAt ?: 0L
        val dateButton = TextView(this).apply {
            text = if (followUpAt > 0) formatDate(followUpAt) else "选择跟进日期"
            textSize = 14f
            setTextColor(accent)
            gravity = Gravity.CENTER_VERTICAL
            background = round(dp(8), Color.parseColor("#F3F4F6"))
            setPadding(dp(10), dp(12), dp(10), dp(12))
            setOnClickListener {
                val base = Calendar.getInstance().apply {
                    if (followUpAt > 0) timeInMillis = followUpAt
                }
                DatePickerDialog(
                    this@KnowledgeActivity,
                    { _, year, month, day ->
                        followUpAt = Calendar.getInstance().apply {
                            clear()
                            set(year, month, day)
                        }.timeInMillis
                        text = formatDate(followUpAt)
                    },
                    base.get(Calendar.YEAR),
                    base.get(Calendar.MONTH),
                    base.get(Calendar.DAY_OF_MONTH)
                ).show()
            }
        }
        val notesEdit = edit(existing?.notes ?: "", "关于这个人要记住的事").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3; gravity = Gravity.TOP
        }
        box.addView(label("名字")); box.addView(nameEdit)
        box.addView(label("别名（每行一个）")); box.addView(aliasEdit)
        box.addView(label("线索来源")); box.addView(sourceEdit)
        box.addView(label("线索阶段")); box.addView(stageEdit)
        box.addView(label("意向等级")); box.addView(intentEdit)
        box.addView(label("下一步动作")); box.addView(nextEdit)
        box.addView(label("跟进日期")); box.addView(dateButton)
        box.addView(label("客户关系与需求")); box.addView(relEdit)
        box.addView(label("备注")); box.addView(notesEdit)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "新建客户线索" else "编辑客户线索")
            .setView(wrapScroll(box))
            .setPositiveButton("保存") { _, _ ->
                val name = nameEdit.text.toString().trim()
                if (name.isBlank()) { toast("名字不能空"); return@setPositiveButton }
                store.saveContact(Contact(
                    id = existing?.id ?: KbStore.newId(),
                    name = name,
                    aliases = aliasEdit.text.toString().split("\n")
                        .map { it.trim() }.filter { it.isNotEmpty() },
                    apps = existing?.apps ?: emptyList(),
                    source = sourceEdit.text.toString().trim(),
                    leadStage = stageEdit.selectedItem.toString(),
                    intentLevel = intentEdit.selectedItem.toString(),
                    nextAction = nextEdit.text.toString().trim(),
                    nextFollowUpAt = if (nextEdit.text.isBlank()) 0L else followUpAt,
                    relationship = relEdit.text.toString().trim(),
                    notes = notesEdit.text.toString().trim(),
                    autoSummary = existing?.autoSummary ?: ""
                ))
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun appLabel(pkg: String): String = when (pkg) {
        "com.tencent.mm" -> "微信"
        "com.xingin.xhs" -> "小红书"
        "com.tencent.mobileqq" -> "QQ"
        "com.ss.android.lark" -> "飞书"
        "com.twitter.android" -> "X"
        else -> pkg
    }

    private fun matchesQuery(contact: Contact, query: String): Boolean {
        if (query.isBlank()) return true
        val haystack = listOf(
            contact.name,
            contact.aliases.joinToString(" "),
            contact.source,
            contact.relationship,
            contact.notes,
            contact.nextAction
        ).joinToString(" ").lowercase()
        return haystack.contains(query.lowercase())
    }

    private fun formatDate(time: Long): String =
        SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(time)

    private fun formatDateTime(time: Long): String =
        SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(time)

    private fun dayBounds(): Pair<Long, Long> {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = calendar.timeInMillis
        calendar.add(Calendar.DAY_OF_MONTH, 1)
        return start to calendar.timeInMillis
    }

    // ----------------------------------------------------------------- atoms

    private fun confirm(title: String, msg: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title).setMessage(msg)
            .setPositiveButton("确定") { _, _ -> onYes() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun dialogBox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(4))
    }

    private fun wrapScroll(v: View) = ScrollView(this).apply { addView(v) }

    private fun twoButtons(
        a: String, onA: () -> Unit,
        b: String?, onB: (() -> Unit)?
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        row.addView(wideBtn(a, true, onA))
        if (b != null && onB != null) row.addView(wideBtn(b, false, onB))
        return row
    }

    private fun wideBtn(labelText: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = labelText; textSize = 14f; gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (primary) Color.WHITE else accent)
        background = round(dp(11), if (primary) accent else Color.WHITE, stroke = !primary)
        setPadding(dp(12), dp(11), dp(12), dp(11))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { rightMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun smallToggle(on: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = if (on) "开" else "关"; textSize = 13f; gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (on) Color.WHITE else sub)
        background = round(dp(10), if (on) accent else Color.parseColor("#E5E7EB"))
        setPadding(dp(16), dp(6), dp(16), dp(6))
        setOnClickListener { onClick() }
    }

    private fun toggleRow(labelText: String, initial: Boolean): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(2)); tag = initial
        }
        val lab = text(labelText, 14f, ink).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = TextView(this).apply {
            text = if (initial) "开" else "关"; textSize = 13f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (initial) Color.WHITE else sub)
            background = round(dp(10), if (initial) accent else Color.parseColor("#E5E7EB"))
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
        sw.setOnClickListener {
            val now = !((row.tag as? Boolean) ?: true); row.tag = now
            sw.text = if (now) "开" else "关"
            sw.setTextColor(if (now) Color.WHITE else sub)
            sw.background = round(dp(10), if (now) accent else Color.parseColor("#E5E7EB"))
        }
        row.addView(lab); row.addView(sw)
        return row
    }

    private fun emptyCard(msg: String): View = card().apply { addView(text(msg, 13f, sub)) }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(dp(14), Color.WHITE)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) }
    }

    private fun label(t: String) = text(t, 13f, ink, bold = true).apply { setPadding(0, dp(12), 0, dp(4)) }

    private fun edit(value: String, hintText: String) = EditText(this).apply {
        setText(value); hint = hintText; textSize = 14f; setTextColor(ink)
        setHintTextColor(Color.parseColor("#9CA3AF"))
        background = round(dp(8), Color.parseColor("#F3F4F6"))
        setPadding(dp(10), dp(10), dp(10), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(2) }
    }

    private fun selector(options: List<String>, selected: String) = Spinner(this).apply {
        adapter = ArrayAdapter(
            this@KnowledgeActivity,
            android.R.layout.simple_spinner_dropdown_item,
            options
        )
        setSelection(options.indexOf(selected).coerceAtLeast(0))
        background = round(dp(8), Color.parseColor("#F3F4F6"))
        setPadding(dp(8), dp(4), dp(8), dp(4))
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun round(radius: Int, color: Int, stroke: Boolean = false) = GradientDrawable().apply {
        cornerRadius = radius.toFloat(); setColor(color)
        if (stroke) setStroke(dp(1), accent)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_OPEN_LEADS = "open_leads"
        const val EXTRA_LEAD_FILTER = "lead_filter"
    }
}
