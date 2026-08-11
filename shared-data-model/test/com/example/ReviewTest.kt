package com.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ReviewTest {
    @Test
    fun `review Kubernetes target may contain request owner reference`() {
        val review = Review(
            repository = Repository("https://github.com/example/repository.git"),
            pr = "7",
            kubernetes = Review.Kubernetes(
                namespace = "reviews",
                name = "agent-review-request-7",
                ownerReference = Review.OwnerReference(
                    apiVersion = "example.com/v1",
                    kind = "AgentReviewRequest",
                    name = "request-7",
                    uid = "uid-7",
                ),
            ),
        )

        val ownerReference = assertNotNull(review.kubernetes.ownerReference)
        assertEquals("uid-7", ownerReference.uid)
        assertEquals(false, ownerReference.blockOwnerDeletion)
    }

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
