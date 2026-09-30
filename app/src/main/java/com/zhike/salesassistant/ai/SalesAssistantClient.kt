package com.zhike.salesassistant.ai

import com.zhike.salesassistant.core.Analysis
import com.zhike.salesassistant.core.ChatSnapshot
import com.zhike.salesassistant.core.ComplianceGuard
import com.zhike.salesassistant.core.Prefs
import com.zhike.salesassistant.core.RankedReply
import com.zhike.salesassistant.core.kb.ChatContext

/**
 * Thin facade over the three split clients so callers keep one entry point.
 * Construct with [Prefs] — every route reads its own address / key / model from
 * there, so switching providers in settings takes effect on the next call.
 */
class SalesAssistantClient(prefs: Prefs) {

    private val judgeClient = JudgeClient(prefs)
    private val replyClient = ReplyClient(prefs)

    /** The 7 judgment questions. Errors come back inside [Analysis.error]. */
    fun judge(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext? = null): Analysis =
        judgeClient.judge(snapshot, relationship, ctx)

    /** Draft 3 candidates on the reply route, then rank them on the judge route. */
    fun draftAndRank(
        snapshot: ChatSnapshot,
        relationship: String,
        ctx: ChatContext? = null
    ): List<RankedReply> {
        val candidates = ComplianceGuard.sanitizeCandidates(
            replyClient.draft(snapshot, relationship, ctx)
        )
        return judgeClient.rank(snapshot, relationship, candidates, ctx)
    }

    /** Judge + replies, sequential. Used by the settings connectivity test. */
    fun analyze(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext? = null): Analysis {
        val a = judge(snapshot, relationship, ctx)
        if (a.error != null) return a
        val ranked = try { draftAndRank(snapshot, relationship, ctx) } catch (e: Exception) { emptyList() }
        return a.copy(rankedReplies = ranked)
    }
}
