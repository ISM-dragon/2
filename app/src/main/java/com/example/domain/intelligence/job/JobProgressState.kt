package com.example.domain.intelligence.job

data class JobProgressState(
    val jobId: String,
    val url: String,
    val source: String = "Web",
    val status: JobStatus,
    val progressPercent: Float = 0f,
    val currentStepDescription: String = "",
    val propertyId: String? = null,
    val errorMessage: String? = null,
    val errorCode: String? = null
)
