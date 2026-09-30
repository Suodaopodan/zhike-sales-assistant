package com.zhike.salesassistant.ai

import com.zhike.salesassistant.core.Prefs
import org.json.JSONObject

/**
 * Translates the app's System One request shape to provider-specific JSON.
 * Cloudflare wraps model input and successful output in one extra envelope.
 */
object JudgeProtocol {

    fun request(
        provider: String,
        model: String,
        state: JSONObject,
        questions: JSONObject
    ): JSONObject {
        val input = JSONObject()
            .put("state", state)
            .put("questions", questions)
        return if (provider == Prefs.PROVIDER_CLOUDFLARE) {
            JSONObject()
                .put("model", model)
                .put("input", input)
        } else {
            input.put("model", model)
        }
    }

    fun answers(provider: String, response: JSONObject): JSONObject {
        val payload = if (provider == Prefs.PROVIDER_CLOUDFLARE) {
            if (!response.optBoolean("success", false)) {
                val detail = response.optJSONArray("errors")?.toString().orEmpty()
                throw IllegalStateException(
                    if (detail.isBlank()) "Cloudflare 返回失败" else "Cloudflare 返回失败：$detail"
                )
            }
            response.optJSONObject("result")
                ?: throw IllegalStateException("Cloudflare 响应缺少 result")
        } else {
            response
        }
        return payload.optJSONObject("answers")
            ?: throw IllegalStateException("判断接口响应缺少 answers")
    }
}
