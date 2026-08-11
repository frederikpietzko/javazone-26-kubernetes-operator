package com.example

class ReviewWorkflow(
    private val publisher: ReviewResultPublisher,
    private val review: () -> ReviewResult,
) {
    fun run() {
        try {
            publisher.start()
            publisher.complete(review())
        } catch (exception: Exception) {
            try {
                publisher.fail(exception)
            } catch (secondary: Exception) {
                System.err.println(
                    "Could not persist failed review status: ${secondary.message}",
                )
            }
            throw exception
        }
    }
}
