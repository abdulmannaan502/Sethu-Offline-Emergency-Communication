package com.sethu.emergency.comm.util

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Crash-safe JSON-array file used by the durable SOS stores.
 *
 * Writes: serialize -> temp file -> fsync -> atomic rename over the target, so a reader (or a crash) sees
 * either the complete old file or the complete new file, never a mixture.
 *
 * Reads never silently discard data: a corrupt/truncated file is salvaged record-by-record (every complete
 * record before the damage is kept) and the original bytes are preserved as `<name>.corrupt-<timestamp>`
 * before any later write can replace them.
 */
internal class DurableJsonArrayFile(private val dir: File, private val name: String) {

    class LoadResult(
        /** Elements that parsed as JSON objects or strings (legacy). */
        val elements: List<Any>,
        /** Human-readable note when the file was damaged or partially unreadable; null when clean. */
        val issue: String?,
        val backup: File?,
        /** True when the file exists but could not be read at all; callers must NOT rewrite the store in that case. */
        val unreadable: Boolean = false
    )

    private val file = File(dir, name)

    fun load(): LoadResult {
        if (!file.exists()) return LoadResult(emptyList(), null, null)
        // A concurrent atomic replace (or a scanner holding the file) can make a read fail transiently; retry briefly.
        var text: String? = null
        for (attempt in 1..5) {
            try {
                text = file.readText(Charsets.UTF_8)
                break
            } catch (e: IOException) {
                if (attempt < 5) Thread.sleep(25)
            }
        }
        if (text == null) {
            // Still unreadable: never pretend the store is empty silently; keep a copy and surface the issue.
            return LoadResult(emptyList(), "Storage file could not be read", preserveOriginal(), unreadable = true)
        }
        if (text.isBlank()) return LoadResult(emptyList(), null, null)

        return try {
            val arr = JSONArray(text)
            val ok = ArrayList<Any>(arr.length())
            var skipped = 0
            for (i in 0 until arr.length()) {
                when (val v = arr.opt(i)) {
                    is JSONObject, is String -> ok.add(v)
                    else -> skipped++
                }
            }
            if (skipped > 0) {
                LoadResult(ok, "$skipped unreadable record(s) skipped", preserveOriginal())
            } else {
                LoadResult(ok, null, null)
            }
        } catch (e: JSONException) {
            val salvaged = salvage(text)
            LoadResult(salvaged, "Storage file was damaged; ${salvaged.size} record(s) recovered", preserveOriginal())
        }
    }

    /** Recover every complete element from a truncated/garbled top-level array. */
    private fun salvage(text: String): List<Any> {
        val out = ArrayList<Any>()
        try {
            val t = JSONTokener(text)
            if (t.nextClean() != '[') return out
            while (true) {
                val c = t.nextClean()
                if (c == ']' || c == '\u0000') break
                if (c == ',') continue
                t.back()
                when (val v = t.nextValue()) {
                    is JSONObject, is String -> out.add(v)
                    else -> Unit
                }
            }
        } catch (_: Exception) {
            // Keep what we recovered so far.
        }
        return out
    }

    fun preserveOriginal(): File? {
        return try {
            val backup = File(dir, "$name.corrupt-${System.currentTimeMillis()}")
            file.copyTo(backup, overwrite = false)
            backup
        } catch (_: Exception) {
            null
        }
    }

    /** Returns true only if the new content is durably in place. */
    fun write(content: String): Boolean {
        val tmp = File(dir, "$name.tmp")
        return try {
            FileOutputStream(tmp).use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            true
        } catch (e: Exception) {
            try { tmp.delete() } catch (_: Exception) {}
            false
        }
    }

    fun delete() {
        file.delete()
    }
}

