package io.github.tabssh.ui.activities

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.R
import io.github.tabssh.databinding.ActivityRecordingsBinding
import io.github.tabssh.terminal.recording.TranscriptManager
import io.github.tabssh.ui.adapters.RecordingAdapter
import io.github.tabssh.ui.adapters.RecordingItem
import io.github.tabssh.ui.adapters.RecordingKind
import io.github.tabssh.utils.RecordingActions
import io.github.tabssh.utils.VideoRecordingStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Unified browser for everything the session recorder can produce — videos
 * (mp4) and terminal casts (`.cast`) from the `Movies/TabSSH` MediaStore
 * folder, plus plain-text session transcripts from app-private storage.
 * Replaces the old transcript-only viewer so recordings from any tab type
 * (terminal today; VNC/SPICE consoles as they gain recording) share one list.
 */
class RecordingsActivity : TabSSHActivity() {

    private lateinit var binding: ActivityRecordingsBinding
    private lateinit var adapter: RecordingAdapter

    private var allItems: List<RecordingItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityRecordingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.appBar.toolbar)
        supportActionBar?.setTitle(R.string.activity_label_recordings)

        adapter = RecordingAdapter(
            onOpen = { item -> openItem(item) },
            onDelete = { item -> confirmDelete(item) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.chipGroupFilter.setOnCheckedStateChangeListener { _, _ -> applyFilter() }

        loadRecordings()
    }

    private fun loadRecordings() {
        lifecycleScope.launch(Dispatchers.IO) {
            val recordings = VideoRecordingStorage.listRecordings(this@RecordingsActivity).map {
                RecordingItem(
                    kind = if (it.isCast) RecordingKind.CAST else RecordingKind.VIDEO,
                    name = it.filename,
                    size = it.size,
                    timestamp = it.timestamp,
                )
            }
            val transcripts = TranscriptManager.getAllTranscripts(this@RecordingsActivity).map {
                RecordingItem(
                    kind = RecordingKind.TRANSCRIPT,
                    name = it.name,
                    size = it.size,
                    timestamp = it.timestamp,
                    transcript = it,
                )
            }
            val items = (recordings + transcripts).sortedByDescending { it.timestamp }

            withContext(Dispatchers.Main) {
                allItems = items
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val filtered = when (binding.chipGroupFilter.checkedChipId) {
            R.id.chip_filter_videos -> allItems.filter { it.kind == RecordingKind.VIDEO }
            R.id.chip_filter_casts -> allItems.filter { it.kind == RecordingKind.CAST }
            R.id.chip_filter_transcripts -> allItems.filter { it.kind == RecordingKind.TRANSCRIPT }
            else -> allItems
        }
        adapter.submitList(filtered)
        binding.emptyStateLayout.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun openItem(item: RecordingItem) {
        when (item.kind) {
            RecordingKind.VIDEO -> {
                val actions = listOf(
                    getString(R.string.recordings_action_play) to { RecordingActions.play(this, item.name) },
                    getString(R.string.video_recording_share_video) to { RecordingActions.share(this, item.name, "video/mp4") },
                )
                showActionsDialog(item.name, actions)
            }
            RecordingKind.CAST -> {
                val actions = listOf(
                    getString(R.string.video_recording_upload_cast) to { RecordingActions.uploadCast(this, item.name) },
                    getString(R.string.video_recording_share_cast) to { RecordingActions.share(this, item.name, "application/json") },
                )
                showActionsDialog(item.name, actions)
            }
            RecordingKind.TRANSCRIPT -> viewTranscript(item)
        }
    }

    private fun showActionsDialog(title: String, actions: List<Pair<String, () -> Unit>>) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions[which].second()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun viewTranscript(item: RecordingItem) {
        val transcript = item.transcript ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val content = TranscriptManager.getTranscriptContent(this@RecordingsActivity, transcript)

            withContext(Dispatchers.Main) {
                MaterialAlertDialogBuilder(this@RecordingsActivity)
                    .setTitle(transcript.name)
                    .setMessage(content)
                    .setPositiveButton(getString(R.string.close), null)
                    .show()
            }
        }
    }

    private fun confirmDelete(item: RecordingItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.recordings_delete_title))
            .setMessage(getString(R.string.cloud_delete_title, item.name))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                // Deletion runs off Main — both the transcript path and the
                // MediaStore delete can block on slow storage.
                lifecycleScope.launch(Dispatchers.IO) {
                    val ok = when (item.kind) {
                        RecordingKind.TRANSCRIPT -> item.transcript != null &&
                            TranscriptManager.deleteTranscript(item.transcript)
                        else -> VideoRecordingStorage.deleteRecording(
                            this@RecordingsActivity,
                            item.name,
                            RecordingActions.legacyFileFor(item.name)
                        )
                    }
                    withContext(Dispatchers.Main) {
                        if (ok) {
                            Toast.makeText(
                                this@RecordingsActivity,
                                getString(R.string.recordings_deleted_toast),
                                Toast.LENGTH_SHORT
                            ).show()
                            loadRecordings()
                        } else {
                            Toast.makeText(
                                this@RecordingsActivity,
                                getString(R.string.recordings_delete_failed_toast),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

}
