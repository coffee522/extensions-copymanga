package eu.kanade.tachiyomi.extension.zh.copymanga

import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString.Companion.decodeHex
import org.jsoup.nodes.Document
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal val copyMangaWebJson = Json { ignoreUnknownKeys = true }

internal fun Document.copyMangaScriptValue(name: String): String {
    val pattern = Regex("""\bvar\s+${Regex.escape(name)}\s*=\s*['"]([^'"]+)['"]""")
    return select("script").firstNotNullOfOrNull { script -> pattern.find(script.data())?.groupValues?.get(1) }
        ?: error("CopyManga HTML response is missing $name")
}

internal fun decodeCopyMangaWebPayload(key: String, payload: String): String {
    // The website prepends a 16-byte IV to the hexadecimal AES-CBC payload.
    require(payload.length > 16) { "CopyManga website data is truncated" }
    val iv = payload.take(16).toByteArray()
    val encrypted = payload.substring(16).decodeHex().toByteArray()
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES"), IvParameterSpec(iv))
    return cipher.doFinal(encrypted).decodeToString()
}

internal fun Document.parseCopyMangaWebImageUrls(): List<String> {
    val json = decodeCopyMangaWebPayload(copyMangaScriptValue("cct"), copyMangaScriptValue("contentKey"))
    val images = copyMangaWebJson.decodeFromString<List<CopyMangaPage>>(json)
    check(images.isNotEmpty()) { "CopyManga website returned an empty page list" }
    return images.map { image ->
        val url = image.url
        check(url != null && url.toHttpUrlOrNull()?.isHttps == true) { "CopyManga website returned an invalid image URL" }
        url
    }
}
