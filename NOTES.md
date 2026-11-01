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
- **Tags:** since v0.3 the app never adds tags by itself (hashtag tags were noise). Tags are yours; the edit sheet suggests your most-used ones. On first start of v0.3 the app removes the hashtag tags older versions wrote, but only from notes whose tags are *all* hashtags from the caption, so any note with a tag you added keeps all of its tags.
- **Filenames:** `handle - first 7 words of caption - last 6 digits of id.md`. Hashtags and mentions are skipped unless the caption is only hashtags. Letters of any script are kept; emoji, punctuation and ``\/:*?"<>|#^[]`` are dropped; the curly apostrophe becomes `'`. Capped at 120 bytes. With no author or caption: `tiktok - 123456.md`. Never renamed after creation; collisions get ` (2)`.
- `saved:` is the export date if known, else the date it was queued (share/paste).
- `type:` is written only for slideshows (`type: slideshow`, with `images:` and `audio:` when present). Videos omit it; absent means video.
- Cover files are always JPEG (`<id>.jpg`): WebP/HEIC covers are re-encoded. **Fallback:** if no TikTok cover can be fetched, a frame from the video is used. The note doesn't record which source was used.
- Export sections are classified by the outermost name that says something; a name that mentions both likes and favourites (newer exports group them as "Likes and Favorites") is skipped so the inner section ("Favorite Videos" vs "Like List") decides. Found by a real import in v0.4, where all favourites were counted as likes.
- Importing: favourites ("saved") are queued by default; liked videos are an opt-in checkbox. Links from watch history, your own posts, and so on are counted and ignored. Dedupe is by video ID, then by URL for unresolved short links. A video already saved `full` is skipped; text-only/unavailable notes are not re-queued unless you press Retry.
- The feed freezes its order when opened, so new downloads or edits don't make the pager jump. Shuffle is a stable pseudo-random order per shuffle seed.
- Release `versionCode` = `major*1_000_000 + minor*1_000 + patch` from the tag (`v1.2.3` → `1002003`). Minor and patch must stay below 1000; the workflow and `release.sh` enforce this.
- The release asset is always named `loopkeep.apk`.
- Release keystore is PKCS12, so the store and key share one password.
- Room uses destructive migration: the index rebuilds from the folder, and only the queue would be lost.

**Design (v0.2)**
- Dark-only. It's a video viewer first, and a single theme keeps the brand consistent. Own palette (coral → peach gradient, teal/gold/rose status colours) rather than Material You dynamic colour, which washed out the UI.
- Shared components in `ui/Components.kt`: `GradientButton`, `StatusPill`, `Avatar`, `StatTile`, `SectionCard`/`SettingRow`, `PlainTextField`. The latter makes the whole visible input area tappable (the earlier version only reacted on the first text line).
- Counters (saved / partial / gone / failed) sit at the top of the library; tapping one filters by it, and *Failed* opens the downloads screen.
- Screenshots in `fastlane/metadata/android/en-US/images` (the F-Droid/IzzyOnDroid layout) were taken on the emulator with public posts from @tiktok, @natgeo and @nasa, using a synthetic export.

**Own posts from the export, phone videos, gallery (v0.5)**
- TikTok gives no link for "Only me" posts. The export's own-posts section (`Video → Videos → VideoList`, entries with `Date` + `VideoLink`, as read by the open-source tiktok-json-extractor) has a **direct, expiring file link** per post, private ones included. Classified as `OWN` by exact section names (`Video`, `Videos`, `Posts`, `VideoList`…); the newer guessed shape (`Post → Posts` with a post `Link`, `Title`, `WhoCanView`) is handled too. Unverified against a real export.
- Own posts with a TikTok ID go through the normal pipeline and fall back to the direct link when TikTok refuses. Those without an ID are keyed `own-<yyyyMMddHHmmss>` (post time), downloaded from the link (4xx → "link expired" failure), and get the owner's handle from the export's profile section (`userName` under a `Profile` path only, so follower lists can't be mistaken for it). `saved` = `posted`, so own posts sort by post date.
- *Add video from phone* creates a post keyed `own-<yyyyMMdd>-<hash>` with a user-picked date (default: the file's date), caption and handle (remembered), moving the file like *Attach video*.
- Gallery: a `.nomedia` file in `media/` (default) keeps MediaStore/galleries out; toggled in Settings, applied on start, on folder change and on toggle, followed by a `MediaScannerConnection` scan of the folder so existing entries disappear or return immediately (verified on the emulator: 3 → 0 → 3 → 0 indexed videos).
- Room schema v4 adds `mediaUrl`, `caption`, `authorHandle` to the queue.

**Private posts and attached files (v0.4)**
- A post ID's top 32 bits are its creation time (Unix seconds). Verified against TikTok's own dates for 3 posts (2019, Feb 2026, Oct 2026); used as the `posted` fallback when TikTok gives nothing, so private and deleted posts keep their real date.
- TikTok answers `statusCode` 10216 (private post) or 10222 (private account) on the post page; yt-dlp reads the same codes. Those posts become `text-only` (shown as *Partial*) with a queue message explaining what to do, instead of `unavailable`.
- Private videos need the owner's session. Options considered: in-app TikTok login (WebView + cookies passed to yt-dlp) gives the original without watermark, but it's login handling the spec ruled out, TikTok often blocks WebView logins, and it would put account cookies in the app. For a handful of videos, *Save video* in TikTok + *Attach video* is simpler and needs no credentials. Chosen.
- *Attach video* moves the picked file: it copies it to `media/<id>.<ext>`, writes the note, and only then deletes the original through the document provider (`DocumentsContract.deleteDocument`). If the provider refuses, the original stays and the message says so.
- The feed looks posts up in the whole library (order still comes from the filter), so a post stays visible after an edit moves it out of the current filter.
- *Attach video* copies the picked file to `media/<id>.<ext>`, reads duration with `MediaMetadataRetriever`, makes a cover from the 1 s frame only if the note has no cover, and merges into the note (`status: full`, `video:`, embed). Caption, notes, collections and dates are kept.
- The edit sheet offers a caption field only while the note has no caption (it uses `fillCaption`, so an existing caption is never replaced). Save moved into the sheet header so the keyboard can't hide it.

**Creator comments and profile links (v0.3)**
- yt-dlp doesn't fetch TikTok comments. The app calls TikTok's web endpoint `api/comment/list/?aid=1988&aweme_id=<id>&count=50&cursor=<n>` directly; it answered unsigned requests on 2026-10-29.
- The creator is recognised by the comment's `label_list` entry "Creator", their numeric user id (yt-dlp `uploader_id`, page `author.id`, or profile `id`), or their handle. Their replies appear in each comment's `reply_comment` preview; they're saved with the comment they answer.
- Only the first 6 pages (300 top-level comments) are read, 0.4 s apart, and reply threads are not expanded beyond TikTok's preview. A creator reply buried deep in a big thread can be missed.
- The bio link is **not** on the post page (`bioLink` is null there); it's on the profile page (`webapp.user-detail.userInfo.user.bioLink.link`, sometimes without a scheme). Profiles are fetched once per creator per hour.
- Saved as `author_url`, `author_link`, `author_bio` (managed keys; link and bio are refreshed on later fetches) and a managed `## Creator comments` section placed above `## Notes`. Comments are never removed by a later fetch that finds none.
- *Settings → Fetch creator comments & links* queues a `refresh` item per saved post: it fetches profile and comments only, rewrites just those parts of the note, and downloads nothing.
- Room schema v3 adds `creatorComments` and `authorLink` with a migration (re-reads notes once).

**Collections**
- **Confirmed with a real export (2026-11-01):** TikTok's export lists collection *names* only (`Likes and Favorites → Favorite Collection → FavoriteCollectionList[] → {Date, FavoriteCollection}`); `Likes and Favorites → Collection` is `{}` and favourites are one flat list. Which video is in which collection is not exported. For a private account, the unsigned `api/user/collection_list` answers `total: 0`, and logging in is out of scope. So collections come from:
  1. shared collection links (`tiktok.com/@user/collection/<name>-<id>`), listed with yt-dlp's `tiktok:collection` extractor;
  2. the export, if a future version names collections (the parser picks up `CollectionName`/`name` fields or map keys under a collections section, and `Collection: X` lines in TXT);
  3. **Sync from TikTok** (`ui/TikTokSyncScreen.kt`, `assets/collection_sync.js`): the user signs in on TikTok's own login page in a WebView (desktop UA; Loopkeep never sees the password, and nothing is automated about the login). The script then runs inside the page and calls the same web API TikTok's site uses (`api/user/collection_list`, `api/collection/item_list`, sourceType 113), plain first and with `byted_acrawler.frontierSign` if refused. secUid comes from `webapp.app-context.user`, else from the profile page's embedded data (`api/user/detail` answers empty without signing). Results go through `enqueueCollection`, so saved posts only get the name added and missing ones are downloaded. Read-only on TikTok. The original spec ruled out login automation; this was added at the user's request (2026-11-01) as a manual sign-in. "Sign out" clears the WebView cookies.
   - **Google accounts** can't sign in inside a WebView: Google answers "This browser or app may not be secure" (checked 2026-11-01). They use TikTok's QR login instead: `assets/qr_hook.js` (document-start script via androidx.webkit) captures `data.qrcode_index_url` from `passport/web/get_qrcode` (a `tiktok.com/passport/web/scan_index/?token=…` link), and *Confirm in TikTok app* opens it in the TikTok app via `snssdk1233://webview?url=…`, then as a plain link restricted to TikTok's package. The page's own poll (`check_qrconnect`) then signs the WebView in. Whether the TikTok app accepts either intent is unverified (no TikTok app on the emulator); the fallback is scanning a screenshot of the code from TikTok's scanner.
   - TikTok's page signs its own API requests (`X-Gnarly`, `X-Bogus`) through a fetch hook, so the sync script's plain requests get signed too.
   - WebView gotcha: AndroidView's default WRAP_CONTENT layout params make WebView force a zero layout height, so `100vh` is 0 and TikTok's login page renders blank. The WebView gets MATCH_PARENT.
   - Verified on the emulator signed out: login page renders and the form works; the script resolved the secUid from the profile page, `collection_list` answered (0 collections for a signed-out viewer), and the result reached the app. The signed-in listing itself is unverified (no test account).
  3. the edit sheet, by hand, or in bulk: long-press in the grid to select posts, then *Collection*.
- The library home is collections only (2026-11-01, at the user's request): tiles for *Uncategorized* (posts whose `collections` is empty; `LibraryFilter.uncategorized`), every collection, and *New collection*. Posts are only listed inside a collection, Uncategorized, or search/status results; back returns to the tiles.
- Names listed by the export (or typed in the picker) are kept in app settings (`known_collections`) so they appear as empty collections until posts are filed into them. Membership itself only lives in the notes.
- **Listing collections is unreliable.** On 2026-10-29 yt-dlp returned zero entries for its own test collections, and TikTok's `api/collection/item_list` answered `statusCode 100001` to unsigned requests. An empty listing is reported as a readable failure in the queue, never silently accepted.
- Stored as `collections: [A, B]` in the note (managed key, unioned on merge) and as `"\nA\nB\n"` in the index for exact `LIKE` matching. Room schema v2 adds the columns with a real migration, so the queue survives the update.
- A post that's already saved only gets the collection added to its note; nothing is downloaded again.

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
