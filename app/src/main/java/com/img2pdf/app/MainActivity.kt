package com.img2pdf.app

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var btnShare: Button
    private var lastPdfUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        btnShare = findViewById(R.id.btnShare)
        btnShare.isEnabled = false
        btnShare.setOnClickListener { sharePdf() }

        // 处理传入的 intent
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND_MULTIPLE) return
        val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: return
        if (uris.isEmpty()) return

        statusText.text = "收到 ${uris.size} 张图片，正在排序..."

        // 关键：按文件名自然排序
        val sortedUris = naturalSortByFileName(uris)

        statusText.text = "正在生成PDF (${sortedUris.size} 页)..."

        try {
            val pdfFile = imagesToPdf(sortedUris)
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val displayName = "图片_$timestamp.pdf"

            // 保存到 Downloads 文件夹
            val savedUri = saveToDownloads(pdfFile, displayName)
            pdfFile.delete() // 删除临时文件

            lastPdfUri = savedUri
            btnShare.isEnabled = true
            statusText.text = "✅ 生成完成！\n文件名: $displayName\n共 ${sortedUris.size} 页"
        } catch (e: Exception) {
            statusText.text = "❌ 生成失败: ${e.message}"
            e.printStackTrace()
        }
    }

    /**
     * 自然排序：按文件名中的数字部分作为数值比较
     * 例: "photo2.jpg" < "photo10.jpg" （不是字典序的 "10" < "2"）
     */
    private fun naturalSortByFileName(uris: List<Uri>): List<Uri> {
        data class NamedUri(val uri: Uri, val name: String)

        // 获取每个 URI 的文件名
        val namedUris = uris.map { uri ->
            NamedUri(uri, getFileName(uri))
        }

        // 自然排序比较器
        val comparator = Comparator<NamedUri> { a, b ->
            naturalCompare(a.name, b.name)
        }

        return namedUris.sortedWith(comparator).map { it.uri }
    }

    /**
     * 自然比较两个字符串
     * 将字符串拆分为 [字母段, 数字段, 字母段, 数字段, ...] 逐段比较
     * 数字段按数值比较，字母段按字典序（忽略大小写）
     */
    private fun naturalCompare(a: String, b: String): Int {
        val regex = Regex("(\\d+)|(\\D+)")
        val partsA = regex.findAll(a.lowercase()).map { it.value }.toList()
        val partsB = regex.findAll(b.lowercase()).map { it.value }.toList()

        val len = minOf(partsA.size, partsB.size)
        for (i in 0 until len) {
            val pa = partsA[i]
            val pb = partsB[i]
            val numA = pa.toLongOrNull()
            val numB = pb.toLongOrNull()

            val result = when {
                numA != null && numB != null -> numA.compareTo(numB) // 都是数字，按数值比
                else -> pa.compareTo(pb)                             // 否则按字典序
            }
            if (result != 0) return result
        }
        return partsA.size.compareTo(partsB.size)
    }

    private fun getFileName(uri: Uri): String {
        var name = "unknown"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(idx) ?: "unknown"
            }
        }
        return name
    }

    /**
     * 把多张图片合成一个 PDF
     * 每张图片作为一页，页面尺寸 = 图片尺寸（像素）
     * 对超大图片做降采样防 OOM
     */
    private fun imagesToPdf(uris: List<Uri>): File {
        val pdf = PdfDocument()
        try {
            uris.forEachIndexed { i, uri ->
                val bitmap = decodeSampledBitmap(uri, 2048) // 限制最大2048px防OOM
                try {
                    // A4 比例：595 x 842 points (72 dpi)
                    // 我们用图片实际像素作为页面大小
                    val pageInfo = PdfDocument.PageInfo.Builder(
                        bitmap.width, bitmap.height, i + 1
                    ).create()
                    val page = pdf.startPage(pageInfo)
                    page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                    pdf.finishPage(page)
                } finally {
                    bitmap.recycle()
                }
            }

            val outFile = File(getExternalFilesDir(null), "temp_output.pdf")
            FileOutputStream(outFile).use { pdf.writeTo(it) }
            return outFile
        } finally {
            pdf.close()
        }
    }

    /**
     * 降采样：如果图片超过 maxSize，按比例缩小，防 OOM
     */
    private fun decodeSampledBitmap(uri: Uri, maxSize: Int): Bitmap {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }

        val width = options.outWidth
        val height = options.outHeight
        var sampleSize = 1
        while (width / sampleSize > maxSize || height / sampleSize > maxSize) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        } ?: throw Exception("无法读取图片")
    }

    /**
     * 保存到公共 Downloads 文件夹
     * Android 10+ 用 MediaStore，低版本直接写文件
     */
    private fun saveToDownloads(sourceFile: File, displayName: String): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ : MediaStore
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("无法创建文件")
            contentResolver.openOutputStream(uri)?.use { out ->
                sourceFile.inputStream().use { it.copyTo(out) }
            }
            return uri
        } else {
            // Android 8-9 : 直接写入 Downloads 目录
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val dest = File(dir, displayName)
            sourceFile.copyTo(dest, overwrite = true)
            // 通知媒体库扫描
            MediaStore.Images.Media.insertImage(contentResolver, dest.absolutePath, displayName, "")
            sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(dest)))
            return Uri.fromFile(dest)
        }
    }

    private fun sharePdf() {
        val uri = lastPdfUri ?: return
        // 用 FileProvider 生成可分享的 content:// URI
        val file = File(getExternalFilesDir(null), "temp_share.pdf")

        // 如果是 content:// URI（MediaStore），先复制出来以便 FileProvider 分享
        // 直接用原始 URI 分享即可（MediaStore URI 本身可分享）
        val shareUri = if (uri.scheme == "content" && uri.authority?.contains("media") == true) {
            uri
        } else {
            // 对于 file:// URI，用 FileProvider
            FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "分享PDF"))
    }
}
