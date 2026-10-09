
package com.rfidsoftwares.data.local.dao

/**
 * Room mapping row for grouped outbox counts.
 */
data class OutboxStateCount(
    val state: String,
    val count: Int,
)
