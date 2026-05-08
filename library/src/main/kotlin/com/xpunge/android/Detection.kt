package com.xpunge.android

data class Detection(
    val label: String,
    val confidence: Double,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)
