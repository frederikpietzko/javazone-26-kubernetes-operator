package com.example

import io.javaoperatorsdk.operator.Operator
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

@TestConfiguration(proxyBeanMethods = false)
class TestOperatorConfiguration {
    @Bean
    @Primary
    fun operator(): Operator = Operator()
}
