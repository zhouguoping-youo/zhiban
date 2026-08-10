package com.zhiban.rebuild.data.contact.enrichment

import com.zhiban.rebuild.runtime.tool.sha256
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Public-company evidence returned by the ZhiBan server; never a proven employment fact. */
data class CompanyRegistryMatch(
    val providerRecordId: String,
    val canonicalName: String,
    val creditCode: String?,
    val registrationStatus: String?,
    val registeredAddress: String?,
    val confidence: Double,
    val matchReasons: List<String>,
)

interface CompanyRegistryGateway {
    val isConfigured: Boolean

    /** Sends only a company-name hint. Contact names, phones and emails are forbidden here. */
    suspend fun search(companyHint: String): List<CompanyRegistryMatch>
}

object UnavailableCompanyRegistryGateway : CompanyRegistryGateway {
    override val isConfigured: Boolean = false

    override suspend fun search(companyHint: String): List<CompanyRegistryMatch> = emptyList()
}

internal class HttpCompanyRegistryGateway(private val client: OkHttpClient, baseUrl: String, private val json: Json = Json { ignoreUnknownKeys = true }) :
    CompanyRegistryGateway {
    private val searchUrl = baseUrl.trim().takeIf(String::isNotEmpty)?.toHttpUrlOrNull()
        ?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }
        ?.newBuilder()
        ?.addPathSegments("v1/company/search")
        ?.build()

    override val isConfigured: Boolean = searchUrl != null

    override suspend fun search(companyHint: String): List<CompanyRegistryMatch> {
        val query = companyHint.trim()
        require(query.length in MIN_QUERY_CHARS..MAX_QUERY_CHARS) { "INVALID_COMPANY_QUERY" }
        val url = checkNotNull(searchUrl) { "COMPANY_GATEWAY_NOT_CONFIGURED" }
        val payload = json.encodeToString(
            CompanySearchRequest(
                requestId = "company-${sha256(query).take(24)}",
                query = query,
            ),
        )
        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .build()
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "COMPANY_GATEWAY_HTTP_${response.code}" }
                val body = checkNotNull(response.body) { "COMPANY_GATEWAY_EMPTY_BODY" }
                val bytes = readBounded(body.byteStream())
                val decoded = json.decodeFromString<CompanySearchResponse>(
                    bytes.toString(StandardCharsets.UTF_8),
                )
                validate(decoded)
            }
        }
    }

    private fun validate(response: CompanySearchResponse): List<CompanyRegistryMatch> {
        require(response.provider == PROVIDER_ID) { "COMPANY_GATEWAY_PROVIDER_MISMATCH" }
        require(response.matches.size <= MAX_MATCHES) { "COMPANY_GATEWAY_TOO_MANY_MATCHES" }
        return response.matches.map { match ->
            CompanyRegistryMatch(
                providerRecordId = match.providerRecordId.requireWireText(MAX_RECORD_ID_CHARS),
                canonicalName = match.canonicalName.requireWireText(MAX_COMPANY_NAME_CHARS),
                creditCode = match.creditCode.optionalWireText(MAX_CREDIT_CODE_CHARS),
                registrationStatus = match.registrationStatus.optionalWireText(MAX_STATUS_CHARS),
                registeredAddress = match.registeredAddress.optionalWireText(MAX_ADDRESS_CHARS),
                confidence = match.confidence.also {
                    require(it in MIN_CONFIDENCE..1.0) { "COMPANY_GATEWAY_INVALID_CONFIDENCE" }
                },
                matchReasons = match.matchReasons.take(MAX_REASONS).map {
                    it.requireWireText(MAX_REASON_CHARS)
                },
            )
        }
    }

    private fun String.requireWireText(maxChars: Int): String = trim().also {
        require(it.isNotEmpty() && it.length <= maxChars) { "COMPANY_GATEWAY_INVALID_FIELD" }
    }

    private fun String?.optionalWireText(maxChars: Int): String? = this?.trim()?.takeIf(String::isNotEmpty)?.also {
        require(it.length <= maxChars) { "COMPANY_GATEWAY_INVALID_FIELD" }
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            check(total <= MAX_RESPONSE_BYTES) { "COMPANY_GATEWAY_RESPONSE_TOO_LARGE" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private companion object {
        const val PROVIDER_ID = "qichacha"
        const val MIN_QUERY_CHARS = 2
        const val MAX_QUERY_CHARS = 80
        const val MAX_MATCHES = 5
        const val MAX_RESPONSE_BYTES = 64 * 1_024
        const val MAX_RECORD_ID_CHARS = 160
        const val MAX_COMPANY_NAME_CHARS = 200
        const val MAX_CREDIT_CODE_CHARS = 32
        const val MAX_STATUS_CHARS = 40
        const val MAX_ADDRESS_CHARS = 300
        const val MAX_REASONS = 4
        const val MAX_REASON_CHARS = 80
        const val MIN_CONFIDENCE = 0.5
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

@Serializable
private data class CompanySearchRequest(val requestId: String, val query: String)

@Serializable
private data class CompanySearchResponse(val provider: String, val matches: List<CompanySearchMatch>)

@Serializable
private data class CompanySearchMatch(
    val providerRecordId: String,
    val canonicalName: String,
    val creditCode: String? = null,
    val registrationStatus: String? = null,
    val registeredAddress: String? = null,
    val confidence: Double,
    val matchReasons: List<String> = emptyList(),
)
