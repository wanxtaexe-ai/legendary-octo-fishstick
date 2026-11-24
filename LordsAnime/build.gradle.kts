version = 1

cloudstream {
    description = "Hindi Subbed Anime from Lord's Anime (lordsanime.com) with remote domains.json support"
    authors = listOf("wanxtaexe-ai")
    status = 1
    tvTypes = listOf("Anime")
    language = "hi"
    iconUrl = "https://www.google.com/s2/favicons?domain=www.lordsanime.com&sz=128"
}

android {
    sourceSets["main"].kotlin.srcDir("../cloudstream-common/src/main/kotlin")
}
