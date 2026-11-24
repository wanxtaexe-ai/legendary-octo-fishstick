version = 1

cloudstream {
    description = "Hindi Subbed Anime from Hindi Subbed Academy (hindisubbedacademy.com)"
    authors = listOf("wanxtaexe-ai")
    status = 1
    tvTypes = listOf("Anime")
    language = "hi"
    iconUrl = "https://www.google.com/s2/favicons?domain=hindisubbedacademy.com&sz=128"
}

android {
    sourceSets["main"].kotlin.srcDir("../cloudstream-common/src/main/kotlin")
}
