version = 1

cloudstream {
    description = "Hindi Dubbed Anime & Embedded HLS Streams from AnimeVilla (animevilla.org)"
    authors = listOf("wanxtaexe-ai")
    status = 1
    tvTypes = listOf("Anime")
    language = "hi"
    iconUrl = "https://www.google.com/s2/favicons?domain=animevilla.org&sz=128"
}

android {
    sourceSets["main"].kotlin.srcDir("../cloudstream-common/src/main/kotlin")
}
