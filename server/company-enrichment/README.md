# 知伴工商查询网关

该服务只接收企业名称线索，代理企查查“企业模糊搜索”，并把返回值裁剪为企业主体字段。联系人姓名、手机号、邮箱、消息内容都不允许进入此接口。

## 运行

服务端环境变量：

- `QCC_APP_KEY`
- `QCC_SECRET_KEY`
- `QCC_DAILY_BUDGET`，默认 1000
- `PER_IP_REQUESTS_PER_MINUTE`，默认 10
- `PORT`，默认 8787

```sh
npm test
npm start
```

生产环境必须部署在 HTTPS 反向代理或 API Gateway 后，并在边缘层校验用户会话或设备证明、增加设备级限流与总预算告警；不能把当前进程直接暴露到公网。服务不记录请求正文，也不返回企查查原始响应。

企查查接口以其[企业模糊搜索官方文档](https://openapi.qcc.com/dataApi/886)为准，生产启用前仍需完成企业实名、场景审核与真实凭据握手。

Android 构建时通过仓库外 Gradle 属性配置地址：

```properties
zhiban.companyEnrichmentBaseUrl=https://enrichment.example.com/
```

未配置时 Android 使用安全的不可用实现，不发起网络请求，也不影响本地联系人能力。
