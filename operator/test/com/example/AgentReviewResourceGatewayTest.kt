package com.example

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.ObjectReferenceBuilder
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentReviewResourceGatewayTest {
    @Test
    fun `matching resources are reusable without create or update`() {
        val desired = AgentReviewResourceFactory.create(request(), "review-agent:1")
        assertTrue(AgentReviewResourceMatcher.configMapMatches(
            ConfigMapBuilder(desired.configMap).build(), desired.configMap,
        ))
        assertTrue(AgentReviewResourceMatcher.jobMatches(
            io.fabric8.kubernetes.api.model.batch.v1.JobBuilder(desired.job).build(), desired.job,
        ))
    }

    @Test
    fun `config map data and job image drift conflict`() {
        val desired = AgentReviewResourceFactory.create(request(), "review-agent:1")
        val changedConfigMap = ConfigMapBuilder(desired.configMap)
            .addToData("review.yaml", "drift")
            .build()
        val changedJob = io.fabric8.kubernetes.api.model.batch.v1.JobBuilder(desired.job)
            .editSpec()
            .editTemplate()
            .editSpec()
            .editContainer(0)
            .withImage("review-agent:2")
            .endContainer()
            .endSpec()
            .endTemplate()
            .endSpec()
            .build()

        assertFalse(AgentReviewResourceMatcher.configMapMatches(changedConfigMap, desired.configMap))
        assertFalse(AgentReviewResourceMatcher.jobMatches(changedJob, desired.job))
    }

    @Test
    fun `owner UID drift conflicts`() {
        val desired = AgentReviewResourceFactory.create(request(), "review-agent:1")
        val changed = ConfigMapBuilder(desired.configMap).build()
        changed.metadata.ownerReferences[0].uid = "different-uid"

        assertFalse(AgentReviewResourceMatcher.configMapMatches(changed, desired.configMap))
    }

    @Test
    fun `generated ServiceAccount secrets do not cause drift`() {
        val desired = AgentReviewResourceFactory.create(request(), "review-agent:1")
        val existing = io.fabric8.kubernetes.api.model.ServiceAccountBuilder(desired.serviceAccount)
            .withSecrets(ObjectReferenceBuilder().withName("generated-token").build())
            .build()

        assertTrue(AgentReviewResourceMatcher.serviceAccountMatches(existing, desired.serviceAccount))
    }
}
