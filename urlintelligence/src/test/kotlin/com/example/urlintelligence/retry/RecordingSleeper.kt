package com.example.urlintelligence.retry

/** Sleeper that records delays instead of waiting. */
class RecordingSleeper : Sleeper {
    val delays = mutableListOf<Long>()

    override suspend fun delay(millis: Long) {
        delays += millis
    }
}
