package com.zhike.salesassistant.core.content

data class ContentRecord(
    val id: String,
    val platform: String,
    val outputMode: String,
    val contentType: String,
    val audience: String,
    val topic: String,
    val facts: String,
    val content: String,
    val favorite: Boolean = false,
    val sourceId: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

