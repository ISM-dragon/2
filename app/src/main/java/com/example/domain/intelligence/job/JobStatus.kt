package com.example.domain.intelligence.job

enum class JobStatus {
    QUEUED,
    FETCHING,
    PARSING,
    NORMALIZING,
    ENRICHING,
    ANALYZING,
    COMPLETED,
    FAILED
}
