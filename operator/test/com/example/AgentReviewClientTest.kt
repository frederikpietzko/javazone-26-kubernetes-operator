package com.example

import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentReviewClientTest {
    @Test
    fun `matching resources are reusable without create or update`() {
        val desired = AgentReviewResourceFactory.create(request(), "review-agent:1")
        assertTrue(
            AgentReviewResourceMatcher.configMapMatches(
                ConfigMapBuilder(desired.configMap).build(),
                desired.configMap,
            )
        )
        assertTrue(
            AgentReviewResourceMatcher.jobMatches(
                JobBuilder(desired.job).build(),
                desired.job,
            )
        )
    }

    @Test
    fun `config map data and job image drift conflict`() {
        val desired = AgentReviewResourceFactory.create(request(), "review-agent:1")
        val changedConfigMap =
            ConfigMapBuilder(desired.configMap).addToData("review.yaml", "drift").build()
        val changedJob =
            JobBuilder(desired.job)
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

        assertFalse(
            AgentReviewResourceMatcher.configMapMatches(changedConfigMap, desired.configMap)
        )
        assertFalse(AgentReviewResourceMatcher.jobMatches(changedJob, desired.job))
    }

    @Test
    fun `owner UID drift conflicts`() {
        val desired = AgentReviewResourceFactory.create(request(), "review-agent:1")
        val changed = ConfigMapBuilder(desired.configMap).build()
        changed.metadata.ownerReferences[0].uid = "different-uid"

        assertFalse(AgentReviewResourceMatcher.configMapMatches(changed, desired.configMap))
    }
}
