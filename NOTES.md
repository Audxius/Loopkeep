# Loopkeep: developer notes

## Plan

1. Gradle skeleton (AGP 9, Kotlin 2.4, Compose), CI-ready.
2. Pure-Kotlin core (no Android deps, fully unit-tested):
   URL/ID resolution, tolerant export parser, frontmatter editor, note document model,
   note writer, filename generator, tag suggester, yt-dlp info / oEmbed parsers.
3. Storage layer over the Storage Access Framework (DocumentsContract queries).
4. Room index (videos + FTS4) and a persistent download queue.
5. Processing pipeline in WorkManager: resolve → yt-dlp → oEmbed fallback → write files → index.
6. Compose UI: folder setup, library grid with search/filters, vertical feed, queue, settings.
7. Share-sheet target and export import.
8. CI and release workflows, setup and release scripts, docs.
