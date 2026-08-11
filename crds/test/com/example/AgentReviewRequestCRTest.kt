package com.example

import kotlin.test.Test
import kotlin.test.assertEquals

class AgentReviewRequestCRTest {
    @Test
    fun `request model stores repository and pull request`() {
        val request = AgentReviewRequestCR().apply {
            spec = AgentReviewRequestSpec().apply {
                repository = AgentReviewRepository().apply {
                    url = "https://github.com/example/repository.git"
                }
                pr = "42"
            }
        }

        assertEquals("https://github.com/example/repository.git", request.spec.repository!!.url)
        assertEquals("42", request.spec.pr)
    }
}
