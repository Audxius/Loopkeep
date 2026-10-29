package io.github.audxius.loopkeep

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import io.github.audxius.loopkeep.core.TikTokUrls
import io.github.audxius.loopkeep.data.QueueSource
import kotlinx.coroutines.launch

/** Invisible share-sheet target: queues any TikTok links in the shared text and closes. */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as App).graph
        val text = listOfNotNull(
            intent?.getStringExtra(Intent.EXTRA_TEXT),
            intent?.getStringExtra(Intent.EXTRA_SUBJECT),
            intent?.dataString,
        ).joinToString(" ")
        val urls = TikTokUrls.findUrls(text).filter { TikTokUrls.isPostUrl(it) }

        if (graph.storage() == null) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_MESSAGE, "Pick a folder first, then share the link again.")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            finish()
            return
        }
        if (urls.isEmpty()) {
            Toast.makeText(this, "No TikTok video link in what was shared", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        lifecycleScope.launch {
            val r = graph.queue.enqueueUrls(urls, QueueSource.SHARE)
            Toast.makeText(applicationContext, "Loopkeep: ${r.summary}", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
