package io.github.tabssh.ui.activities

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.databinding.ActivityKeyboardCustomizationBinding
import io.github.tabssh.ui.adapters.KeyboardKeyAdapter
import io.github.tabssh.ui.keyboard.KeyboardKey
import io.github.tabssh.utils.logging.Logger

class KeyboardCustomizationActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityKeyboardCustomizationBinding
    private lateinit var app: TabSSHApplication
    
    private lateinit var availableKeysAdapter: KeyboardKeyAdapter
    private lateinit var currentLayoutAdapter: KeyboardKeyAdapter
    
    private val currentLayout = mutableListOf<KeyboardKey>()
    private val availableKeys = mutableListOf<KeyboardKey>()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKeyboardCustomizationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        app = application as TabSSHApplication
        
        setupToolbar()
        loadCurrentLayout()
        loadAvailableKeys()
        setupRecyclerViews()
        setupButtons()
        
        Logger.d("KeyboardCustomization", "Activity created")
    }
    
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }
    
    private fun loadCurrentLayout() {
        // Load saved layout from preferences or use default
        val savedLayout = app.preferencesManager.getString("custom_keyboard_layout", "")
        
        if (savedLayout.isNotBlank()) {
            // Parse saved layout
            val keyIds = savedLayout.split(",")
            currentLayout.clear()
            keyIds.forEach { id ->
                KeyboardKey.getAllAvailableKeys().find { it.id == id }?.let {
                    currentLayout.add(it)
                }
            }
        } else {
            // Use default layout
            currentLayout.addAll(getDefaultLayout())
        }
        
        Logger.d("KeyboardCustomization", "Loaded ${currentLayout.size} keys in layout")
    }
    
    private fun loadAvailableKeys() {
        availableKeys.clear()
        
        // Add all keys that aren't in the current layout
        KeyboardKey.getAllAvailableKeys().forEach { key ->
            if (!currentLayout.any { it.id == key.id }) {
                availableKeys.add(key)
            }
        }
        
        Logger.d("KeyboardCustomization", "Loaded ${availableKeys.size} available keys")
    }
    
    private fun setupRecyclerViews() {
        // Available keys - grid layout
        availableKeysAdapter = KeyboardKeyAdapter(availableKeys) { key ->
            addKeyToLayout(key)
        }
        binding.availableKeysRecycler.apply {
            layoutManager = GridLayoutManager(this@KeyboardCustomizationActivity, 4)
            adapter = availableKeysAdapter
        }
        
        // Current layout - linear with drag-to-reorder
        currentLayoutAdapter = KeyboardKeyAdapter(currentLayout, isRemovable = true) { key ->
            removeKeyFromLayout(key)
        }
        binding.currentLayoutRecycler.apply {
            layoutManager = LinearLayoutManager(this@KeyboardCustomizationActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = currentLayoutAdapter
        }
        
        // Add drag-to-reorder functionality
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPosition = viewHolder.adapterPosition
                val toPosition = target.adapterPosition
                
                // Swap items
                val item = currentLayout.removeAt(fromPosition)
                currentLayout.add(toPosition, item)
                currentLayoutAdapter.notifyItemMoved(fromPosition, toPosition)
                
                return true
            }
            
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // Not used
            }
        })
        itemTouchHelper.attachToRecyclerView(binding.currentLayoutRecycler)
    }
    
    private fun setupButtons() {
        binding.btnResetDefault.setOnClickListener {
            showResetConfirmation()
        }
        
        binding.btnSave.setOnClickListener {
            saveLayout()
        }
    }
    
    private fun addKeyToLayout(key: KeyboardKey) {
        if (currentLayout.size >= 20) {
            Toast.makeText(this, "Maximum 20 keys allowed in layout", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Move from available to current
        availableKeys.remove(key)
        currentLayout.add(key)
        
        availableKeysAdapter.notifyDataSetChanged()
        currentLayoutAdapter.notifyItemInserted(currentLayout.size - 1)
        
        Logger.d("KeyboardCustomization", "Added key: ${key.label}")
    }
    
    private fun removeKeyFromLayout(key: KeyboardKey) {
        val position = currentLayout.indexOf(key)
        if (position >= 0) {
            currentLayout.removeAt(position)
            availableKeys.add(key)
            
            currentLayoutAdapter.notifyItemRemoved(position)
            availableKeysAdapter.notifyDataSetChanged()
            
            Logger.d("KeyboardCustomization", "Removed key: ${key.label}")
        }
    }
    
    private fun showResetConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Reset to Default?")
            .setMessage("This will restore the default keyboard layout. Continue?")
            .setPositiveButton("Reset") { _, _ ->
                resetToDefault()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun resetToDefault() {
        currentLayout.clear()
        currentLayout.addAll(getDefaultLayout())
        
        loadAvailableKeys()
        
        availableKeysAdapter.notifyDataSetChanged()
        currentLayoutAdapter.notifyDataSetChanged()
        
        Toast.makeText(this, "Reset to default layout", Toast.LENGTH_SHORT).show()
        Logger.d("KeyboardCustomization", "Reset to default layout")
    }
    
    private fun saveLayout() {
        // Save layout as comma-separated key IDs
        val layoutString = currentLayout.joinToString(",") { it.id }
        app.preferencesManager.setString("custom_keyboard_layout", layoutString)
        
        Toast.makeText(this, "Keyboard layout saved", Toast.LENGTH_SHORT).show()
        Logger.i("KeyboardCustomization", "Saved layout: $layoutString")
        
        finish()
    }
    
    private fun getDefaultLayout(): List<KeyboardKey> {
        // Use the same default keys as KeyboardKey.getDefaultKeys()
        // This ensures consistency between the customization UI and actual default
        return KeyboardKey.getDefaultKeys()
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
