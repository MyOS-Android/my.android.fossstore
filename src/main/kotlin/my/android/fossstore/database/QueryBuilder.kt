package my.android.fossstore.database

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import my.android.fossstore.BuildConfig
import my.android.fossstore.utility.extension.android.*
import my.android.fossstore.utility.extension.text.*

class QueryBuilder {
  companion object {
    fun trimQuery(query: String): String {
      return query.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(separator = " ")
    }
  }

  private val builder = StringBuilder()
  private val arguments = mutableListOf<String>()

  operator fun plusAssign(query: String) {
    if (builder.isNotEmpty()) {
      builder.append(" ")
    }
    builder.append(trimQuery(query))
  }

  operator fun remAssign(argument: String) {
    this.arguments += argument
  }

  operator fun remAssign(arguments: List<String>) {
    this.arguments += arguments
  }

  fun query(db: SQLiteDatabase, signal: CancellationSignal?): Cursor {
    val query = builder.toString()
    val arguments = arguments.toTypedArray()
    if (BuildConfig.DEBUG) {
      synchronized(QueryBuilder::class.java) {
        debug(query)
        db.rawQuery("EXPLAIN QUERY PLAN $query", arguments).use { it.asSequence()
          .forEach { debug(":: ${it.getString(it.getColumnIndex("detail"))}") } }
      }
    }
    return db.rawQuery(query, arguments, signal)
  }
}
