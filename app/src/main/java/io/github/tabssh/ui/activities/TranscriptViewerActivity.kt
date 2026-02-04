package io.github.tabssh.ui.activities

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.databinding.ActivityTranscriptViewerBinding
import io.github.tabssh.terminal.recording.TranscriptManager
import io.github.tabssh.ui.adapters.TranscriptAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TranscriptViewerActivity : AppCompatActivity() {
    
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
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Session Transcripts"
        }
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
            val content = TranscriptManager.getTranscriptContent(transcript)
            
            withContext(Dispatchers.Main) {
                MaterialAlertDialogBuilder(this@TranscriptViewerActivity)
                    .setTitle(transcript.name)
                    .setMessage(content)
                    .setPositiveButton("Close", null)
                    .setNeutralButton("Share") { _, _ -> shareTranscript(transcript) }
                    .show()
            }
        }
    }
    
    private fun shareTranscript(transcript: TranscriptManager.Transcript) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, transcript.name)
            putExtra(Intent.EXTRA_TEXT, TranscriptManager.getTranscriptContent(transcript))
        }
        startActivity(Intent.createChooser(intent, "Share Transcript"))
    }
    
    private fun deleteTranscript(transcript: TranscriptManager.Transcript) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Transcript")
            .setMessage("Delete ${transcript.name}?")
            .setPositiveButton("Delete") { _, _ ->
                if (TranscriptManager.deleteTranscript(transcript)) {
                    android.widget.Toast.makeText(this, "Transcript deleted", android.widget.Toast.LENGTH_SHORT).show()
                    loadTranscripts()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
