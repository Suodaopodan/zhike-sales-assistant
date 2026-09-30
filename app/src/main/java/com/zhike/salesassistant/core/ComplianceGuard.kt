package com.zhike.salesassistant.core

/**
 * Deterministic last-mile guard for education sales copy.
 *
 * Prompt instructions reduce bad output but cannot guarantee compliance. This
 * guard runs after generation and again before filling the chat input.
 */
object ComplianceGuard {

    data class Violation(val rule: String, val match: String)

    private data class Rule(val name: String, val pattern: Regex)

    private val rules = listOf(
        Rule("结果承诺", Regex("""(保过|包过|包录取|包上岸|保证.{0,8}(提分|录取|通过|上岸)|不过退款)""")),
        Rule("绝对效果", Regex("""(100%|百分之百).{0,8}(提分|通过|录取|有效|上岸)""", RegexOption.IGNORE_CASE)),
        Rule("绝对化宣传", Regex("""(全国第一|行业第一|全网第一|唯一指定|最权威|最顶级|国家级名师)""")),
        Rule("考试违规暗示", Regex("""(押中原题|内部原题|考试泄题|命题组内部|提前拿题)""")),
        Rule("收益承诺", Regex("""(稳赚不赔|保证回本|保证赚钱|零风险收益)""")),
        Rule("虚假稀缺", Regex("""(最后.{0,4}(名额|席位)|仅剩.{0,3}(名额|席位)).{0,10}(马上|立刻|现在)?"""))
    )

    private val safeFallbacks = listOf(
        "我先了解一下孩子目前的基础和目标，再帮您匹配更合适的课程方案。",
        "课程效果会因基础和投入不同而有差异，我可以先把课程内容和服务说明发您参考。",
        "您方便说下目前年级、学习目标和主要困难吗？我再给您更准确的建议。"
    )

    fun inspect(text: String): List<Violation> =
        rules.mapNotNull { rule ->
            rule.pattern.find(text)?.let { Violation(rule.name, it.value) }
        }

    fun isAllowed(text: String): Boolean = inspect(text).isEmpty()

    /**
     * Preserve exactly three unique candidates. Unsafe or empty generations are
     * replaced with conservative discovery-oriented replies.
     */
    fun sanitizeCandidates(candidates: List<String>): List<String> {
        val accepted = candidates.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && isAllowed(it) }
            .distinct()
            .take(3)
            .toMutableList()

        safeFallbacks.forEach { fallback ->
            if (accepted.size < 3 && fallback !in accepted) accepted.add(fallback)
        }
        return accepted.take(3)
    }
}
