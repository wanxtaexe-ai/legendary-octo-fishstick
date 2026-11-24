package com.animeext.downloader

import com.animeext.downloader.resolver.AdFilterPolicy
import com.animeext.downloader.resolver.DownloadAndSchemeGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadAndSchemeGuardTest {

    @Test
    fun blocksApkExecutablesAndUnsafeSchemes() {
        assertEquals(
            DownloadAndSchemeGuard.UrlSafety.BLOCKED_UNSAFE_SCHEME,
            DownloadAndSchemeGuard.inspectUrl("intent://scan/#Intent;scheme=zxing;end")
        )
        assertEquals(
            DownloadAndSchemeGuard.UrlSafety.BLOCKED_UNSAFE_SCHEME,
            DownloadAndSchemeGuard.inspectUrl("market://details?id=com.scam.app")
        )
        assertEquals(
            DownloadAndSchemeGuard.UrlSafety.BLOCKED_UNSAFE_SCHEME,
            DownloadAndSchemeGuard.inspectUrl("tg://resolve?domain=spam")
        )
        assertEquals(
            DownloadAndSchemeGuard.UrlSafety.BLOCKED_EXECUTABLE_OR_APK,
            DownloadAndSchemeGuard.inspectUrl("https://evil.example.com/update.apk?v=1")
        )
        assertEquals(
            DownloadAndSchemeGuard.UrlSafety.BLOCKED_EXECUTABLE_OR_APK,
            DownloadAndSchemeGuard.inspectUrl("https://evil.example.com/dl?filename=installer.xapk")
        )
        assertEquals(
            DownloadAndSchemeGuard.UrlSafety.BLOCKED_EXECUTABLE_OR_APK,
            DownloadAndSchemeGuard.inspectUrl("https://evil.example.com/setup.exe")
        )
        assertTrue(DownloadAndSchemeGuard.isBlockedMimeType("application/vnd.android.package-archive"))
    }

    @Test
    fun allowsSafeVideoAndZipTargets() {
        assertEquals(
            DownloadAndSchemeGuard.UrlSafety.SAFE_HTTP_OR_HTTPS,
            DownloadAndSchemeGuard.inspectUrl("https://pixeldrain.com/api/file/abc123")
        )
        assertTrue(DownloadAndSchemeGuard.isDirectDownloadOrMediaTarget("https://cdn.example.com/ep01.mp4"))
        assertTrue(DownloadAndSchemeGuard.isDirectDownloadOrMediaTarget("https://cdn.example.com/batch.zip"))
        assertTrue(DownloadAndSchemeGuard.isKnownCloudFileHost("https://www.mirrored.to/files/F2A10447/"))
        assertFalse(DownloadAndSchemeGuard.isBlockedMimeType("video/mp4"))
    }

    @Test
    fun adFilterBlocksAdNetworksButKeepsTurnstileAndFirstPartyApis() {
        assertEquals(
            AdFilterPolicy.SubresourceDecision.BLOCK_AD,
            AdFilterPolicy.decideSubresource("https://cdn.popads.net/pop.js", "shortener.example")
        )
        assertEquals(
            AdFilterPolicy.SubresourceDecision.ALLOW,
            AdFilterPolicy.decideSubresource("https://challenges.cloudflare.com/turnstile/v0/api.js", "shortener.example")
        )
        assertEquals(
            AdFilterPolicy.SubresourceDecision.ALLOW,
            AdFilterPolicy.decideSubresource("https://shortener.example/links/go", "shortener.example")
        )
        assertEquals(
            AdFilterPolicy.NavigationDecision.BLOCK_UNSAFE_OR_EXECUTABLE,
            AdFilterPolicy.decideMainFrameNavigation("https://shortener.example", "intent://foo", hasUserGesture = true, isRedirect = false)
        )
    }
}
