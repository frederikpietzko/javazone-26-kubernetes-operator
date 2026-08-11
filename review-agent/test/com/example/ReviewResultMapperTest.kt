package com.example

import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewResultMapperTest {
    @Test
    fun `maps review comments into CR spec`() {
        val spec = ReviewResult(
            file = "src/main/kotlin/Example.kt",
            comments = listOf(
                ReviewCommentResult(lines = listOf(3, 4), comment = "Handle null response"),
            ),
        ).toSpec()

        assertEquals("src/main/kotlin/Example.kt", spec.file)
        assertEquals(1, spec.comments!!.size)
        assertEquals(listOf(3, 4), spec.comments!![0].lines)
        assertEquals("Handle null response", spec.comments!![0].comment)
    }

    @Test
    fun `maps missing review comments to empty CR list`() {
        assertEquals("", ReviewResult().toSpec().file)
        assertEquals(emptyList(), ReviewResult().toSpec().comments)
    }
}
