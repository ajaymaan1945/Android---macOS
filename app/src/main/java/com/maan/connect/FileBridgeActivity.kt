package com.maan.connect

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import java.util.concurrent.ConcurrentHashMap

/**
 * A MAAN-owned file surface.  Because this screen belongs to MAAN, the
 * AccessibilityService can reliably map a mirrored-screen coordinate to the
 * real DocumentFile URI.  This is the safe bridge used for phone -> Mac
 * drag/drop; it does not pretend that MediaProjection pixels are file objects.
 */
class FileBridgeActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_URI = "tree_uri"
        private const val REQUEST_TREE = 991
        private const val PREFS = "MAANFileBridge"
        private const val KEY_TREE = "tree_uri"
        private const val MAX_FILES = 250

        @Volatile var visible = false
        private val filesByUri = ConcurrentHashMap<String, String>()

        fun labelFor(uri: String): String? = filesByUri[uri]
        fun remember(uri: String, name: String) { filesByUri[uri] = name }
    }

    private lateinit var list: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        visible = true
        buildUi()
        refresh()
    }

    override fun onDestroy() {
        visible = false
        filesByUri.clear()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 24, 20, 20)
        }
        val title = TextView(this).apply {
            text = "MAAN Files"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
        }
        status = TextView(this).apply {
            textSize = 14f
            alpha = .7f
            setPadding(0, 8, 0, 14)
        }
        val choose = Button(this).apply {
            text = "Choose phone folder"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }, REQUEST_TREE)
            }
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }
        root.addView(title)
        root.addView(status)
        root.addView(choose)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_TREE || resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_TREE, uri.toString()).apply()
        refresh()
    }

    private fun refresh() {
        list.removeAllViews()
        filesByUri.clear()
        val tree = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TREE, null)
        if (tree.isNullOrBlank()) {
            status.text = "Choose a folder once. Then drag files from this MAAN Files screen to your Mac."
            return
        }
        val root = DocumentFile.fromTreeUri(this, Uri.parse(tree))
        if (root == null || !root.canRead()) {
            status.text = "Folder access is no longer available. Choose it again."
            return
        }
        val files = ArrayList<DocumentFile>()
        collect(root, files)
        status.text = "${files.size} files available • Drag a file from this screen to the Mac window"
        files.take(MAX_FILES).forEach { addRow(it) }
    }

    private fun collect(dir: DocumentFile, out: MutableList<DocumentFile>) {
        val children = runCatching { dir.listFiles() }.getOrDefault(emptyArray())
        for (child in children) {
            if (out.size >= MAX_FILES) return
            if (child.isDirectory) collect(child, out) else if (child.isFile) out += child
        }
    }

    private fun addRow(file: DocumentFile) {
        val uri = file.uri.toString()
        val name = file.name ?: "file"
        remember(uri, name)
        val row = TextView(this).apply {
            text = "📄  $name"
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 18, 16, 18)
            isFocusable = true
            contentDescription = "MAAN_FILE|$uri|$name"
            tag = uri
            setOnClickListener { }
        }
        list.addView(row, LinearLayout.LayoutParams(-1, -2))
    }
}
