# Loopkeep: developer notes

## Architecture

The folder is the product; the app is a viewer and a downloader that writes into it.

```
TikTok link ─┬─ share sheet (ShareActivity)
             ├─ export import (core/ExportParser)          ┌── notes/<name>.md
             └─ paste / retry                              │
                    │                                      ├── media/<id>.mp4 | .jpg | _N.jpg | .m4a
                    ▼                                      │
           Room queue table ──► QueueWorker (WorkManager) ──► Processor ──► VaultStorage (SAF)
                                                           │                     │
                                                           │                     ▼
                                                           └──────────────► Indexer ──► Room index + FTS4
                                                                                             │
                                                         Compose UI (grid, feed, queue) ◄────┘
```

| Package | What it does | Android-free? |
|---|---|---|
| `core/` | URL/ID resolution, export parser, frontmatter editor, note model and writer, filenames, tag suggestions, yt-dlp/page/oEmbed parsers, queue policy | Yes, fully unit-tested |
| `storage/VaultStorage` | SAF access via raw `DocumentsContract` queries (one query lists a folder with name/size/mtime) | No |
| `data/` | Room: `videos` index (+ `videos_fts`), `queue`; dynamic library query; preferences | No |
| `index/Indexer` | Incremental rescan (mtime + size), full rescan, single-note reindex | No |
| `pipeline/` | `Processor` (one item end-to-end), `QueueWorker`, `YtDlp` wrapper, `Http`, `QueueRepository` | No |
| `ui/` | Compose screens; `PlayerPool` (3 ExoPlayers for previous/current/next) | No |

### Processing one item (`pipeline/Processor`)
1. Short link → follow redirects until a URL contains an ID. Everything is fetched through `https://www.tiktok.com/@<handle>/video/<id>` (empty handle works with yt-dlp), **also for photo posts**: yt-dlp rejects `/photo/` URLs and TikTok's `/photo/` page carries no post data, while the `/video/` URL gives yt-dlp metadata + audio and a page with the images. The note keeps the real `/photo/` link.
2. Skip if a `full` note already exists (unless it's a user retry).
3. yt-dlp: best single-file format, H.264 preferred (`-f "b[vcodec!=none]/b" -S vcodec:h264,res:1080`), plus `--write-info-json`. No ffmpeg needed.
4. If there is no video (failure, or a slideshow), fetch the post page and parse TikTok's embedded state JSON (`__UNIVERSAL_DATA_FOR_REHYDRATION__`): caption, author, date, covers, slideshow images, and `statusCode`.
5. Only if neither yt-dlp nor the page gave anything: try oEmbed.
6. Cover: TikTok's own (yt-dlp `thumbnails`, then page covers, then oEmbed), re-encoded to JPEG. Only if all fail: a frame from the video.
7. Media is copied to `media/` via `<name>.part` then renamed; the note is created, or merged into an existing one; the note is re-indexed.

Outcome per item: `full`, `text-only`, `unavailable`, retry later (network/storage), or failed after 6 attempts.

### Note editing rules (`core/Frontmatter`, `core/NoteDocument`)
- The frontmatter editor is line-preserving: only keys the app explicitly sets are rewritten; everything else (unknown keys, comments, nesting, odd formatting) stays byte-for-byte, in order. Unchanged files round-trip byte-exactly, including CRLF and BOM.
- Keys the app manages: `tiktok_id, source_url, author, author_handle, posted, saved, tags, status, type, duration, video, cover, audio, images`. On a retry merge, `author`, `author_handle`, `posted`, `source_url` only fill gaps (your edits win); `tags` are unioned.
- The body is `[embeds] [caption] ## Notes [your notes] [anything else]`. The app only rewrites the leading `![[…]]` embed block it created and the content of `## Notes`. The caption is never rewritten.
- The app re-reads the file immediately before writing, so edits made in Obsidian a moment ago are kept.

## Decisions & assumptions

**Naming**
- App name **Loopkeep**, package **`io.github.audxius.loopkeep`**. Debug builds use `io.github.audxius.loopkeep.debug`, so they install side by side with releases.

**Stack and versions** (checked against Maven Central / Google Maven on 2026-10-29)
- AGP 9.5.0 with built-in Kotlin, Kotlin 2.4.21, KSP 2.3.12, Gradle 9.8.2, JDK 17.
- `compileSdk 37` is required by core-ktx 1.19.1. `targetSdk` stays at **36**: I couldn't test Android 17 behaviour changes. Lint flags this (`OldTargetApi`) on purpose.
- Compose BOM 2026.09.00, Room 2.8.5, WorkManager 2.12.0, Media3 1.11.1, Coil 3.6.3, kotlinx-serialization-json 1.12.0.
- **youtubedl-android 0.18.1** (`io.github.junkfood02.youtubedl-android`). Its last release was Nov 2025; it isn't archived, and Seal (actively maintained) still depends on it. It's the only practical on-device yt-dlp for Android. Library staleness matters little, because the yt-dlp script itself is updated at runtime from GitHub. I checked that the bundled Python is 3.12, which satisfies current yt-dlp's `requires-python >= 3.11`. On first run on the emulator it auto-updated to yt-dlp 2026.10.26.
- No navigation library (a 4-screen back stack in the ViewModel), no DI framework, SharedPreferences rather than DataStore. Fewer moving parts.
- Kotlin/JVM `org.json` is not used in core (Android's stub throws in unit tests); core uses kotlinx-serialization's `JsonElement`.

**APK**
- ABIs: **arm64-v8a and x86_64 only** (x86_64 for emulators). The bundled Python runtime is ~14 MB per ABI; a single universal APK with all four ABIs would be much larger. 32-bit-only phones are not supported. APK ≈ 58 MB.
- No ffmpeg module: TikTok offers progressive MP4s, so nothing needs merging.
- `allowBackup=false`: the folder is the backup; the index is rebuildable.

**Data and behaviour**
- **oEmbed is degraded.** Observed live on 2026-10-29: `https://www.tiktok.com/oembed` now returns only an iframe player, with no `title`, `author_name` or `thumbnail_url`, and HTTP 200 even for deleted posts. So the text-only fallback reads the post page's embedded JSON instead; oEmbed stays as a last resort in case the old fields come back.
- **"Unavailable" is decided only by TikTok's page `statusCode`** (0 = OK, 10204 = doesn't exist, etc.). yt-dlp's error text is unreliable: for a deleted post it reported "Your IP address is blocked from accessing this post".
- A page with no post data never counts as a result (v0.1.1 wrote empty "text-only" notes for photo posts because of this). On start, the app re-queues any text-only note that has neither caption nor cover, once.
- **Delete** (feed trash button, or long-press in the grid) removes the note, every media file it references, its index row and its queue history. Re-importing the export would download it again.
- If nothing at all can be fetched (network, captcha page), the item is retried with backoff (1 min doubling, capped at 6 h, ±20 % jitter) and marked **failed** after 6 attempts **without writing a note**. It shows in the queue with the error and can be retried. I didn't want to write misleading notes.
- Queue: one item at a time, 2.5–6 s random pause between items, WorkManager with a network constraint, foreground notification (dataSync). Interrupted items go back to pending. Below 300 MB free (app cache), downloads pause 30 min.
- yt-dlp auto-updates at most every 3 days at the start of a batch (switch in Settings). There's also a manual "Update yt-dlp now".
- **Tags:** new notes get up to 6 tags from the caption's hashtags, lower-cased, minus engagement noise (`fyp`, `foryou…`, `viral`, …) and all-digit tags. The edit sheet suggests more (hashtags plus existing library tags that appear in the caption).
- **Filenames:** `handle - first 7 words of caption - last 6 digits of id.md`. Hashtags and mentions are skipped unless the caption is only hashtags. Letters of any script are kept; emoji, punctuation and ``\/:*?"<>|#^[]`` are dropped; the curly apostrophe becomes `'`. Capped at 120 bytes. With no author or caption: `tiktok - 123456.md`. Never renamed after creation; collisions get ` (2)`.
- `saved:` is the export date if known, else the date it was queued (share/paste).
- `type:` is written only for slideshows (`type: slideshow`, with `images:` and `audio:` when present). Videos omit it; absent means video.
- Cover files are always JPEG (`<id>.jpg`): WebP/HEIC covers are re-encoded. **Fallback:** if no TikTok cover can be fetched, a frame from the video is used. The note doesn't record which source was used.
- Importing: favourites ("saved") are queued by default; liked videos are an opt-in checkbox. Links from watch history, your own posts, and so on are counted and ignored. Dedupe is by video ID, then by URL for unresolved short links. A video already saved `full` is skipped; text-only/unavailable notes are not re-queued unless you press Retry.
- The feed freezes its order when opened, so new downloads or edits don't make the pager jump. Shuffle is a stable pseudo-random order per shuffle seed.
- Release `versionCode` = `major*1_000_000 + minor*1_000 + patch` from the tag (`v1.2.3` → `1002003`). Minor and patch must stay below 1000; the workflow and `release.sh` enforce this.
- The release asset is always named `loopkeep.apk`.
- Release keystore is PKCS12, so the store and key share one password.
- Room uses destructive migration: the index rebuilds from the folder, and only the queue would be lost.

## How to update yt-dlp

- In the app: **Settings → Downloader → Update yt-dlp now**. Auto-update (every 3 days) is on by default.
- Then **Queue → Retry failed**, and for text-only items, open them and press **Retry download**.
- To update the youtubedl-android library itself (new bundled Python, etc.), bump `youtubedl` in `gradle/libs.versions.toml` and cut a release.

## Known issues / limitations

- **Short links (`vm.tiktok.com/…`) are unverified live.** The redirect follower is generic; an expired short link redirects to the TikTok home page and ends as "Not a TikTok post link".
- **TikTok export format is unverified.** No real export was available (`samples/` was empty). The parser is schema-agnostic and tested on synthetic JSON/TXT/HTML/ZIP shaped like the publicly described export.
- The page fallback can be defeated by TikTok serving a captcha or a region block. Those items retry and eventually fail.
- Android 15+ limits `dataSync` foreground services to about 6 h per day. Very large imports pause and continue later (WorkManager reschedules).
- A note moved out of `notes/` disappears from the app (subfolders aren't scanned). Renaming a note in Obsidian is fine.
- Renaming or deleting a media file breaks the link; the feed then shows "Video file missing".
- If two notes share a `tiktok_id`, only the first one found is indexed.
- In light theme, status bar icons can be low-contrast on the setup screen.
- Verified on an Android 15 (API 35) x86_64 emulator only; not on a physical phone.
