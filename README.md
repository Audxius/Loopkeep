# Loopkeep

Keep your saved TikToks after you delete TikTok.

Loopkeep downloads every video you saved on TikTok to your phone, together with its full caption,
author and date, into a plain folder of Markdown notes and media files that works as (part of) an
[Obsidian](https://obsidian.md) vault. The app is just a nicer way to look at that folder:

- a full-screen, swipe-up/down **feed**, like TikTok but with no algorithm, no account, no tracking, and nothing but your own saves;
- a **thumbnail grid** with full-text search over captions, notes, tags and authors, plus filters;
- **tags and notes** you add in the app are written back into the Markdown files.

If the app disappears, the folder is still a complete, readable archive.

## The folder

```
<your folder>/
  notes/   one .md file per video
  media/   <tiktok_id>.mp4 and <tiktok_id>.jpg (TikTok's own cover image)
```

A note looks like this:

```markdown
---
tiktok_id: "7300000000000000001"
source_url: https://www.tiktok.com/@chef/video/7300000000000000001
author: Chef Anna
author_handle: chef
posted: 2025-03-01
saved: 2025-03-14
tags: [recipe, pasta]
status: full
duration: 34
video: "[[7300000000000000001.mp4]]"
cover: "[[7300000000000000001.jpg]]"
---

![[7300000000000000001.mp4]]

Best pasta ever 🍝 #recipe #pasta

## Notes
```

- `status` is `full` (video saved), `text-only` (caption and cover only; you can retry later), or `unavailable` (TikTok says the post is gone or private).
- Slideshow (photo) posts get `type: slideshow`, their images as `<id>_1.jpg`, `<id>_2.jpg` …, and the sound if available.
- Edit notes freely in Obsidian. The app only ever changes its own frontmatter keys (tags, status, media links…) and the `## Notes` section. Your caption edits, extra keys and extra sections are left alone.

## Using it

1. Install (see [SETUP.md](SETUP.md) for releases through Obtainium), open Loopkeep, and pick a folder. To see the notes in Obsidian, pick a folder inside your vault, e.g. `Vault/TikTok`.
2. Add videos, any of these ways:
   - **Import your TikTok data export.** In TikTok: Settings → Account → Download your data → JSON → request, then download. In Loopkeep: Settings → *Import TikTok data export* and pick the file (JSON preferred; the ZIP as downloaded, TXT and HTML also work). Favourites are imported; liked videos are optional.
   - **Share** any TikTok link to *Save to Loopkeep* from TikTok's share menu.
   - **Paste** links in Settings.
3. Downloads run in the background, one at a time, a few seconds apart, with a progress notification. The download icon in the top bar shows the queue: failures, retries, and a *Retry failed* button.
4. Tap a thumbnail to watch from there; swipe up/down. Tap to pause, drag the thin bar at the bottom to seek, and tap the caption to expand it (the text is selectable). The pencil edits tags and notes. The feed follows the current search, filters and order (newest, oldest, or shuffled).
   To delete a video (note + files), tap the trash button in the feed or long-press it in the grid.

Edits made in Obsidian show up the next time you open the app (it rescans changed notes on start).
*Settings → Rescan folder* rebuilds everything from the files.

If downloads start failing, TikTok has probably changed something: *Settings → Update yt-dlp now*, then *Retry failed* in the queue.

## Backup

**Copy the folder.** That's the whole archive: `notes/` and `media/`. The app's own database is
only an index and is rebuilt from the folder. To move to a new phone, copy the folder, install
Loopkeep, and pick the folder.

## Building

```
./gradlew testDebugUnitTest assembleDebug     # needs JDK 17 and the Android SDK
```

Signed releases are built by GitHub Actions from version tags; see [SETUP.md](SETUP.md).
Design notes, decisions and known issues are in [NOTES.md](NOTES.md).

Loopkeep is for keeping a personal copy of posts you saved. Respect creators' rights; don't re-upload their videos.
