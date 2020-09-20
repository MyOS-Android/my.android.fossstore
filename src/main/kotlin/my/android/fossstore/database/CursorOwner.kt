package my.android.fossstore.database

import android.database.Cursor
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.loader.app.LoaderManager
import androidx.loader.content.Loader
import my.android.fossstore.entity.ProductItem

class CursorOwner: Fragment(), LoaderManager.LoaderCallbacks<Cursor> {
  sealed class Request {
    internal abstract val id: Int

    data class ProductsAvailable(val searchQuery: String, val section: ProductItem.Section,
      val order: ProductItem.Order): Request() {
      override val id: Int
        get() = 1
    }

    data class ProductsInstalled(val searchQuery: String, val section: ProductItem.Section,
      val order: ProductItem.Order): Request() {
      override val id: Int
        get() = 2
    }

    data class ProductsUpdates(val searchQuery: String, val section: ProductItem.Section,
      val order: ProductItem.Order): Request() {
      override val id: Int
        get() = 3
    }

    object Repositories: Request() {
      override val id: Int
        get() = 4
    }
  }

  interface Callback {
    fun onCursorData(request: Request, cursor: Cursor?)
  }

  private data class ActiveRequest(val request: Request, val callback: Callback?, val cursor: Cursor?)

  init {
    retainInstance = true
  }

  private val activeRequests = mutableMapOf<Int, ActiveRequest>()

  fun attach(callback: Callback, request: Request) {
    val oldActiveRequest = activeRequests[request.id]
    if (oldActiveRequest?.callback != null &&
      oldActiveRequest.callback != callback && oldActiveRequest.cursor != null) {
      oldActiveRequest.callback.onCursorData(oldActiveRequest.request, null)
    }
    val cursor = if (oldActiveRequest?.request == request && oldActiveRequest.cursor != null) {
      callback.onCursorData(request, oldActiveRequest.cursor)
      oldActiveRequest.cursor
    } else {
      null
    }
    activeRequests[request.id] = ActiveRequest(request, callback, cursor)
    if (cursor == null) {
      LoaderManager.getInstance(this).restartLoader(request.id, null, this)
    }
  }

  fun detach(callback: Callback) {
    for (id in activeRequests.keys) {
      val activeRequest = activeRequests[id]!!
      if (activeRequest.callback == callback) {
        activeRequests[id] = activeRequest.copy(callback = null)
      }
    }
  }

  override fun onCreateLoader(id: Int, args: Bundle?): Loader<Cursor> {
    val request = activeRequests[id]!!.request
    return QueryLoader(requireContext()) {
      when (request) {
        is Request.ProductsAvailable -> Database.ProductAdapter
          .query(false, false, request.searchQuery, request.section, request.order, it)
        is Request.ProductsInstalled -> Database.ProductAdapter
          .query(true, false, request.searchQuery, request.section, request.order, it)
        is Request.ProductsUpdates -> Database.ProductAdapter
          .query(true, true, request.searchQuery, request.section, request.order, it)
        is Request.Repositories -> Database.RepositoryAdapter.query(it)
      }
    }
  }

  override fun onLoadFinished(loader: Loader<Cursor>, data: Cursor?) {
    val activeRequest = activeRequests[loader.id]
    if (activeRequest != null) {
      activeRequests[loader.id] = activeRequest.copy(cursor = data)
      activeRequest.callback?.onCursorData(activeRequest.request, data)
    }
  }

  override fun onLoaderReset(loader: Loader<Cursor>) = onLoadFinished(loader, null)
}
