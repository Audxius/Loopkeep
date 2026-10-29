# Testing Loopkeep

## Install

Pick one:

- **Release through Obtainium** (recommended, gets updates): run `scripts/setup.sh` once ([SETUP.md](SETUP.md)), then add the repo in Obtainium.
- **Debug APK without any setup:** GitHub → Actions → the latest *CI* run → *Artifacts* → `loopkeep-debug-apk`. Unzip it, copy `app-debug.apk` to the phone, open it, and allow "install unknown apps" for your file manager. It installs as a separate app (`…loopkeep.debug`) next to a release build.
- **From a computer:** `./gradlew installDebug` with the phone connected over USB debugging.

Requirements: Android 8.0+ on a 64-bit phone (arm64). 32-bit-only devices are not supported.

## What to try

1. **Setup.** Open the app → *Choose folder* → create or pick a folder inside your Obsidian vault, e.g. `Vault/TikTok`. Allow notifications when asked.
2. **Share one video.** In TikTok, *Share → Save to Loopkeep* (it may be under "More"). You should see a toast "1 queued" and a progress notification. Within ~30 s the video appears in the grid.
   - Check `notes/` and `media/` in a file manager or Obsidian. The note should match the README format, and `media/<id>.jpg` should be TikTok's cover.
3. **Watch.** Tap the thumbnail. It plays and loops; tap to pause; drag the thin bottom bar; tap the caption to expand and long-press to select text; mute button; "open original".
4. **Edit.** Pencil → add a tag, write a note → Save. Open the note in Obsidian: only `tags:` and the `## Notes` section changed.
5. **Obsidian edits survive.** In Obsidian, edit the caption text and add a property (e.g. `rating: 5`). Back in Loopkeep (reopen the app), search for a word you added. Then edit tags in the app again and check in Obsidian that your caption edit and `rating` are still there.
6. **Import your export.** TikTok → Settings → Account → *Download your data* → JSON. When ready, download it, then Loopkeep → Settings → *Import TikTok data export* → pick the ZIP or JSON. The dialog shows counts for saved and liked videos. Queue them and watch the queue screen (download icon).
7. **Search and filters.** Search words from captions, `#tags`, and author names. Try the Status / Tag / Author chips, then *Watch*: the feed only shows the filtered items in the chosen order (Newest / Oldest / Shuffle).
8. **Failure handling.** Turn on airplane mode mid-import: items go back to waiting and resume when you're online. Share a link to a deleted video: you get an "Unavailable" item with a badge.
9. **Text-only retry.** If some items end as "Text only", open one → *Retry download* (also try *Settings → Update yt-dlp now* first).
10. **Rescan.** Delete a note file in Obsidian → reopen Loopkeep: it's gone from the grid. *Settings → Rescan folder* rebuilds everything.
11. **Storage info** in Settings: folder size and counts.
14. **Collections.** Make a TikTok collection public and share its link to Loopkeep. Expect either the posts to arrive filed into that collection, or a clear "TikTok returned no videos" message in Downloads (TikTok often blocks this). Also try adding a post to a collection in the edit sheet, and filtering by it from the shelf.
15. **Counters.** The four tiles at the top of the library: tap *Partial* or *Gone* to filter; *Failed* opens Downloads.
12. **Delete.** In the feed, tap the trash button (or long-press a thumbnail in the grid) → confirm. The note and its files disappear from `notes/` and `media/`.
13. **Photo posts.** Save a photo carousel: you get all images as `<id>_1.jpg …` plus the sound, swipe sideways through them in the feed. If v0.1.1 left an empty "Only the caption was saved" note, opening the new version re-downloads it automatically.

## What was verified (and how)

- **Unit tests** (64, all passing) for: URL/ID resolution, export parsing (JSON/TXT/HTML/ZIP, garbage input, short links, epoch dates), dedupe, frontmatter round-trips (byte-exact, CRLF, BOM, unknown keys, comments, quoting), the note writer and merges (Obsidian edits preserved), filenames (emoji, odd characters, non-Latin scripts, length), tag suggestions, the yt-dlp info / page / oEmbed parsers, and queue backoff and dedupe policy.
- **Live, on a desktop (2026-10-29):** yt-dlp 2026.08 against a public TikTok video confirmed the info JSON fields the parser reads (`uploader` = handle, `channel` = display name, thumbnail IDs `cover`/`originCover`/`dynamicCover`), H.264 format selection, and that the handle-less URL `tiktok.com/@/video/<id>` works. The post page's embedded JSON was checked for a live post and a deleted one (`statusCode` 10204). oEmbed was found to return no caption, author or thumbnail any more.
- **End to end on an Android 15 emulator (x86_64):** folder picker and persisted permission; share-sheet intake of two links; on-device yt-dlp init and its automatic self-update; download of video, info JSON and TikTok cover into the SAF folder; a correct note for a live video and an `unavailable` note for a deleted one; grid thumbnails; the feed opening at the tapped item, playing and swiping; a simulated Obsidian edit picked up by the incremental rescan and found by search; tags and notes saved from the edit sheet with the Obsidian edits and unknown keys intact; export import through the system file picker with correct dedupe.
- **Photo posts (2026-10-29):** live on the emulator with real carousels: a fresh share (3 images + audio) and the automatic repair of an empty v0.1.1 note (7 images + audio, caption, tags). Delete from the feed and from the grid verified (note and all media removed).
- **v0.2 design and collections (2026-10-29), emulator:** imported a synthetic export of 19 real public posts with 3 collections (all 19 saved, 13 memberships written to the notes); collections shelf, filter, edit sheet (typing, tags, notes saved to the file), counters, downloads screen. A shared collection link (yt-dlp's own test collection) produced the expected "TikTok returned no videos" failure.
- **Release signing:** a local signed `assembleRelease` with a keystore created by `scripts/setup.sh` (round-tripped through base64 like CI), checked with `apksigner`. The missing-secrets path fails with a pointer to SETUP.md.

## Not verified (needs a real device, a real export, or GitHub)

- **Your real TikTok export.** The parser is schema-agnostic and tested on synthetic files shaped like TikTok's documented export. If an import finds 0 or too few videos, please put the export (or a trimmed copy) in `samples/` (gitignored) so the parser can be checked against it.
- **Short links** (`vm.tiktok.com/…`, as produced by TikTok's share button) haven't been resolved live. This is the first thing to check with the share sheet.
- **A physical phone:** real share-sheet entry from the TikTok app, playback performance on long feeds, notification behaviour, battery, and long imports under Android's background limits.
- **The release workflow on GitHub:** CI has passed on GitHub (tests, lint, debug APK artifact), but the release workflow needs your signing secrets and a tag, so it hasn't run yet. Its signing and versioning steps were verified locally.
- **Large libraries** (thousands of notes): rescan speed and grid scrolling were only tested with a handful of items.
- **A collection link that TikTok actually lists.** No public collection could be listed during development (see NOTES.md), so the success path of collection import is unverified.
- **Private posts / private accounts**, region-blocked posts, and captcha pages from TikTok.
