/*
 * Yumeiro AdChain Drop-in Integration
 * Matches Yumeiro's BrowserCheckHost design language and safely attaches the live WebView
 * session when a human verification challenge (Turnstile / reCAPTCHA / hCaptcha) is required.
 */
package dev.asfakul.yumeiro.ui.screens.online

import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.asfakul.yumeiro.adchain.AdFilterPolicy
import dev.asfakul.yumeiro.adchain.HumanChallengeKind
import dev.asfakul.yumeiro.ui.theme.SpotifyGreen
import dev.asfakul.yumeiro.ui.theme.TextPrimary
import dev.asfakul.yumeiro.ui.theme.TextSecondary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AdChainGateSession(
    val webView: WebView,
    val host: String,
    val challengeKind: HumanChallengeKind?,
    val done: CompletableDeferred<Boolean>,
)

object AdChainGateCoordinator {
    private val _pending = MutableStateFlow<AdChainGateSession?>(null)
    val pending: StateFlow<AdChainGateSession?> = _pending.asStateFlow()

    suspend fun present(webView: WebView, gateUrl: String, kind: HumanChallengeKind?): Boolean {
        _pending.value?.done?.complete(false)
        val host = AdFilterPolicy.hostOf(gateUrl) ?: "site"
        val deferred = CompletableDeferred<Boolean>()
        _pending.value = AdChainGateSession(webView, host, kind, deferred)
        return try {
            deferred.await()
        } finally {
            if (_pending.value?.done === deferred) {
                _pending.value = null
            }
        }
    }

    fun finish() {
        _pending.value?.done?.complete(true)
        _pending.value = null
    }

    fun cancel() {
        _pending.value?.done?.complete(false)
        _pending.value = null
    }
}

/**
 * Minimal-interruption host for ad-chain human verification (Turnstile / reCAPTCHA / hCaptcha).
 * Mount once next to [BrowserCheckHost] in MTPlayerApp.
 */
@Composable
fun AdChainGateHost() {
    val session by AdChainGateCoordinator.pending.collectAsState()
    val s = session ?: return

    DisposableEffect(s) {
        onDispose {
            runCatching { (s.webView.parent as? ViewGroup)?.removeView(s.webView) }
        }
    }

    Dialog(
        onDismissRequest = { AdChainGateCoordinator.cancel() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        FullScreenDialogBars()
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .testTag("adchain_gate"),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.VerifiedUser, null, tint = SpotifyGreen, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Complete check on ${s.host}",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 1,
                    )
                    val subtitle = when (s.challengeKind) {
                        HumanChallengeKind.CLOUDFLARE_TURNSTILE -> "Tap the Turnstile box — Yumeiro will resume automatically."
                        HumanChallengeKind.RECAPTCHA -> "Solve the reCAPTCHA, then tap Continue if it doesn't auto-advance."
                        HumanChallengeKind.HCAPTCHA -> "Solve the hCaptcha, then tap Continue if it doesn't auto-advance."
                        else -> "Solve the verification check, then tap Continue."
                    }
                    Text(subtitle, color = TextSecondary, fontSize = 11.sp, lineHeight = 14.sp)
                }
                TextButton(onClick = { AdChainGateCoordinator.finish() }) {
                    Text("Continue", color = SpotifyGreen, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = { AdChainGateCoordinator.cancel() }) {
                    Icon(Icons.Default.Close, "Cancel", tint = TextPrimary)
                }
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    runCatching { (s.webView.parent as? ViewGroup)?.removeView(s.webView) }
                    s.webView
                },
            )
        }
    }
}
