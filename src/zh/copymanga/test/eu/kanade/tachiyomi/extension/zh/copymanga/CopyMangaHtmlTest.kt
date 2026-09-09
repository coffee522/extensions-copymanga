package eu.kanade.tachiyomi.extension.zh.copymanga

import keiyoushi.utils.asJsoup
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CopyMangaHtmlTest {

    @Test
    fun `parses the website comic list attribute without executing javascript`() {
        val document = """
            <div class="exemptComicList">
                <div class="exemptComic-box" total="2" list="[{&#x27;path_word&#x27;: &#x27;alpha&#x27;, &#x27;name&#x27;: &#x27;第一本&#x27;, &#x27;cover&#x27;: &#x27;https://img.test/alpha.jpg&#x27;, &#x27;status&#x27;: 0, &#x27;author&#x27;: [{&#x27;name&#x27;: &#x27;作者甲&#x27;, &#x27;path_word&#x27;: &#x27;author-a&#x27;}]}, {&#x27;path_word&#x27;: &#x27;beta&#x27;, &#x27;name&#x27;: &#x27;第二本&#x27;, &#x27;cover&#x27;: &#x27;https://img.test/beta.jpg&#x27;, &#x27;status&#x27;: 2, &#x27;author&#x27;: [{&#x27;name&#x27;: &#x27;作者乙&#x27;, &#x27;path_word&#x27;: &#x27;author-b&#x27;}, {&#x27;name&#x27;: &#x27;作者丙&#x27;, &#x27;path_word&#x27;: &#x27;author-c&#x27;}]}]"></div>
            </div>
        """.asJsoup("https://www.mangacopy.com")

        val page = document.parseCopyMangaHtmlPage()

        assertEquals(2, page.total)
        assertEquals(listOf("alpha", "beta"), page.comics.map { it.pathWord })
        assertEquals("第一本", page.comics.first().name)
        assertEquals(listOf("作者甲"), page.comics.first().author.map { it.name })
        assertEquals(0, page.comics.first().status?.value)
        assertEquals(2, page.comics.last().status?.value)
        assertEquals("https://img.test/beta.jpg", page.comics.last().cover)
    }

    @Test
    fun `parses an empty website result`() {
        val document = """
            <div class="exemptComicList">
                <div class="exemptComic-box" total="0" list="[]"></div>
            </div>
        """.asJsoup("https://www.mangacopy.com")

        val page = document.parseCopyMangaHtmlPage()

        assertEquals(0, page.total)
        assertTrue(page.comics.isEmpty())
    }

    @Test
    fun `html requests do not carry API-only headers`() {
        val htmlHeaders = Headers.Builder()
            .add("Accept", "application/json")
            .add("User-Agent", "test-agent")
            .add("Version", "2025.11.21")
            .add("Webp", "1")
            .add("platform", "1")
            .build()
            .copyMangaHtmlHeaders()

        assertEquals("text/html", htmlHeaders["Accept"])
        assertEquals("test-agent", htmlHeaders["User-Agent"])
        assertNull(htmlHeaders["Version"])
        assertNull(htmlHeaders["Webp"])
        assertNull(htmlHeaders["platform"])
    }
}
