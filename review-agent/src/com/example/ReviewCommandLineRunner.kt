package com.example

import io.fabric8.kubernetes.client.KubernetesClientBuilder
import org.springaicommunity.agent.tools.FileSystemTools
import org.springaicommunity.agent.tools.GlobTool
import org.springaicommunity.agent.tools.GrepTool
import org.springaicommunity.agent.tools.ShellTools
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor
import org.springframework.boot.CommandLineRunner

class ReviewCommandLineRunner(
    private val chatClientBuilder: ChatClient.Builder,
    private val review: Review,
) : CommandLineRunner {
    companion object {
        private val SYSTEM_PROMPT =
            """
            You are an adversarial code reviewer. You sole purpose is to find flaws in code.
            You should look at the diff. If necessary you can clone the repository, but remember to clean it up afterwards.
            """
                .trimIndent()
    }

    override fun run(vararg args: String) {
        KubernetesClientBuilder().build().use { client ->
            KubernetesReviewResultPublisher(client, review.kubernetes).use { publisher ->
                ReviewWorkflow(publisher) {
                    val reviewResult =
                        chatClientBuilder
                            .defaultSystem(SYSTEM_PROMPT)
                            .defaultAdvisors(SimpleLoggerAdvisor())
                            .defaultTools(
                                ShellTools.builder().build(),
                                FileSystemTools.builder().build(),
                                GrepTool.builder().build(),
                                GlobTool.builder().build(),
                            )
                            .build()
                            .prompt()
                            .user(
                                "Review the following code: ${review.repository.url} PR: ${review.pr}"
                            )
                            .call()
                            .entity(ReviewResult::class.java)
                    requireNotNull(reviewResult) {
                        "Did not receive a response from the chat client"
                    }
                }
            }
        }
    }
}
