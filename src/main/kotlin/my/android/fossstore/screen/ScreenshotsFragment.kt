package my.android.fossstore.screen

import android.app.Dialog
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.MarginPageTransformer
import androidx.viewpager2.widget.ViewPager2
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import my.android.fossstore.R
import my.android.fossstore.database.Database
import my.android.fossstore.entity.Product
import my.android.fossstore.entity.Repository
import my.android.fossstore.graphics.PaddingDrawable
import my.android.fossstore.network.PicassoDownloader
import my.android.fossstore.utility.RxUtils
import my.android.fossstore.utility.extension.android.*
import my.android.fossstore.utility.extension.resources.*
import my.android.fossstore.widget.StableRecyclerAdapter

class ScreenshotsFragment(): DialogFragment() {
  companion object {
    private const val EXTRA_PACKAGE_NAME = "packageName"
    private const val EXTRA_REPOSITORY_ID = "repositoryId"
    private const val EXTRA_IDENTIFIER = "identifier"

    private const val STATE_IDENTIFIER = "identifier"
  }

  constructor(packageName: String, repositoryId: Long, identifier: String): this() {
    arguments = Bundle().apply {
      putString(EXTRA_PACKAGE_NAME, packageName)
      putLong(EXTRA_REPOSITORY_ID, repositoryId)
      putString(EXTRA_IDENTIFIER, identifier)
    }
  }

  fun show(fragmentManager: FragmentManager) {
    show(fragmentManager, this::class.java.name)
  }

  private var viewPager: ViewPager2? = null

  private var productDisposable: Disposable? = null

  override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
    val packageName = requireArguments().getString(EXTRA_PACKAGE_NAME)!!
    val repositoryId = requireArguments().getLong(EXTRA_REPOSITORY_ID)
    val dialog = Dialog(requireContext(), R.style.Theme_Main_Dark)

    val window = dialog.window!!
    val decorView = window.decorView
    val background = dialog.context.getColorFromAttr(android.R.attr.colorBackground).defaultColor
    decorView.setBackgroundColor(background.let { ColorUtils.blendARGB(0x00ffffff and it, it, 0.9f) })
    decorView.setPadding(0, 0, 0, 0)
    background.let { ColorUtils.blendARGB(0x00ffffff and it, it, 0.8f) }.let {
      window.statusBarColor = it
      window.navigationBarColor = it
    }
    window.attributes = window.attributes.apply {
      title = ScreenshotsFragment::class.java.name
      format = PixelFormat.TRANSLUCENT
      windowAnimations = run {
        val typedArray = dialog.context.obtainStyledAttributes(null,
          intArrayOf(android.R.attr.windowAnimationStyle), android.R.attr.dialogTheme, 0)
        try {
          typedArray.getResourceId(0, 0)
        } finally {
          typedArray.recycle()
        }
      }
      if (Android.sdk(28)) {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
      }
    }

    val hideFlags = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
      View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE
    decorView.systemUiVisibility = decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
      View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    val applyHide = Runnable { decorView.systemUiVisibility = decorView.systemUiVisibility or hideFlags }
    val handleClick = {
      decorView.removeCallbacks(applyHide)
      if ((decorView.systemUiVisibility and hideFlags) == hideFlags) {
        decorView.systemUiVisibility = decorView.systemUiVisibility and hideFlags.inv()
      } else {
        decorView.systemUiVisibility = decorView.systemUiVisibility or hideFlags
      }
    }
    decorView.postDelayed(applyHide, 2000L)
    decorView.setOnClickListener { handleClick() }

    val viewPager = ViewPager2(dialog.context)
    viewPager.adapter = Adapter(packageName) { handleClick() }
    viewPager.setPageTransformer(MarginPageTransformer(resources.sizeScaled(16)))
    viewPager.viewTreeObserver.addOnGlobalLayoutListener {
      (viewPager.adapter as Adapter).size = Pair(viewPager.width, viewPager.height)
    }
    dialog.addContentView(viewPager, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
      ViewGroup.LayoutParams.MATCH_PARENT))
    this.viewPager = viewPager

    var restored = false
    productDisposable = Observable.just(Unit)
      .concatWith(Database.observable(Database.Subject.Products))
      .observeOn(Schedulers.io())
      .flatMapSingle { RxUtils.querySingle { Database.ProductAdapter.get(packageName, it) } }
      .map { Pair(it.find { it.repositoryId == repositoryId }, Database.RepositoryAdapter.get(repositoryId)) }
      .observeOn(AndroidSchedulers.mainThread())
      .subscribe {
        val (product, repository) = it
        val screenshots = product?.screenshots.orEmpty()
        (viewPager.adapter as Adapter).update(repository, screenshots)
        if (!restored) {
          restored = true
          val identifier = savedInstanceState?.getString(STATE_IDENTIFIER)
            ?: requireArguments().getString(STATE_IDENTIFIER)
          if (identifier != null) {
            val index = screenshots.indexOfFirst { it.identifier == identifier }
            if (index >= 0) {
              viewPager.setCurrentItem(index, false)
            }
          }
        }
      }

    return dialog
  }

  override fun onDestroyView() {
    super.onDestroyView()

    viewPager = null

    productDisposable?.dispose()
    productDisposable = null
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)

    val viewPager = viewPager
    if (viewPager != null) {
      val identifier = (viewPager.adapter as Adapter).getCurrentIdentifier(viewPager)
      identifier?.let { outState.putString(STATE_IDENTIFIER, it) }
    }
  }

  private class Adapter(private val packageName: String, private val onClick: () -> Unit):
    StableRecyclerAdapter<Adapter.ViewType, RecyclerView.ViewHolder>() {
    enum class ViewType { SCREENSHOT }

    private class ViewHolder(context: Context): RecyclerView.ViewHolder(ImageView(context)) {
      val image: ImageView
        get() = itemView as ImageView

      val placeholder: Drawable

      init {
        itemView.layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT,
          RecyclerView.LayoutParams.MATCH_PARENT)

        val placeholder = itemView.context.getDrawableCompat(R.drawable.ic_photo_camera).mutate()
        placeholder.setTint(itemView.context.getColorFromAttr(android.R.attr.textColorPrimary).defaultColor
          .let { ColorUtils.blendARGB(0x00ffffff and it, it, 0.25f) })
        this.placeholder = PaddingDrawable(placeholder, 4f)
      }
    }

    private var repository: Repository? = null
    private var screenshots = emptyList<Product.Screenshot>()

    fun update(repository: Repository?, screenshots: List<Product.Screenshot>) {
      this.repository = repository
      this.screenshots = screenshots
      notifyDataSetChanged()
    }

    var size = Pair(0, 0)
      set(value) {
        if (field != value) {
          field = value
          notifyDataSetChanged()
        }
      }

    fun getCurrentIdentifier(viewPager: ViewPager2): String? {
      val position = viewPager.currentItem
      return screenshots.getOrNull(position)?.identifier
    }

    override val viewTypeClass: Class<ViewType>
      get() = ViewType::class.java

    override fun getItemCount(): Int = screenshots.size
    override fun getItemDescriptor(position: Int): String = screenshots[position].identifier
    override fun getItemEnumViewType(position: Int): ViewType = ViewType.SCREENSHOT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: ViewType): RecyclerView.ViewHolder {
      return ViewHolder(parent.context).apply {
        itemView.setOnClickListener { onClick() }
      }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
      holder as ViewHolder
      val screenshot = screenshots[position]
      val (width, height) = size
      if (width > 0 && height > 0) {
        holder.image.load(PicassoDownloader.createScreenshotUri(repository!!, packageName, screenshot)) {
          placeholder(holder.placeholder)
          error(holder.placeholder)
          resize(width, height)
          centerInside()
        }
      } else {
        holder.image.clear()
      }
    }
  }
}
