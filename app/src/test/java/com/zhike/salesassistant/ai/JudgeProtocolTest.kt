package com.zhike.salesassistant.ai

import com.zhike.salesassistant.core.Prefs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JudgeProtocolTest {

    @Test
    fun cloudflareWrapsSystemOneInput() {
        val state = JSONObject().put("conversation", "想了解课程价格")
        val questions = JSONObject().put(
            "high_intent",
            JSONObject().put("type", "noul").put("instructions", "是否高意向")
        )

        val body = JudgeProtocol.request(
            Prefs.PROVIDER_CLOUDFLARE,
            Prefs.DEFAULT_JUDGE_MODEL_CLOUDFLARE,
            state,
            questions
        )

        assertEquals("typesafe/jev", body.getString("model"))
        assertFalse(body.has("state"))
        assertEquals(state.toString(), body.getJSONObject("input").getJSONObject("state").toString())
        assertTrue(body.getJSONObject("input").getJSONObject("questions").has("high_intent"))
    }

    @Test
    fun cloudflareUnwrapsAnswers() {
        val response = JSONObject()
            .put("success", true)
            .put(
                "result",
                JSONObject().put(
                    "answers",
                    JSONObject().put(
                        "high_intent",
                        JSONObject().put("type", "noul").put("noul", 0.91)
                    )
                )
            )

        val answers = JudgeProtocol.answers(Prefs.PROVIDER_CLOUDFLARE, response)

        assertEquals(0.91, answers.getJSONObject("high_intent").getDouble("noul"), 0.001)
    }

    @Test(expected = IllegalStateException::class)
    fun cloudflareRejectsFailedEnvelope() {
        JudgeProtocol.answers(
            Prefs.PROVIDER_CLOUDFLARE,
            JSONObject().put("success", false).put("errors", "invalid token")
        )
    }
}
