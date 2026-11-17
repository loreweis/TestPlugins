dependencies {
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
}

// Use an integer for version numbers
version = 22

cloudstream {
    // All of these properties are optional, you can safely remove any of them.

    description = "Test"
    authors = listOf("Cloudburst", "Luna72")

    /**
    * Status int as one of the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta-only
    **/
    status = 1 // Will be 3 if unspecified

    tvTypes = listOf("Anime")

    requiresResources = true
    language = "id"

    // Random CC logo I found
    iconUrl = "https://external-content.duckduckgo.com/ip3/cloudstream.miraheze.org.ico"
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}
