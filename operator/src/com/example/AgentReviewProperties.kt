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
    @field:NotBlank var openAiBaseUrl: String = "http://127.0.0.1:11434",
)
