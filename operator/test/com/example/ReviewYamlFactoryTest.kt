package com.example

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import tools.jackson.dataformat.yaml.YAMLMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ReviewYamlFactoryTest {
    @Test
    fun `serializes review configuration under review root`() {
        val request = AgentReviewRequestCR().apply {
            metadata = ObjectMetaBuilder()
                .withName("request-42")
                .withNamespace("reviews")
                .withUid("uid-42")
                .build()
            spec = AgentReviewRequestSpec().apply {
                repository = AgentReviewRepository().apply {
                    url = "https://github.com/example/repository.git"
                }
                pr = "42"
            }
        }

        val tree = YAMLMapper.builder().build().readTree(
            ReviewYamlFactory.create(request, "agent-review-request-42"),
        )

        assertEquals("https://github.com/example/repository.git", tree.at("/review/repository/url").asText())
        assertEquals("42", tree.at("/review/pr").asText())
        assertEquals("reviews", tree.at("/review/kubernetes/namespace").asText())
        assertEquals("agent-review-request-42", tree.at("/review/kubernetes/name").asText())
        assertEquals("uid-42", tree.at("/review/kubernetes/ownerReference/uid").asText())
        assertFalse(tree.at("/review/kubernetes/ownerReference/blockOwnerDeletion").asBoolean())
    }
}
