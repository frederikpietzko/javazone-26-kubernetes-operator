package com.example.reviewer

import com.example.ReviewComment
import com.example.ReviewResultSpec

fun ReviewResult.toSpec(): ReviewResultSpec =
    ReviewResultSpec().also { spec ->
        spec.comments = comments.map { resultComment ->
            ReviewComment().also { comment ->
                comment.file = resultComment.file
                comment.lines = resultComment.lines
                comment.comment = resultComment.comment
            }
        }
    }
