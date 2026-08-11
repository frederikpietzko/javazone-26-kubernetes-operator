package com.example

interface ReviewResultPublisher {
    fun start()

    fun complete(result: ReviewResult)

    fun fail(exception: Exception)
}
