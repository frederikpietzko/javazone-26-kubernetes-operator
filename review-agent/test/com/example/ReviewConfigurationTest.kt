package com.example

import com.example.reviewer.ReviewConfiguration
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReviewConfigurationTest {
    @Test
    fun `binds review properties into shared model`() {
        val environment =
            StandardEnvironment().apply {
                propertySources.addFirst(
                    MapPropertySource(
                        "test",
                        mapOf(
                            "review.repository.url" to "https://github.com/example/project.git",
                            "review.pr" to "42",
                            "review.kubernetes.namespace" to "reviews",
                            "review.kubernetes.name" to "review-result-42",
                        ),
                    )
                )
            }

        val review = ReviewConfiguration().review(environment)

        assertEquals("https://github.com/example/project.git", review.repository.url)
        assertEquals("42", review.pr)
        assertEquals("reviews", review.kubernetes.namespace)
        assertEquals("review-result-42", review.kubernetes.name)
    }

    @Test
    fun `fails when review Kubernetes properties are missing`() {
        val environment =
            StandardEnvironment().apply {
                propertySources.addFirst(
                    MapPropertySource(
                        "test",
                        mapOf(
                            "review.repository.url" to "https://github.com/example/project.git",
                            "review.pr" to "42",
                        ),
                    )
                )
            }

        assertFailsWith<BindException> {
            ReviewConfiguration().review(environment)
        }
    }

    @Test
    fun `fails when review properties are missing`() {
        assertFailsWith<IllegalStateException> {
            ReviewConfiguration().review(StandardEnvironment())
        }
    }
}
