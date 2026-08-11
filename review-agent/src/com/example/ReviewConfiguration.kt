package com.example

import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

data class ReviewResultTarget(
    val namespace: String,
    val name: String,
)

@Configuration(proxyBeanMethods = false)
class ReviewConfiguration {
    @Bean
    fun review(environment: Environment): Review =
        Binder.get(environment)
            .bind("review", Bindable.of(Review::class.java))
            .orElseThrow {
                IllegalStateException("Missing required review configuration")
            }

    @Bean
    fun reviewResultTarget(environment: Environment): ReviewResultTarget =
        Binder.get(environment)
            .bind("review.kubernetes", Bindable.of(ReviewResultTarget::class.java))
            .orElseThrow {
                IllegalStateException("Missing required review Kubernetes configuration")
            }
}
