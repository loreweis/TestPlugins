package com.example

import com.lagradost.cloudstream3.*

class ExampleProvider : MainAPI() { // All providers must be an instance of MainAPI
    override var mainUrl = "https://example.com/" 
    override var name = "Test"
    override val supportedTypes = setOf(TvType.Anime)

    override var lang = "id"

    // Enable this when your provider has a main page
    override val hasMainPage = true

    override val mainPage = mainPageOf("test" to "Test")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val item = newAnimeSearchResponse("Test", mainUrl, TvType.Anime)
        return newHomePageResponse(request.name, listOf(item), hasNext = false)
    }

    // This function gets called when you search for something
    override suspend fun search(query: String): List<SearchResponse> {
        return listOf()
    }
}
