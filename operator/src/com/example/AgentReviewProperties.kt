package com.example

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.validation.annotation.Validated

@Component
@Validated
@ConfigurationProperties("agent-review")
data class AgentReviewProperties(
    @field:NotBlank var image: String = "",
)
