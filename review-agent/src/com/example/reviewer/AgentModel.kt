package com.example.reviewer

data class ReviewResult(val comments: List<ReviewCommentResult> = emptyList())

data class ReviewCommentResult(
    val file: String = "",
    val lines: List<Int> = emptyList(),
    val comment: String = "",
)
