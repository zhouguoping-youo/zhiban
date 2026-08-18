package com.zhiban.rebuild.data.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 敏感消息丢弃过滤的隐私关键路径回归(审计测试盲区2):敏感词/OTP 近码/密钥近值/
 * 银行卡近值/孤立 Luhn 卡号五类规则 + 大小写归一化 + 边界。
 */
class SensitiveMessageFilterTest {

    @Test fun `sensitive keywords drop the message`() {
        assertTrue(SensitiveMessageFilter.shouldDrop("你的验证码是123456"))
        assertTrue(SensitiveMessageFilter.shouldDrop("VERIFICATION CODE: 9988"))
        assertTrue(SensitiveMessageFilter.shouldDrop("这笔交易的动态密码如下"))
    }

    @Test fun `otp near-code patterns drop the message`() {
        assertTrue(SensitiveMessageFilter.shouldDrop("登录验证码为 8231，10 分钟内有效"))
        assertTrue(SensitiveMessageFilter.shouldDrop("use 4321 as your one-time password"))
        assertFalse(SensitiveMessageFilter.shouldDrop("我们周六 8 点见面"))
    }

    @Test fun `secret key near-value patterns drop the message`() {
        assertTrue(SensitiveMessageFilter.shouldDrop("api key 是 AbCdEf12345678，请保存"))
        assertTrue(SensitiveMessageFilter.shouldDrop("密钥：Xk2mPq9vWz3rT8uA"))
        assertFalse(SensitiveMessageFilter.shouldDrop("帮我改一下密码"))
    }

    @Test fun `bank card near-value patterns drop the message`() {
        assertTrue(SensitiveMessageFilter.shouldDrop("银行卡号是 6222 0202 1234 5678"))
        assertTrue(SensitiveMessageFilter.shouldDrop("card number 4111111111111111"))
        assertFalse(SensitiveMessageFilter.shouldDrop("银行卡办理需要身份证"))
    }

    @Test fun `standalone luhn-valid card number drops the message`() {
        assertTrue(SensitiveMessageFilter.shouldDrop("请记下 4111111111111111"))
        assertFalse(SensitiveMessageFilter.shouldDrop("订单号 4111111111111112 请查收")) // Luhn 不通过
        assertFalse(SensitiveMessageFilter.shouldDrop("电话号码 13800138000"))
    }

    @Test fun `ordinary messages pass`() {
        assertFalse(SensitiveMessageFilter.shouldDrop("明天上午十点开会，别忘了带电脑"))
        assertFalse(SensitiveMessageFilter.shouldDrop("合同已经发你邮箱了"))
        assertFalse(SensitiveMessageFilter.shouldDrop(""))
    }
}
