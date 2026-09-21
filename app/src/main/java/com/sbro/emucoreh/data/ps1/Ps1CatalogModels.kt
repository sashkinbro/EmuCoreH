package com.sbro.emucoreh.data.ps1

data class Ps1CatalogSummary(
    val igdbId: Long,
    val name: String,
    val normalizedName: String,
    val storyline: String?,
    val summary: String?,
    val year: Int?,
    val rating: Double?,
    val coverUrl: String?,
    val heroUrl: String?,
    val genres: List<String> = emptyList(),
    val primarySerial: String? = null
)

data class Ps1CatalogDetails(
    val igdbId: Long,
    val name: String,
    val normalizedName: String,
    val year: Int?,
    val rating: Double?,
    val storyline: String?,
    val summary: String?,
    val genres: List<String>,
    val screenshots: List<String>,
    val videos: List<String>,
    val coverUrl: String?,
    val heroUrl: String?,
    val primarySerial: String? = null
)
