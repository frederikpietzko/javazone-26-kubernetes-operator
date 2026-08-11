package com.example

import org.springframework.beans.factory.BeanRegistrarDsl
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

@SpringBootApplication @Import(Reviewer::class) class Application

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}

data class ReviewResult(
    val file: String = "",
    val comments: List<ReviewCommentResult> = emptyList(),
)

data class ReviewCommentResult(
    val lines: List<Int> = emptyList(),
    val comment: String = "",
)

class Reviewer :
    BeanRegistrarDsl({
        registerBean<ReviewCommandLineRunner>()
    })
