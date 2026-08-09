package com.zhiban.rebuild.data.notification

internal object SensitiveMessageFilter {
    fun shouldDrop(value: String): Boolean {
        val normalized = value.lowercase()
        return sensitiveWords.any(normalized::contains) ||
            otpNearCode.containsMatchIn(normalized) ||
            secretKeyNearValue.containsMatchIn(normalized) ||
            bankCardNearValue.containsMatchIn(normalized) ||
            cardNumberCandidate.findAll(normalized).any { it.value.isLuhnValidCardNumber() }
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
    private const val BANK_CARD_KEYWORD =
        "(?:银行卡|银行账号|储蓄卡|信用卡|借记卡|bank\\s+card|card\\s+number|account\\s+number)"
    private const val BANK_CARD_VALUE = "(?:[0-9*][ -]?){3,23}[0-9*]"
    private val bankCardNearValue = Regex(
        "(?:$BANK_CARD_KEYWORD.{0,20}$BANK_CARD_VALUE|$BANK_CARD_VALUE.{0,20}$BANK_CARD_KEYWORD)",
        RegexOption.IGNORE_CASE,
    )
    private val cardNumberCandidate = Regex("""(?<!\d)(?:\d[ -]?){12,18}\d(?!\d)""")

    private fun String.isLuhnValidCardNumber(): Boolean {
        val digits = filter(Char::isDigit)
        if (digits.length !in 13..19) return false
        val sum = digits.reversed().foldIndexed(0) { index, total, character ->
            val value = character.digitToInt()
            total + if (index % 2 == 0) value else (value * 2).let { if (it > 9) it - 9 else it }
        }
        return sum % 10 == 0
    }
}
