package com.example

fun ReviewResult.toSpec(): ReviewResultSpec =
    ReviewResultSpec().also { spec ->
        spec.comments = comments.map { resultComment ->
            ReviewComment().also { comment ->
                comment.lines = resultComment.lines
                comment.comment = resultComment.comment
            }
        }
    }
