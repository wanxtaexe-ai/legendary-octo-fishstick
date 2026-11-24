# Plug-and-Play Drop-In for `siamform/yumeiro`

Zero new Gradle dependencies or `settings.gradle.kts` edits required — Yumeiro already ships `okhttp`, `kotlinx-coroutines`, `androidx.webkit`, `jsoup`, and Compose Material 3.

## Option A: One-command attach

```bash
bash install-into-yumeiro.sh /path/to/your/yumeiro/checkout
```

## Option B: Manual attach (takes ~2 minutes)

1. Copy the pure-Kotlin + Android WebView files into `app/src/main/java/dev/asfakul/yumeiro/adchain/`:
   - All `*.kt` files from `yumeiro-adchain/src/main/kotlin/dev/asfakul/yumeiro/adchain/`
   - `yumeiro-adchain/android-integration/AdChainWebViewRunner.kt` → `.../adchain/android/`
2. Copy the 2 Yumeiro bridge files from `yumeiro-dropin/app/src/main/java/dev/asfakul/yumeiro/`:
   - `ui/screens/online/AdChainGateHost.kt`
   - `stream/YumeiroAdChainBridge.kt`
3. Apply `yumeiro-integration.patch` (adds **4 lines total** across `StreamResolver.kt` and `MTPlayerApp.kt`):
   - In `StreamResolver.resolve(...)` right after `resolveBlocking(...)`:
     ```kotlin
     if (r is Resolution.NeedsBrowser || r is Resolution.Failed) {
         YumeiroAdChainBridge.tryResolveIfAdChain(context, request, r)?.let { return@withContext it }
     }
     ```
   - In `MTPlayerApp.kt` right next to `BrowserCheckHost()`:
     ```kotlin
     dev.asfakul.yumeiro.ui.screens.online.AdChainGateHost()
     ```
