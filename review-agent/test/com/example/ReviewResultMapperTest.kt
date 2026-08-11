package com.example

import com.example.reviewer.ReviewCommentResult
import com.example.reviewer.ReviewResult
import com.example.reviewer.toSpec
import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewResultMapperTest {
    @Test
    fun `maps review comments into CR spec`() {
        val spec =
            ReviewResult(
                    comments =
                        listOf(
                            ReviewCommentResult(
                                file = "src/main/kotlin/Example.kt",
                                lines = listOf(3, 4),
                                comment = "Handle null response",
                            )
                        )
                )
                .toSpec()

        assertEquals(1, spec.comments!!.size)
        assertEquals("src/main/kotlin/Example.kt", spec.comments!![0].file)
        assertEquals(listOf(3, 4), spec.comments!![0].lines)
        assertEquals("Handle null response", spec.comments!![0].comment)
    }

    @Test
    fun `maps missing review comments to empty CR list`() {
        assertEquals(emptyList(), ReviewResult().toSpec().comments)
    }
}
