package com.example

class ReviewWorkflow
private constructor(
    private val publisher: ReviewResultPublisher,
    private val review: () -> ReviewResult,
) {
    companion object {
        operator fun invoke(
            publisher: ReviewResultPublisher,
            review: () -> ReviewResult,
        ) = ReviewWorkflow(publisher, review).run()
    }

    private fun run() {
        try {
            publisher.start()
            publisher.complete(review())
        } catch (exception: Exception) {
            try {
                publisher.fail(exception)
            } catch (secondary: Exception) {
                System.err.println("Could not persist failed review status: ${secondary.message}")
            }
            throw exception
        }
    }
}
