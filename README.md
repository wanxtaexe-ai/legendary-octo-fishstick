# CloudStream 3 / Yumeiro Hindi Anime Extensions

A CloudStream 3 (`.cs3`) multi-module extension repository for five WordPress-based anime sites, built with shared semantic HTML parsing, remote `domains.json` configuration, and `WebViewResolver` / Pixeldrain / Gofile / embedded HLS stream extraction.

## Repository URL (CloudStream 3 / Yumeiro)

Paste this URL into **Extensions → `+` → Add repository** (after the GitHub Actions workflow runs on `main`):

```text
https://raw.githubusercontent.com/wanxtaexe-ai/legendary-octo-fishstick/builds/repo.json
```

## Plugin Modules (`.cs3`)

| Module | Provider Name | Site URL | Features |
| --- | --- | --- | --- |
| `LordsAnime` | `LordsAnime` | `https://www.lordsanime.com` | Semantic WP parser, Season/Episode ranges, `WebViewResolver` |
| `TpxSub` | `TpXAnime` | `https://www.tpxsub.com` | `1080p`/`720p`/`480p` tiers, Gofile/Pixeldrain, `WebViewResolver` |
| `YbxAnime` | `YBXAnime` | `https://ybxanime.com` | List/article semantic parsing, `YBX LINK` `WebViewResolver` |
| `HindiSubbedAcademy` | `HindiSubbedAcademy` | `https://hindisubbedacademy.com` | Kiranime `?s=` + `/search/?keyword=` fallback |
| `AnimeVilla` | `AnimeVilla` | `https://animevilla.org` | Kiranime search fallback + `/watch/` embedded `.m3u8`/`.mp4` extraction |

## Project Layout

* `repo.json` & `domains.json`: CloudStream repository manifest & remote domain config (`baseUrl` hot-updates without recompiling `.cs3` plugins).
* `cloudstream-common/`: Shared CloudStream 3 `MainAPI` base (`WordPressBaseProvider`), semantic Jsoup parser (`WordPressSemanticParser`), and remote `DomainConfigLoader`.
* `LordsAnime/`, `TpxSub/`, `YbxAnime/`, `HindiSubbedAcademy/`, `AnimeVilla/`: Individual CloudStream 3 plugin modules compiled into `.cs3` artifacts.
* `.github/workflows/build.yml`: Automated GitHub Actions CI that runs `./gradlew make makePluginsJson` on pushes to `main`/`master` and publishes `*.cs3`, `plugins.json`, and `repo.json` to the `builds` branch.
* `js-extension/` & `android-extension/`: Standalone JS/Node.js test runner + reusable Android library module.

## Automated Build & Tests

### CloudStream 3 Build (GitHub Actions / Local Android SDK + JDK 17)

```bash
./gradlew make makePluginsJson
```

### JavaScript Parser, HTTP & Resolver Tests

```bash
npm ci
npm test
```
