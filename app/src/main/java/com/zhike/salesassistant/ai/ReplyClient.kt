package com.zhike.salesassistant.ai

import com.zhike.salesassistant.core.ChatSnapshot
import com.zhike.salesassistant.core.ComplianceGuard
import com.zhike.salesassistant.core.Prefs
import com.zhike.salesassistant.core.kb.ChatContext
import com.zhike.salesassistant.core.kb.Contact
import com.zhike.salesassistant.core.kb.LogEntry
import org.json.JSONArray
import org.json.JSONObject

/**
 * The generative route: any OpenAI-compatible `/chat/completions` endpoint.
 * Drafts the 3 candidate replies, and (D stage) summarizes text. Reads
 * replyBaseUrl / replyKey / replyModel from [Prefs].
 */
class ReplyClient(private val prefs: Prefs) {

    data class LeadSuggestion(
        val leadStage: String,
        val intentLevel: String,
        val nextAction: String,
        val reason: String
    )

    /**
     * Exactly 3 varied candidate replies in Chinese.
     *
     * @param ctx D-stage knowledge context. When present its background and
     *        history are prepended to the prompt with an instruction to stay
     *        consistent with them and invent nothing beyond them.
     */
    fun draft(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext? = null): List<String> {
        val convo = snapshot.messages.takeLast(10).joinToString("\n") {
            (if (it.side == "me") "我" else "对方") + "：" + it.text
        }
        val sys = "你是中小教培团队的中文销售话术助手，服务于小红书私信和微信咨询。" +
            "目标是先准确理解需求，再基于知识库提供真实、克制、可执行的下一步，不强推、不骚扰。" +
            "只输出一个 JSON 数组，含且仅含 3 条候选回复文本：" +
            "第一条侧重承接并追问关键信息，第二条侧重匹配课程价值，第三条侧重明确下一步行动。" +
            "每条不超过 60 字，口语、自然、像真人销售顾问。" +
            "不得编造课程、价格、师资、案例或优惠；不得承诺提分、录取、考试通过或收益；" +
            "不得使用绝对化宣传、虚假稀缺、押题泄题暗示；不得索取与咨询无关的敏感信息。" +
            "在小红书场景中遵守平台规则，只有客户明确同意时才建议转到其他沟通渠道。" +
            "不要解释，不要加引号以外的内容，直接输出 JSON 数组。"
        val user = knowledgeBlock(relationship, ctx) +
            "客户关系与业务背景：$relationship\n\n最近对话：\n$convo\n\n请给出 3 条合规候选回复。"
        return parseThree(chat(sys, user, temperature = 0.8))
    }

    /** The background + history preamble; empty string when there is no context. */
    private fun knowledgeBlock(relationship: String, ctx: ChatContext?): String {
        ctx ?: return ""
        val background = ctx.background(relationship)
        val history = ctx.history
        if (background.isBlank() && history.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("以下是客户背景、课程资料与销售知识库。回复必须与之一致，")
            .append("只可引用其中明确存在的事实，不要补全或猜测缺失信息。\n")
        if (background.isNotBlank()) sb.append(background).append('\n')
        if (history.isNotEmpty()) {
            sb.append("\n更早的聊天记录（越靠下越新）：\n")
            history.takeLast(prefs.contextHistoryCount.coerceIn(0, 100)).forEach {
                sb.append(if (it.side == "me") "我：" else "对方：").append(it.text).append('\n')
            }
        }
        sb.append('\n')
        return sb.toString()
    }

    /**
     * One plain chat round trip for the settings connectivity test. Deliberately
     * NOT [summarize]: the test should exercise the ordinary path, not whatever
     * the summary prompt happens to be.
     */
    fun ping(): String =
        chat("你是连通性测试助手，只按要求回答，不要解释。", "请只回复两个字：收到", temperature = 0.0).trim()

    /** Condense a block of text (used by the D-stage contact auto-summary). */
    fun summarize(text: String): String {
        if (text.isBlank()) return ""
        val sys = "你是中文摘要助手。把给到的聊天记录压缩成不超过 120 字的第三人称要点摘要，" +
            "只保留事实、偏好、承诺和待办，不要评论，不要编造。直接输出摘要正文。"
        return chat(sys, text, temperature = 0.2).trim()
    }

    fun generateContent(
        platform: String,
        contentType: String,
        audience: String,
        topic: String,
        facts: String,
        outputMode: String = "完整图文"
    ): String {
        val sys = "你是教培机构的内容营销编辑。生成真实、克制、可直接修改使用的中文内容。" +
            "不得编造价格、师资、案例、优惠或学习效果；不得承诺提分、录取、考试通过；" +
            "不得制造虚假稀缺，不得诱导骚扰私信。输出模式为：$outputMode。" +
            "只输出内容正文，不要解释生成过程。"
        val user = "平台：$platform\n输出模式：$outputMode\n内容类型：$contentType\n目标人群：$audience\n主题：$topic\n" +
            "可使用的真实资料：${facts.ifBlank { "未提供，只能写通用教育建议" }}"
        val result = chat(sys, user, temperature = 0.7).trim()
        val violations = ComplianceGuard.inspect(result)
        if (violations.isNotEmpty()) {
            throw IllegalStateException("生成内容触发合规规则：${violations.joinToString("、") { it.rule }}")
        }
        return result
    }

    fun rewriteContent(content: String, direction: String): String {
        val sys = "你是教培内容编辑。只改写给定内容，保持事实不变，不增加价格、师资、案例、优惠、效果承诺或虚假稀缺。" +
            "改写方向：$direction。只输出改写后的正文，不要解释。"
        val result = chat(sys, content, temperature = 0.4).trim()
        val violations = ComplianceGuard.inspect(result)
        if (violations.isNotEmpty()) {
            throw IllegalStateException("改写内容触发合规规则：${violations.joinToString("、") { it.rule }}")
        }
        return result
    }

    /** Suggest structured CRM fields from a contact and its locally stored chat history. */
    fun suggestLead(contact: Contact, history: List<LogEntry>): LeadSuggestion {
        val convo = history.takeLast(20).joinToString("\n") {
            (if (it.side == "me") "我" else "客户") + "：" + it.text
        }
        val sys = "你是教培销售主管，负责做线索复盘。只输出 JSON 对象，不要解释。" +
            "leadStage 只能是：新线索、已沟通、已试听、待成交、已成交、暂缓；" +
            "intentLevel 只能是：待判断、高、中、低。" +
            "nextAction 是一条具体、低压力、可执行的下一步，不超过 30 字。" +
            "reason 是基于对话事实的判断依据，不超过 60 字。" +
            "不要编造客户没有说过的年级、预算、课程、时间或结果承诺。"
        val user = "客户档案：姓名=${contact.name}，来源=${contact.source}，当前阶段=${contact.leadStage}，" +
            "当前意向=${contact.intentLevel}，备注=${contact.notes}\n最近聊天：\n" +
            convo.ifBlank { "暂无聊天记录，只能返回待判断，并建议先了解需求。" }
        val raw = chat(sys, user, temperature = 0.1).trim()
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        val o = if (start >= 0 && end > start) runCatching {
            JSONObject(raw.substring(start, end + 1))
        }.getOrNull() else null
        val stages = setOf("新线索", "已沟通", "已试听", "待成交", "已成交", "暂缓")
        val intents = setOf("待判断", "高", "中", "低")
        return LeadSuggestion(
            leadStage = o?.optString("leadStage").orEmpty().takeIf { it in stages } ?: contact.leadStage,
            intentLevel = o?.optString("intentLevel").orEmpty().takeIf { it in intents } ?: "待判断",
            nextAction = o?.optString("nextAction").orEmpty().trim().take(40)
                .ifBlank { "先了解客户当前年级、目标和主要困难" },
            reason = o?.optString("reason").orEmpty().trim().take(100)
                .ifBlank { "信息不足，建议先补充真实需求后再判断。" }
        )
    }

    /** One chat-completions round trip; returns the assistant message content. */
    private fun chat(system: String, user: String, temperature: Double): String {
        val url = prefs.replyEndpoint()
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject()
            .put("model", prefs.replyModel)
            .put("messages", messages)
            .put("temperature", temperature)
        val resp = HttpJson.post(url, prefs.effectiveReplyKey(), body, Route.REPLY, HttpJson.headersFor(url))
        return resp.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content") ?: ""
    }

    private fun parseThree(content: String): List<String> {
        val start = content.indexOf('[')
        val end = content.lastIndexOf(']')
        if (start >= 0 && end > start) {
            try {
                val arr = JSONArray(content.substring(start, end + 1))
                val out = ArrayList<String>()
                for (i in 0 until arr.length()) out.add(arr.getString(i).trim())
                if (out.size >= 3) return out.take(3)
                while (out.size < 3) out.add("（稍等，我看下）")
                return out
            } catch (_: Exception) { }
        }
        // Fallback: split lines.
        val lines = content.split("\n").map { it.trim().trimStart('-', '*', '1', '2', '3', '.', ' ', '"') }
            .filter { it.isNotBlank() }
        val out = lines.take(3).toMutableList()
        while (out.size < 3) out.add("（稍等，我看下）")
        return out
    }
}
