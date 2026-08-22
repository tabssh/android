package io.github.tabssh.ui.activities

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.R
import io.github.tabssh.databinding.ActivityTranscriptViewerBinding
import io.github.tabssh.terminal.recording.TranscriptManager
import io.github.tabssh.ui.adapters.TranscriptAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TranscriptViewerActivity : TabSSHActivity() {
    
    private lateinit var binding: ActivityTranscriptViewerBinding
    private lateinit var adapter: TranscriptAdapter
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityTranscriptViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupToolbar()
        setupRecyclerView()
        loadTranscripts()
    }
    
    private fun setupToolbar() {
        setSupportActionBar(binding.appBar.toolbar)
        supportActionBar?.setTitle(R.string.transcript_viewer_title)
    }
    
    private fun setupRecyclerView() {
        adapter = TranscriptAdapter(
            onView = { transcript -> viewTranscript(transcript) },
            onShare = { transcript -> shareTranscript(transcript) },
            onDelete = { transcript -> deleteTranscript(transcript) }
        )
        
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }
    
    private fun loadTranscripts() {
        lifecycleScope.launch(Dispatchers.IO) {
            val transcripts = TranscriptManager.getAllTranscripts(this@TranscriptViewerActivity)
            
            withContext(Dispatchers.Main) {
                adapter.submitList(transcripts)
                
                if (transcripts.isEmpty()) {
                    binding.emptyStateLayout.visibility = android.view.View.VISIBLE
                    binding.recyclerView.visibility = android.view.View.GONE
                } else {
                    binding.emptyStateLayout.visibility = android.view.View.GONE
                    binding.recyclerView.visibility = android.view.View.VISIBLE
                }
            }
        }
    }
    
    private fun viewTranscript(transcript: TranscriptManager.Transcript) {
        lifecycleScope.launch(Dispatchers.IO) {
            val content = TranscriptManager.getTranscriptContent(this@TranscriptViewerActivity, transcript)
            
            withContext(Dispatchers.Main) {
                MaterialAlertDialogBuilder(this@TranscriptViewerActivity)
                    .setTitle(transcript.name)
                    .setMessage(content)
                    .setPositiveButton(getString(R.string.close), null)
                    .setNeutralButton(getString(R.string.share)) { _, _ -> shareTranscript(transcript) }
                    .show()
            }
        }
    }
    
    private fun shareTranscript(transcript: TranscriptManager.Transcript) {
        lifecycleScope.launch(Dispatchers.IO) {
            val content = TranscriptManager.getTranscriptContent(this@TranscriptViewerActivity, transcript)
            withContext(Dispatchers.Main) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, transcript.name)
                    putExtra(Intent.EXTRA_TEXT, content)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.transcript_viewer_share_chooser_title)))
            }
        }
    }
    
    private fun deleteTranscript(transcript: TranscriptManager.Transcript) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.transcript_viewer_delete_title))
            .setMessage(getString(R.string.transcript_viewer_delete_message_fmt, transcript.name))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                // File deletion runs off Main — TranscriptManager.deleteTranscript
                // walks the filesystem and can block on slow storage.
                lifecycleScope.launch(Dispatchers.IO) {
                    val ok = TranscriptManager.deleteTranscript(transcript)
                    withContext(Dispatchers.Main) {
                        if (ok) {
                            android.widget.Toast.makeText(
                                this@TranscriptViewerActivity,
                                getString(R.string.transcript_viewer_deleted_toast),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            loadTranscripts()
                        } else {
                            android.widget.Toast.makeText(
                                this@TranscriptViewerActivity,
                                getString(R.string.transcript_viewer_delete_failed_toast),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    
}
