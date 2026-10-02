package com.gatecontrol.android.network

import com.gatecontrol.android.common.SupportRedactor
import com.google.gson.GsonBuilder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends a support bundle (value tree of String / Number / Boolean / List /
 * Map, schema 1) to POST /api/v1/client/support-bundle: one more full
 * redaction pass ([SupportRedactor.redactValue]), JSON, gzip.
 */
@Singleton
class SupportBundleUploader @Inject constructor(
    private val apiClientProvider: ApiClientProvider,
) {
    suspend fun upload(serverUrl: String, peerId: Int, bundle: Map<String, Any?>): SupportBundleUploadResponse {
        val body = encode(bundle).toRequestBody(GZIP)
        return apiClientProvider.getClient(serverUrl).uploadSupportBundle(peerId, body)
    }

    companion object {
        private val GZIP = "application/gzip".toMediaType()
        private val gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()

        /** Redacted JSON of [bundle]. */
        fun toJson(bundle: Map<String, Any?>): String = gson.toJson(SupportRedactor.redactValue(bundle))

        /** gzip(toJson(bundle)). */
        fun encode(bundle: Map<String, Any?>): ByteArray {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(toJson(bundle).toByteArray(Charsets.UTF_8)) }
            return out.toByteArray()
        }
    }
}
