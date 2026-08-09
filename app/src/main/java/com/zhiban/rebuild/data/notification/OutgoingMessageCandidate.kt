package com.zhiban.rebuild.data.notification

import java.security.MessageDigest

internal fun outgoingAccessibilityCandidate(
    packageName: String,
    appLabel: String,
    conversationTitle: String,
    body: String,
    postedAtEpochMs: Long,
): NotificationCandidateEntity? {
    val platform = SocialAppCatalog.platformForPackage(packageName) ?: return null
    val cleanTitle = conversationTitle.normalizedVisibleText(80) ?: return null
    val cleanBody = body.normalizedVisibleText(2_000) ?: return null
    if (SensitiveMessageFilter.shouldDrop(cleanBody)) return null
    val bucket = postedAtEpochMs / DEDUPE_WINDOW_MS
    val sourceKey = digest("accessibility|${platform.code}|$cleanTitle|$cleanBody|$bucket")
    return NotificationCandidateEntity(
        candidateId = "outgoing-${sourceKey.take(32)}",
        sourceKey = sourceKey,
        packageName = packageName,
        appLabel = appLabel.take(80),
        title = cleanTitle,
        body = cleanBody,
        postedAtEpochMs = postedAtEpochMs,
        createdAtEpochMs = postedAtEpochMs,
        sourceType = "ACCESSIBILITY",
        platform = platform.code,
        conversationTitle = cleanTitle,
        senderName = cleanTitle,
        direction = "OUTGOING",
        messageKind = "MESSAGE",
        insightJson = NotificationInsightAnalyzer.analyze(
            text = cleanBody,
            senderName = cleanTitle,
            conversationTitle = cleanTitle,
            postedAtEpochMs = postedAtEpochMs,
        ).toJsonOrNull(),
    )
}

private fun String.normalizedVisibleText(limit: Int): String? = replace(Regex("\\s+"), " ").trim().take(limit).takeIf(String::isNotBlank)

private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

private const val DEDUPE_WINDOW_MS = 5 * 60_000L
