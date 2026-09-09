package eu.kanade.tachiyomi.extension.zh.copymanga

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CopyMangaPaginationTest {

    @Test
    fun `full page continues only while reported total has remaining items`() {
        assertTrue(
            copyMangaHasNextPage(
                offset = 0,
                total = 100,
                returnedSize = 21,
                requestedPageSize = 21,
            ),
        )
        assertFalse(
            copyMangaHasNextPage(
                offset = 79,
                total = 100,
                returnedSize = 21,
                requestedPageSize = 21,
            ),
        )
    }

    @Test
    fun `empty or short pages stop even when reported total is too large`() {
        assertFalse(
            copyMangaHasNextPage(
                offset = 45_901,
                total = 55_248,
                returnedSize = 0,
                requestedPageSize = 21,
            ),
        )
        assertFalse(
            copyMangaHasNextPage(
                offset = 45_880,
                total = 55_248,
                returnedSize = 12,
                requestedPageSize = 21,
            ),
        )
    }
}
