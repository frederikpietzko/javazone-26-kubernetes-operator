package com.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class AgentReviewLifecycleTest {
    private val lifecycle = AgentReviewLifecycle(ResourceNameGenerator())

    @Test
    fun `new request asks for all dependent resources and InProgress status`() {
        val decision = lifecycle.decide(request(), desiredState(), ObservedAgentReviewResources(null, null, null))
        val ensure = assertIs<LifecycleDecision.EnsureResources>(decision)
        assertEquals("InProgress", ensure.status.phase)
        assertEquals("agent-review-request-42", ensure.status.jobName)
        assertEquals("agent-review-request-42", ensure.status.configMapName)
        assertEquals("agent-review-request-42", ensure.status.reviewResultName)
        assertEquals(JOB_CREATION_PENDING_MESSAGE, ensure.status.message)
    }

    @Test
    fun `matching resources are reused and request remains InProgress`() {
        val decision = lifecycle.decide(request(), desiredState(), observedWithActiveJob())
        val wait = assertIs<LifecycleDecision.Wait>(decision)
        assertEquals("InProgress", wait.status.phase)
        assertNull(wait.status.message)
    }

    @Test
    fun `completed result makes request Successful`() {
        val result = ReviewResultCR().apply {
            status = ReviewResultStatus().also { it.status = "Completed" }
        }
        val decision = lifecycle.decide(request(), desiredState(), observedWithCompletedJob(result))
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
        val decision = lifecycle.decide(request(), desiredState(), observedWithActiveJob(result))
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
        val decision = lifecycle.decide(request(), desiredState(), observedWithActiveJob(result))
        val error = assertIs<LifecycleDecision.Error>(decision)
        assertEquals("Error", error.status.phase)
        assertEquals("model unavailable", error.status.message)
    }

    @Test
    fun `failed job makes request Error`() {
        val decision = lifecycle.decide(request(), desiredState(), observedWithFailedJob())
        val error = assertIs<LifecycleDecision.Error>(decision)
        assertEquals("Error", error.status.phase)
        assertEquals("review-agent Job failed", error.status.message)
    }

    @Test
    fun `successful job without result makes request Error`() {
        val decision = lifecycle.decide(request(), desiredState(), observedWithCompletedJob(null))
        val error = assertIs<LifecycleDecision.Error>(decision)
        assertEquals("Error", error.status.phase)
        assertEquals("review-agent Job completed without publishing a result", error.status.message)
    }

    @Test
    fun `missing owned resource after start makes request Error without rerun`() {
        val request = request().apply {
            status = AgentReviewRequestStatus().also { it.phase = "InProgress" }
        }
        val decision = lifecycle.decide(request, desiredState(request), observedWithActiveJob().copy(configMap = null))
        val error = assertIs<LifecycleDecision.Error>(decision)
        assertEquals("Error", error.status.phase)
        assertEquals("owned review-agent resource disappeared after processing started", error.status.message)
    }

    @Test
    fun `missing job after all dependent resources were created makes request Error without rerun`() {
        val observed = observedWithActiveJob().copy(job = null)
        val request = request().apply {
            status = AgentReviewRequestStatus().also {
                it.phase = "InProgress"
                it.message = "review-agent Job running"
            }
        }
        val decision = lifecycle.decide(request, desiredState(request), observed)
        val error = assertIs<LifecycleDecision.Error>(decision)
        assertEquals("Error", error.status.phase)
        assertEquals("review-agent Job disappeared after dependent resources were created", error.status.message)
    }

    @Test
    fun `terminal successful request is Noop`() {
        val request = request().apply {
            status = AgentReviewRequestStatus().also { it.phase = "Successful" }
        }
        assertIs<LifecycleDecision.Noop>(lifecycle.decide(request, desiredState(request), observedWithActiveJob()))
    }

    @Test
    fun `terminal error request is Noop`() {
        val request = request().apply {
            status = AgentReviewRequestStatus().also { it.phase = "Error" }
        }
        assertIs<LifecycleDecision.Noop>(lifecycle.decide(request, desiredState(request), observedWithActiveJob()))
    }
}
