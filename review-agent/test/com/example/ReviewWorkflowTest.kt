package com.example

import com.example.reviewer.ReviewCommentResult
import com.example.reviewer.ReviewResult
import com.example.reviewer.ReviewResultPublisher
import com.example.reviewer.ReviewWorkflow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReviewWorkflowTest {
    @Test
    fun `publishes completed result after successful review`() {
        val publisher = RecordingPublisher()
        val result =
            ReviewResult(comments = listOf(ReviewCommentResult(comment = "Review comment")))

        ReviewWorkflow(publisher) { result }

        assertEquals(listOf("start", "complete", "complete-result"), publisher.events)
    }

    @Test
    fun `publishes failed status and rethrows review exception`() {
        val publisher = RecordingPublisher()
        val failure = IllegalStateException("model unavailable")

        assertFailsWith<IllegalStateException> {
            ReviewWorkflow(publisher) { throw failure }
        }

        assertEquals(listOf("start", "fail:model unavailable"), publisher.events)
    }

    @Test
    fun `preserves original exception when failed status update throws`() {
        val publisher =
            RecordingPublisher(failException = IllegalStateException("status unavailable"))
        val failure = IllegalStateException("model unavailable")

        val thrown =
            assertFailsWith<IllegalStateException> {
                ReviewWorkflow(publisher) { throw failure }
            }

        assertEquals("model unavailable", thrown.message)
    }

    private class RecordingPublisher(private val failException: Exception? = null) :
        ReviewResultPublisher {
        val events = mutableListOf<String>()

        override fun start() {
            events += "start"
        }

        override fun complete(result: ReviewResult) {
            events += "complete"
            events += "complete-result"
        }

        override fun fail(exception: Exception) {
            events += "fail:${exception.message}"
            failException?.let { throw it }
        }
    }
}
