package com.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class AgentReviewLifecycleTest {
    @Test
    fun `new request asks for all dependent resources and InProgress status`() {
        val decision = AgentReviewLifecycle.decide(request(), ObservedAgentReviewResources(null, null, null, null, null, null))
        val ensure = assertIs<LifecycleDecision.EnsureResources>(decision)
        assertEquals("InProgress", ensure.status.phase)
        assertEquals("agent-review-request-42", ensure.status.jobName)
        assertEquals("agent-review-request-42", ensure.status.configMapName)
        assertEquals("agent-review-request-42", ensure.status.reviewResultName)
        assertNull(ensure.status.message)
    }

    @Test
    fun `matching resources are reused and request remains InProgress`() {
        val decision = AgentReviewLifecycle.decide(request(), observedWithActiveJob())
        val wait = assertIs<LifecycleDecision.Wait>(decision)
        assertEquals("InProgress", wait.status.phase)
        assertEquals("agent-review-request-42", wait.status.jobName)
        assertNull(wait.status.message)
    }

    @Test
    fun `completed result makes request Successful`() {
        val result = ReviewResultCR().apply {
            status = ReviewResultStatus().also { it.status = "Completed" }
        }
        val decision = AgentReviewLifecycle.decide(request(), observedWithCompletedJob(result))
        val successful = assertIs<LifecycleDecision.Successful>(decision)
        assertEquals("Successful", successful.status.phase)
        assertEquals("agent-review-request-42", successful.status.reviewResultName)
        assertNull(successful.status.message)
    }

    @Test
    fun `in-progress result keeps request InProgress`() {
        val result = ReviewResultCR().apply {
            status = ReviewResultStatus().also { it.status = "InProgress" }
        }
        val decision = AgentReviewLifecycle.decide(request(), observedWithActiveJob(result))
        val wait = assertIs<LifecycleDecision.Wait>(decision)
        assertEquals("InProgress", wait.status.phase)
        assertNull(wait.status.message)
    }

    @Test
    fun `failed result makes request Error with result message`() {
        val result = ReviewResultCR().apply {
            status = ReviewResultStatus().also {
                it.status = "Failed"
                it.error = "model unavailable"
            }
        }
        val decision = AgentReviewLifecycle.decide(request(), observedWithActiveJob(result))
        val error = assertIs<LifecycleDecision.Error>(decision)
        assertEquals("Error", error.status.phase)
        assertEquals("model unavailable", error.status.message)
    }

    @Test
    fun `failed job makes request Error`() {
        val decision = AgentReviewLifecycle.decide(request(), observedWithFailedJob())
        val error = assertIs<LifecycleDecision.Error>(decision)
        assertEquals("Error", error.status.phase)
        assertEquals("review-agent Job failed", error.status.message)
    }

    @Test
    fun `successful job without result makes request Error`() {
        val decision = AgentReviewLifecycle.decide(request(), observedWithCompletedJob(null))
        val error = assertIs<LifecycleDecision.Error>(decision)
        assertEquals("Error", error.status.phase)
        assertEquals("review-agent Job completed without publishing a result", error.status.message)
    }

    @Test
    fun `missing owned resource after start makes request Error without rerun`() {
        val request = request().apply {
            status = AgentReviewRequestStatus().also { it.phase = "InProgress" }
        }
        val decision = AgentReviewLifecycle.decide(request, observedWithActiveJob().copy(configMap = null))
        val error = assertIs<LifecycleDecision.Error>(decision)
        assertEquals("Error", error.status.phase)
        assertEquals("owned review-agent resource disappeared after processing started", error.status.message)
    }

    @Test
    fun `missing job after all dependent resources were created makes request Error without rerun`() {
        val observed = observedWithActiveJob().copy(job = null)
        val decision = AgentReviewLifecycle.decide(request(), observed)
        val error = assertIs<LifecycleDecision.Error>(decision)
        assertEquals("Error", error.status.phase)
        assertEquals("review-agent Job disappeared after dependent resources were created", error.status.message)
    }

    @Test
    fun `terminal request is Noop`() {
        val request = request().apply {
            status = AgentReviewRequestStatus().also { it.phase = "Successful" }
        }
        assertIs<LifecycleDecision.Noop>(AgentReviewLifecycle.decide(request, observedWithActiveJob()))
    }
}
