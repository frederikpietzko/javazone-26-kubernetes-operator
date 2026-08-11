package com.example

import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.stereotype.Component
import kotlin.reflect.full.findAnnotation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

@SpringBootTest
@Import(TestOperatorConfiguration::class)
class AgentReviewRequestReconcilerTest {
    @Autowired
    lateinit var applicationContext: ApplicationContext

    @Test
    fun `registers AgentReviewRequest controller`() {
        assertNotNull(applicationContext.getBean(AgentReviewRequestReconciler::class.java))
    }

    @Test
    fun `new request produces EnsureResources decision`() {
        val reconciler = AgentReviewRequestReconciler(FakeGateway(), AgentReviewProperties("review-agent:1"))
        val decision = reconciler.reconcileOnce(
            request(),
            ObservedAgentReviewResources(null, null, null, null, null, null),
        )
        assertIs<LifecycleDecision.EnsureResources>(decision)
    }

    @Test
    fun `terminal result produces Successful decision without resource creation`() {
        val result = ReviewResultCR().apply {
            status = ReviewResultStatus().also { it.status = "Completed" }
        }
        val reconciler = AgentReviewRequestReconciler(FakeGateway(), AgentReviewProperties("review-agent:1"))
        val decision = reconciler.reconcileOnce(request(), observedWithCompletedJob(result))
        assertEquals("Successful", assertIs<LifecycleDecision.Successful>(decision).status.phase)
    }

    @Test
    fun `reconciler is Spring discoverable`() {
        assertNotNull(AgentReviewRequestReconciler::class.findAnnotation<Component>())
        assertEquals(
            "agent-review-request",
            AgentReviewRequestReconciler::class.findAnnotation<ControllerConfiguration>()!!.name,
        )
    }
}

private class FakeGateway : AgentReviewResourceGateway {
    var created: AgentReviewResources? = null

    override fun observe(namespace: String, baseName: String): ObservedAgentReviewResources =
        ObservedAgentReviewResources(null, null, null, null, null, null)

    override fun createMissing(resources: AgentReviewResources) {
        created = resources
    }
}
