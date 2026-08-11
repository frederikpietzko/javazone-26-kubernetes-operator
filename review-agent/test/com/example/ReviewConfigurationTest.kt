package com.example

import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReviewConfigurationTest {
    @Test
    fun `binds review properties into shared model`() {
        val environment = StandardEnvironment().apply {
            propertySources.addFirst(
                MapPropertySource(
                    "test",
                    mapOf(
                        "review.repository.url" to "https://github.com/example/project.git",
                        "review.pr" to "42",
                    ),
                ),
            )
        }

        val review = ReviewConfiguration().review(environment)

        assertEquals("https://github.com/example/project.git", review.repository.url)
        assertEquals("42", review.pr)
    }

    @Test
    fun `binds review result target`() {
        val environment = StandardEnvironment().apply {
            propertySources.addFirst(
                MapPropertySource(
                    "test",
                    mapOf(
                        "review.repository.url" to "https://github.com/example/project.git",
                        "review.pr" to "42",
                        "review.kubernetes.namespace" to "reviews",
                        "review.kubernetes.name" to "review-result-42",
                    ),
                ),
            )
        }

        val target = ReviewConfiguration().reviewResultTarget(environment)

        assertEquals("reviews", target.namespace)
        assertEquals("review-result-42", target.name)
    }

    @Test
    fun `fails when review result target properties are missing`() {
        assertFailsWith<IllegalStateException> {
            ReviewConfiguration().reviewResultTarget(StandardEnvironment())
        }
    }

    @Test
    fun `fails when review properties are missing`() {
        assertFailsWith<IllegalStateException> {
            ReviewConfiguration().review(StandardEnvironment())
        }
    }
}
