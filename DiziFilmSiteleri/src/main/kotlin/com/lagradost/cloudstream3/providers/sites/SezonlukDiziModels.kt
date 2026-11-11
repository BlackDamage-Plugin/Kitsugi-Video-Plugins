package com.lagradost.cloudstream3.providers

import com.fasterxml.jackson.annotation.JsonProperty


data class Kaynak(
    @JsonProperty("status") val status: String,
    @JsonProperty("data") val data: List<Veri>,
)

data class Veri(
    @JsonProperty("baslik") val baslik: String,
    @JsonProperty("id") val id: Int,
    @JsonProperty("kalite") val kalite: Int,
)

data class AspData(
    val alternatif : String,
    val embed : String
)

data class SearchRoot(
    @JsonProperty("status") val status: String?,
    @JsonProperty("results") val results: Map<String, SearchCategory>?
)

data class SearchCategory(
    @JsonProperty("name") val name: String?,
    @JsonProperty("results") val results: List<SearchItem>?
)

data class SearchItem(
    @JsonProperty("did") val did: Int?,
    @JsonProperty("title") val title: String?,
    @JsonProperty("description") val description: String?,
    @JsonProperty("url") val url: String?,
    @JsonProperty("image") val image: String?,
    @JsonProperty("imdb") val imdb: Double?
)