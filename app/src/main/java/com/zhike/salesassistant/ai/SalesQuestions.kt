package com.zhike.salesassistant.ai

import com.zhike.salesassistant.core.ChatSnapshot
import com.zhike.salesassistant.core.kb.LogEntry
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fixed sales question set with calibrated wording. Instructions/criteria in English; chat text
 * stays Chinese. The state `from` field uses "me"/"other" (the instructions
 * already refer to "the other person" throughout).
 */
object SalesQuestions {

    /**
     * Appended to every question so the D-stage `background` field (relationship,
     * contact notes, knowledge-base hits) reads as given context rather than as
     * an off-topic digression that should be penalized.
     */
    const val BACKGROUND_NOTE =
        " Facts given in background are provided context, not off-topic."

    private fun noul(instructions: String, t: String, f: String) = JSONObject().apply {
        put("type", "noul")
        put("instructions", instructions + BACKGROUND_NOTE)
        put("criteria", JSONObject().put("true", t).put("false", f))
    }

    private fun choice(instructions: String, criteria: Map<String, String>) = JSONObject().apply {
        put("type", "choice")
        put("instructions", instructions + BACKGROUND_NOTE)
        put("criteria", JSONObject().also { c -> criteria.forEach { (k, v) -> c.put(k, v) } })
    }

    private fun score(instructions: String, levels: List<String>) = JSONObject().apply {
        put("type", "score")
        put("instructions", instructions + BACKGROUND_NOTE)
        put("criteria", JSONArray().also { a -> levels.forEach { a.put(it) } })
    }

    /** Sales qualification questions. Returns a fresh JSONObject each call. */
    fun judge(): JSONObject = JSONObject().apply {
        put("literal_question", noul(
            "Has the customer stated a sufficiently explicit education need in the latest conversation? " +
                "Use the full thread. A useful need normally includes at least one of grade, subject, " +
                "current difficulty, target, schedule, budget, or expected service.",
            "The need is explicit enough to answer or recommend a concrete next step without guessing.",
            "Important facts are still missing; the next reply should clarify needs instead of pitching."
        ))
        put("true_intent", choice(
            "What is the customer's primary sales intent now? Use the latest message first and the full " +
                "thread for context. Select the stage that best describes what the customer is trying to do.",
            linkedMapOf(
                "discover_need" to "They are describing a problem or browsing and still need guided discovery.",
                "ask_price" to "They primarily ask about price, discount, refund, trial, or payment.",
                "compare_options" to "They are comparing courses, teachers, institutions, formats, or alternatives.",
                "verify_trust" to "They want evidence about teachers, curriculum, outcomes, service, or credibility.",
                "handle_objection" to "They express a concern such as price, time, effectiveness, fit, or prior bad experience.",
                "ready_to_convert" to "They clearly want to book, trial, register, pay, or continue through an agreed channel."
            )
        ))
        put("danger_level", score(
            "Score current purchase intent, based only on observable signals in the conversation. " +
                "Do not inflate the score merely because the customer replied.",
            listOf(
                "Low intent: casual browsing, vague interaction, or no education need.",
                "Early intent: a real pain point appears, but key needs and constraints are unknown.",
                "Medium intent: asks concrete questions about course, teacher, price, format, or outcomes.",
                "High intent: compares options, shares child details, asks for a plan, trial, schedule, or discount.",
                "Very high intent: explicitly agrees to book, trial, register, pay, or provide necessary contact details."
            )
        ))
        put("should_reply_now", noul(
            "Should the next reply guide the customer to a concrete next step now? " +
                "A concrete next step may be answering with verified facts, asking one useful question, " +
                "sharing an approved resource, booking a trial, or confirming an agreed follow-up. " +
                "Do not choose true when the customer has declined, asked not to be contacted, or only needs a factual answer.",
            "A relevant, low-pressure next step is appropriate and supported by known facts.",
            "Answer the question briefly, pause follow-up, or respect the customer's refusal."
        ))
        put("best_action", choice(
            "What is the best sales action for the next reply? Use only verified knowledge-base facts. " +
                "Do not request contact details before value and consent are established.",
            linkedMapOf(
                "ask_need" to "Ask one focused question about grade, subject, difficulty, target, schedule, or budget.",
                "answer_fact" to "Directly answer with a verified course, price, teacher, schedule, or service fact.",
                "share_proof" to "Share an approved case, curriculum outline, teacher profile, or service explanation.",
                "handle_objection" to "Acknowledge and resolve the customer's stated concern without arguing or over-promising.",
                "invite_trial" to "Invite the customer to an available assessment, consultation, or trial supported by the knowledge base.",
                "request_contact" to "With explicit consent, confirm the appropriate next communication or booking details.",
                "follow_up_later" to "Respect a decline or timing issue and agree on a non-intrusive follow-up point."
            )
        ))
        put("she_needs", choice(
            "What does the customer most need before moving forward? Judge the latest message first.",
            linkedMapOf(
                "clarity" to "A clearer diagnosis of needs or explanation of what is suitable.",
                "value" to "A concrete explanation of curriculum value and fit, without generic selling points.",
                "trust" to "Verifiable evidence about teachers, service, process, or approved cases.",
                "price" to "Transparent price, included services, discount conditions, or refund terms.",
                "timing" to "Schedule, course frequency, trial time, or a suitable follow-up time.",
                "service" to "Registration, booking, after-sales, learning support, or another concrete service action."
            )
        ))
        put("tension_resolved", noul(
            "Has the customer's latest objection or concern been clearly resolved? " +
                "If there was no objection, answer true. Do not infer resolution from silence or a polite filler.",
            "No active objection remains, or the customer explicitly accepted the explanation or plan.",
            "A price, trust, fit, timing, effectiveness, or service concern is still open."
        ))
    }

    /**
     * Build judgment state from a snapshot (last 10 messages), optionally carrying
     * the D-stage knowledge context.
     *
     * @param background relationship + contact notes + matched knowledge notes.
     * @param history older messages for this contact, already de-duplicated
     *        against what is on screen.
     *
     * Both extra fields are omitted when empty, so a user with no knowledge base
     * sends exactly the same body v1.2 did.
     */
    fun buildState(
        snapshot: ChatSnapshot,
        relationship: String,
        background: String = "",
        history: List<LogEntry> = emptyList()
    ): JSONObject {
        val msgs = JSONArray()
        val last10 = snapshot.messages.takeLast(10)
        for (m in last10) {
            msgs.put(JSONObject().put("from", m.side).put("text", m.text))
        }
        val chat = JSONObject()
            .put("relationship", relationship)
            .put("messages", msgs)
            .put("latest_from", last10.lastOrNull()?.side ?: "other")
        val state = JSONObject().put("chat", chat)
        if (background.isNotBlank()) state.put("background", background)
        if (history.isNotEmpty()) {
            val h = JSONArray()
            history.forEach { h.put(JSONObject().put("from", it.side).put("text", it.text)) }
            state.put("history", h)
        }
        return state
    }

    /** The best_reply ranking question over exactly 3 candidates (Chinese text kept). */
    fun rankQuestion(candidates: List<String>): JSONObject {
        require(candidates.size == 3) { "rankQuestion expects exactly 3 candidates" }
        val keys = listOf("reply_a", "reply_b", "reply_c")
        val criteria = JSONObject()
        keys.forEachIndexed { i, k -> criteria.put(k, candidates[i]) }
        val q = JSONObject().apply {
            put("type", "choice")
            put("instructions",
                "Which candidate is the best next sales reply for this customer and stage? " +
                    "Prefer the reply that addresses the latest need, uses verified knowledge, " +
                    "asks at most one useful question, and proposes a low-pressure next step. " +
                    "Penalize invented facts, guarantees, absolute claims, fake scarcity, excessive urgency, " +
                    "platform-rule evasion, or requesting contact details without consent." + BACKGROUND_NOTE)
            put("criteria", criteria)
        }
        return JSONObject().put("best_reply", q)
    }
}
