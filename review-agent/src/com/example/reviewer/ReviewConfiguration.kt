package com.example.reviewer

import com.example.Review
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

@Configuration(proxyBeanMethods = false)
class ReviewConfiguration {
    @Bean
    fun review(environment: Environment): Review =
        Binder.get(environment).bind("review", Bindable.of(Review::class.java)).orElseThrow {
            IllegalStateException("Missing required review configuration")
        }
}
