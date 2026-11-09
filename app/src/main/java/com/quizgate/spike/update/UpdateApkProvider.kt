package com.quizgate.spike.update

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/** Read-only, unexported, explicit URI grants; never resolve caller-controlled filesystem paths. */
class UpdateApkProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    private fun file(uri: Uri): java.io.File {
        val context = context ?: throw FileNotFoundException()
        if (uri.authority != context.packageName + ".updates" || uri.query != null || uri.fragment != null || uri.pathSegments.size != 1) throw FileNotFoundException()
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        if (!name.matches(Regex("[0-9a-f]{64}\\.apk"))) throw FileNotFoundException()
        return try { AppUpdater.get(context).verifiedForProvider(name.removeSuffix(".apk")) } catch (_: Exception) { throw FileNotFoundException("Update package is not verified") }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri): String = "application/vnd.android.package-archive"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = file(uri); val columns = (projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).filter { it in setOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE) }.toTypedArray()
        return MatrixCursor(columns).apply { addRow(columns.map { if (it == OpenableColumns.SIZE) file.length() else "quizgate-update.apk" }) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
