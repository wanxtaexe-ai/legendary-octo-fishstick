rootProject.name = "CloudstreamPlugins"

// Exclude non-CloudStream helper/test directories so only .cs3 plugin modules are built.
val disabled = listOf("android-extension", "anime-downloader-app", "cloudstream-common", "js-extension", "yumeiro-adchain", "gradle", "node_modules")

File(rootDir, ".").eachDir { dir ->
    if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
        include(dir.name)
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}
