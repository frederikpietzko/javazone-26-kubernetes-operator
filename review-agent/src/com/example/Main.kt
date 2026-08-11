package com.example

import com.example.reviewer.Reviewer
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

@SpringBootApplication @Import(Reviewer::class) class Application

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
