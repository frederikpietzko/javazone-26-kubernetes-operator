package com.example

data class Repository(
    val url: String,
)

data class Review(
    val repository: Repository,
    val pr: String,
)
