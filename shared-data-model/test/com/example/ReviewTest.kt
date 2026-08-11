package com.example

import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewTest {
    @Test
    fun `review stores repository and pull request`() {
        val review = Review(
            repository = Repository("https://github.com/example/project.git"),
            pr = "42",
            kubernetes = Review.Kubernetes("reviews", "review-result-42"),
        )

        assertEquals("https://github.com/example/project.git", review.repository.url)
        assertEquals("42", review.pr)
        assertEquals("reviews", review.kubernetes.namespace)
        assertEquals("review-result-42", review.kubernetes.name)
    }
}
