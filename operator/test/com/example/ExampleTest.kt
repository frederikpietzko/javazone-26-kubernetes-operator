package com.example

import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@SpringBootTest
class ExampleTest {
    @Autowired
    lateinit var properties: AgentReviewProperties

    @Test
    fun `context binds review agent image`() {
        assertEquals("review-agent:latest", properties.image)
    }

    @Test
    fun `empty image fails configuration properties validation`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration::class.java))
            .withUserConfiguration(AgentReviewProperties::class.java)
            .withPropertyValues("agent-review.image=")
            .run { context ->
                assertNotNull(context.startupFailure)
            }
    }
}
