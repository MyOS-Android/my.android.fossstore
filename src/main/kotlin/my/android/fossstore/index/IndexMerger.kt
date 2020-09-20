package my.android.fossstore.index

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.fasterxml.jackson.core.JsonToken
import my.android.fossstore.entity.Product
import my.android.fossstore.entity.Release
import my.android.fossstore.utility.extension.android.*
import my.android.fossstore.utility.extension.json.*
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File

class IndexMerger(file: File): Closeable {
  private val db = SQLiteDatabase.openOrCreateDatabase(file, null)

  init {
    db.execWithResult("PRAGMA synchronous = OFF")
    db.execWithResult("PRAGMA journal_mode = OFF")
    db.execSQL("CREATE TABLE product (package_name TEXT PRIMARY KEY, description TEXT NOT NULL, data BLOB NOT NULL)")
    db.execSQL("CREATE TABLE releases (package_name TEXT PRIMARY KEY, data BLOB NOT NULL)")
    db.beginTransaction()
  }

  fun addProducts(products: List<Product>) {
    for (product in products) {
      val outputStream = ByteArrayOutputStream()
      Json.factory.createGenerator(outputStream).use { it.writeDictionary(product::serialize) }
      db.insert("product", null, ContentValues().apply {
        put("package_name", product.packageName)
        put("description", product.description)
        put("data", outputStream.toByteArray())
      })
    }
  }

  fun addReleases(pairs: List<Pair<String, List<Release>>>) {
    for (pair in pairs) {
      val (packageName, releases) = pair
      val outputStream = ByteArrayOutputStream()
      Json.factory.createGenerator(outputStream).use {
        it.writeStartArray()
        for (release in releases) {
          it.writeDictionary(release::serialize)
        }
        it.writeEndArray()
      }
      db.insert("releases", null, ContentValues().apply {
        put("package_name", packageName)
        put("data", outputStream.toByteArray())
      })
    }
  }

  private fun closeTransaction() {
    if (db.inTransaction()) {
      db.setTransactionSuccessful()
      db.endTransaction()
    }
  }

  fun forEach(repositoryId: Long, windowSize: Int, callback: (List<Product>, Int) -> Unit) {
    closeTransaction()
    db.rawQuery("""SELECT product.description, product.data AS pd, releases.data AS rd FROM product
      LEFT JOIN releases ON product.package_name = releases.package_name""", null)
      ?.use { it.asSequence().map {
        val description = it.getString(0)
        val product = Json.factory.createParser(it.getBlob(1)).use {
          it.nextToken()
          Product.deserialize(repositoryId, description, it)
        }
        val releases = it.getBlob(2)?.let { Json.factory.createParser(it).use {
          it.nextToken()
          it.collectNotNull(JsonToken.START_OBJECT, Release.Companion::deserialize)
        } }.orEmpty()
        product.copy(releases = releases)
      }.windowed(windowSize, windowSize, true).forEach { products -> callback(products, it.count) } }
  }

  override fun close() {
    db.use { closeTransaction() }
  }
}
