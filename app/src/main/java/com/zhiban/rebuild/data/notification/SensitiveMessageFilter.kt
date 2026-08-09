package com.zhiban.rebuild.data.notification

internal object SensitiveMessageFilter {
    fun shouldDrop(value: String): Boolean {
        val normalized = value.lowercase()
        return sensitiveWords.any(normalized::contains) ||
            otpNearCode.containsMatchIn(normalized) ||
            secretKeyNearValue.containsMatchIn(normalized)
    }

    private val sensitiveWords = listOf(
        "验证码",
        "校验码",
        "动态密码",
        "动态口令",
        "交易密码",
        "登录码",
        "支付密码",
        "取款密码",
        "one-time password",
        "verification code",
        " otp",
    )
    private const val OTP_KEYWORD =
        "(?:验证码|校验码|动态密码|动态口令|交易密码|登录码|支付密码|取款密码|one[\\s-]?time\\s+password|verification\\s+code|verify\\s+code|security\\s+code|passcode|login\\s+code|auth(?:entication)?\\s+code|confirmation\\s+code|(?:your|the)\\s+code|otp)"
    private val otpNearCode = Regex(
        """(?:$OTP_KEYWORD.{0,20}\b\d{4,8}\b|\b\d{4,8}\b.{0,20}$OTP_KEYWORD)""",
        RegexOption.IGNORE_CASE,
    )
    private val secretKeyNearValue = Regex(
        """(?:密钥|secret\s+key|api\s+key).{0,20}(?:\b\d{4,8}\b|[A-Za-z0-9_-]{12,})|(?:\b\d{4,8}\b|[A-Za-z0-9_-]{12,}).{0,20}(?:密钥|secret\s+key|api\s+key)""",
        RegexOption.IGNORE_CASE,
    )
}
