package com.example

import org.springaicommunity.agent.tools.FileSystemTools
import org.springaicommunity.agent.tools.GlobTool
import org.springaicommunity.agent.tools.GrepTool
import org.springaicommunity.agent.tools.ShellTools
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor
import org.springframework.beans.factory.BeanRegistrarDsl
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

@SpringBootApplication @Import(Reviewer::class) class Application

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}

data class ReviewResult(
    val comments: List<ReviewCommentResult> = emptyList(),
)

data class ReviewCommentResult(
    val lines: List<Int> = emptyList(),
    val comment: String = "",
)

class Reviewer :
    BeanRegistrarDsl({
        registerBean {
            CommandLineRunner {
                val chatClientBuilder = bean<ChatClient.Builder>()
                val review = bean<Review>()
                val reviewResult =
                    chatClientBuilder
                        .defaultSystem {
                            it.text(
                                """
                                You are an adversarial code reviewer. You sole purpose is to find flaws in code.
                                You should look at the diff. If necessary you can clone the repository, but remember to clean it up afterwards.
                                """
                                    .trimIndent()
                            )
                        }
                        .defaultAdvisors(SimpleLoggerAdvisor())
                        .defaultTools(
                            ShellTools.builder().build(),
                            FileSystemTools.builder().build(),
                            GrepTool.builder().build(),
                            GlobTool.builder().build(),
                        )
                        .build()
                        .prompt()
                        .user {
                            it.text(
                                "Review the following code: ${review.repository.url} PR: ${review.pr}"
                            )
                        }
                        .call()
                        .entity(ReviewResult::class.java)
                requireNotNull(reviewResult) { "Did not receive a response from the chat client" }
            }
        }
    })
