package com.zhiban.rebuild.runtime.network

import okhttp3.CertificatePinner

/**
 * Provider-only SPKI pins, verified against the public chains on 2026-07-21.
 *
 * Each host carries an intermediate-CA pin plus its root-CA backup pin. This avoids coupling
 * releases to short-lived leaf certificates while still narrowing trust below the platform's
 * complete CA set. Add the replacement intermediate before a CA rotation; remove the old pin
 * only in a later release after fleet migration.
 */
internal object ProviderCertificatePins {
    internal val pinsByHost: Map<String, Set<String>> = mapOf(
        "api.stepfun.com" to setOf(
            "sha256/E3tYcwo9CiqATmKtpMLW5V+pzIq+ZoDmpXSiJlXGmTo=", // RapidSSL TLS RSA CA G1
            "sha256/i7WTqTvh0OioIruIfFR4kMPnBqrS2rdiVPl/s2uC/CY=", // DigiCert Global Root G2
        ),
    )

    fun build(): CertificatePinner = CertificatePinner.Builder().apply {
        pinsByHost.forEach { (host, pins) -> add(host, *pins.toTypedArray()) }
    }.build()
}
