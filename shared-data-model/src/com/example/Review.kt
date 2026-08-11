package com.example

data class Repository(
    val url: String,
)

data class Review(
    val repository: Repository,
    val pr: String,
    val kubernetes: Kubernetes,
) {
    data class Kubernetes(
        val namespace: String,
        val name: String,
    )
}
