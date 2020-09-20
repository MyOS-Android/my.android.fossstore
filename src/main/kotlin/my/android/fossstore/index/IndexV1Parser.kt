package my.android.fossstore.index

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import my.android.fossstore.entity.Product
import my.android.fossstore.entity.Release
import my.android.fossstore.utility.extension.android.*
import my.android.fossstore.utility.extension.json.*
import my.android.fossstore.utility.extension.text.*
import java.io.InputStream

object IndexV1Parser {
  interface Callback {
    fun onRepository(mirrors: List<String>, name: String, description: String, version: Int, timestamp: Long)
    fun onProduct(product: Product)
    fun onReleases(packageName: String, releases: List<Release>)
  }

  private class Screenshots(val phone: List<String>, val smallTablet: List<String>, val largeTablet: List<String>)
  private class Localized(val name: String, val summary: String, val description: String,
    val whatsNew: String, val metadataIcon: String, val screenshots: Screenshots?)

  private fun <T> Map<String, Localized>.getAndCall(key: String, callback: (String, Localized) -> T?): T? {
    return this[key]?.let { callback(key, it) }
  }

  private fun <T> Map<String, Localized>.find(callback: (String, Localized) -> T?): T? {
    return getAndCall("en-US", callback) ?: getAndCall("en_US", callback) ?: getAndCall("en", callback)
  }

  private fun Map<String, Localized>.findString(fallback: String, callback: (Localized) -> String): String {
    return (find { _, localized -> callback(localized).nullIfEmpty() } ?: fallback).trim()
  }

  fun parse(repositoryId: Long, inputStream: InputStream, callback: Callback) {
    val jsonParser = Json.factory.createParser(inputStream)
    if (jsonParser.nextToken() != JsonToken.START_OBJECT) {
      jsonParser.illegal()
    } else {
      jsonParser.forEachKey {
        when {
          it.dictionary("repo") -> {
            var address = ""
            var mirrors = emptyList<String>()
            var name = ""
            var description = ""
            var version = 0
            var timestamp = 0L
            forEachKey {
              when {
                it.string("address") -> address = valueAsString
                it.array("mirrors") -> mirrors = collectDistinctNotEmptyStrings()
                it.string("name") -> name = valueAsString
                it.string("description") -> description = valueAsString
                it.number("version") -> version = valueAsInt
                it.number("timestamp") -> timestamp = valueAsLong
                else -> skipChildren()
              }
            }
            val realMirrors = ((if (address.isNotEmpty()) listOf(address) else emptyList()) + mirrors).distinct()
            callback.onRepository(realMirrors, name, description, version, timestamp)
          }
          it.array("apps") -> forEach(JsonToken.START_OBJECT) {
            val product = parseProduct(repositoryId)
            callback.onProduct(product)
          }
          it.dictionary("packages") -> forEachKey {
            if (it.token == JsonToken.START_ARRAY) {
              val packageName = it.key
              val releases = collectNotNull(JsonToken.START_OBJECT) { parseRelease() }
              callback.onReleases(packageName, releases)
            } else {
              skipChildren()
            }
          }
          else -> skipChildren()
        }
      }
    }
  }

  private fun JsonParser.parseProduct(repositoryId: Long): Product {
    var packageName = ""
    var nameFallback = ""
    var summaryFallback = ""
    var descriptionFallback = ""
    var icon = ""
    var authorName = ""
    var authorEmail = ""
    var authorWeb = ""
    var source = ""
    var changelog = ""
    var web = ""
    var tracker = ""
    var added = 0L
    var updated = 0L
    var suggestedVersionCode = 0L
    var categories = emptyList<String>()
    var antiFeatures = emptyList<String>()
    val licenses = mutableListOf<String>()
    val donates = mutableListOf<Product.Donate>()
    val localizedMap = mutableMapOf<String, Localized>()
    forEachKey {
      when {
        it.string("packageName") -> packageName = valueAsString
        it.string("name") -> nameFallback = valueAsString
        it.string("summary") -> summaryFallback = valueAsString
        it.string("description") -> descriptionFallback = valueAsString
        it.string("icon") -> icon = IndexHandler.validateIcon(valueAsString)
        it.string("authorName") -> authorName = valueAsString
        it.string("authorEmail") -> authorEmail = valueAsString
        it.string("authorWebSite") -> authorWeb = valueAsString
        it.string("sourceCode") -> source = valueAsString
        it.string("changelog") -> changelog = valueAsString
        it.string("webSite") -> web = valueAsString
        it.string("issueTracker") -> tracker = valueAsString
        it.number("added") -> added = valueAsLong
        it.number("lastUpdated") -> updated = valueAsLong
        it.string("suggestedVersionCode") -> suggestedVersionCode = valueAsString.toLongOrNull() ?: 0L
        it.array("categories") -> categories = collectDistinctNotEmptyStrings()
        it.array("antiFeatures") -> antiFeatures = collectDistinctNotEmptyStrings()
        it.string("license") -> licenses += valueAsString.split(',').filter { it.isNotEmpty() }
        it.string("donate") -> donates += Product.Donate.Regular(valueAsString)
        it.string("bitcoin") -> donates += Product.Donate.Bitcoin(valueAsString)
        it.string("flattrID") -> donates += Product.Donate.Flattr(valueAsString)
        it.string("liberapayID") -> donates += Product.Donate.Liberapay(valueAsString)
        it.string("openCollective") -> donates += Product.Donate.OpenCollective(valueAsString)
        it.dictionary("localized") -> forEachKey {
          if (it.token == JsonToken.START_OBJECT) {
            val locale = it.key
            var name = ""
            var summary = ""
            var description = ""
            var whatsNew = ""
            var metadataIcon = ""
            var phone = emptyList<String>()
            var smallTablet = emptyList<String>()
            var largeTablet = emptyList<String>()
            forEachKey {
              when {
                it.string("name") -> name = valueAsString
                it.string("summary") -> summary = valueAsString
                it.string("description") -> description = valueAsString
                it.string("whatsNew") -> whatsNew = valueAsString
                it.string("icon") -> metadataIcon = valueAsString
                it.array("phoneScreenshots") -> phone = collectDistinctNotEmptyStrings()
                it.array("sevenInchScreenshots") -> smallTablet = collectDistinctNotEmptyStrings()
                it.array("tenInchScreenshots") -> largeTablet = collectDistinctNotEmptyStrings()
                else -> skipChildren()
              }
            }
            val screenshots = if (sequenceOf(phone, smallTablet, largeTablet).any { it.isNotEmpty() })
              Screenshots(phone, smallTablet, largeTablet) else null
            localizedMap[locale] = Localized(name, summary, description, whatsNew,
              metadataIcon.nullIfEmpty()?.let { "$locale/$it" }.orEmpty(), screenshots)
          } else {
            skipChildren()
          }
        }
        else -> skipChildren()
      }
    }
    val name = localizedMap.findString(nameFallback) { it.name }
    val summary = localizedMap.findString(summaryFallback) { it.summary }
    val description = localizedMap.findString(descriptionFallback) { it.description }.replace("\n", "<br/>")
    val whatsNew = localizedMap.findString("") { it.whatsNew }.replace("\n", "<br/>")
    val metadataIcon = localizedMap.findString("") { it.metadataIcon }
    val screenshotPairs = localizedMap.find { key, localized -> localized.screenshots?.let { Pair(key, it) } }
    val screenshots = screenshotPairs
      ?.let { (key, screenshots) -> screenshots.phone.asSequence()
        .map { Product.Screenshot(key, Product.Screenshot.Type.PHONE, it) } +
        screenshots.smallTablet.asSequence()
          .map { Product.Screenshot(key, Product.Screenshot.Type.SMALL_TABLET, it) } +
        screenshots.largeTablet.asSequence()
          .map { Product.Screenshot(key, Product.Screenshot.Type.LARGE_TABLET, it) } }
      .orEmpty().toList()
    return Product(repositoryId, packageName, name, summary, description, whatsNew, icon, metadataIcon,
      Product.Author(authorName, authorEmail, authorWeb), source, changelog, web, tracker, added, updated,
      suggestedVersionCode, categories, antiFeatures, licenses,
      donates.sortedWith(IndexHandler.DonateComparator), screenshots, emptyList())
  }

  private fun JsonParser.parseRelease(): Release {
    var version = ""
    var versionCode = 0L
    var added = 0L
    var size = 0L
    var minSdkVersion = 0
    var targetSdkVersion = 0
    var maxSdkVersion = 0
    var source = ""
    var release = ""
    var hash = ""
    var hashTypeCandidate = ""
    var signature = ""
    var obbMain = ""
    var obbMainHash = ""
    var obbPatch = ""
    var obbPatchHash = ""
    val permissions = linkedSetOf<String>()
    var features = emptyList<String>()
    var platforms = emptyList<String>()
    forEachKey {
      when {
        it.string("versionName") -> version = valueAsString
        it.number("versionCode") -> versionCode = valueAsLong
        it.number("added") -> added = valueAsLong
        it.number("size") -> size = valueAsLong
        it.number("minSdkVersion") -> minSdkVersion = valueAsInt
        it.number("targetSdkVersion") -> targetSdkVersion = valueAsInt
        it.number("maxSdkVersion") -> maxSdkVersion = valueAsInt
        it.string("srcname") -> source = valueAsString
        it.string("apkName") -> release = valueAsString
        it.string("hash") -> hash = valueAsString
        it.string("hashType") -> hashTypeCandidate = valueAsString
        it.string("sig") -> signature = valueAsString
        it.string("obbMainFile") -> obbMain = valueAsString
        it.string("obbMainFileSha256") -> obbMainHash = valueAsString
        it.string("obbPatchFile") -> obbPatch = valueAsString
        it.string("obbPatchFileSha256") -> obbPatchHash = valueAsString
        it.array("uses-permission") -> collectPermissions(permissions, 0)
        it.array("uses-permission-sdk-23") -> collectPermissions(permissions, 23)
        it.array("features") -> features = collectDistinctNotEmptyStrings()
        it.array("nativecode") -> platforms = collectDistinctNotEmptyStrings()
        else -> skipChildren()
      }
    }
    val hashType = if (hash.isNotEmpty() && hashTypeCandidate.isEmpty()) "sha256" else hashTypeCandidate
    val obbMainHashType = if (obbMainHash.isNotEmpty()) "sha256" else ""
    val obbPatchHashType = if (obbPatchHash.isNotEmpty()) "sha256" else ""
    return Release(false, version, versionCode, added, size,
      minSdkVersion, targetSdkVersion, maxSdkVersion, source, release, hash, hashType, signature,
      obbMain, obbMainHash, obbMainHashType, obbPatch, obbPatchHash, obbPatchHashType,
      permissions.toList(), features, platforms, emptyList())
  }

  private fun JsonParser.collectPermissions(permissions: LinkedHashSet<String>, minSdk: Int) {
    forEach(JsonToken.START_ARRAY) {
      val firstToken = nextToken()
      val permission = if (firstToken == JsonToken.VALUE_STRING) valueAsString else ""
      if (firstToken != JsonToken.END_ARRAY) {
        val secondToken = nextToken()
        val maxSdk = if (secondToken == JsonToken.VALUE_NUMBER_INT) valueAsInt else 0
        if (permission.isNotEmpty() && Android.sdk >= minSdk && (maxSdk <= 0 || Android.sdk <= maxSdk)) {
          permissions.add(permission)
        }
        if (secondToken != JsonToken.END_ARRAY) {
          while (true) {
            val token = nextToken()
            if (token == JsonToken.END_ARRAY) {
              break
            } else if (token.isStructStart) {
              skipChildren()
            }
          }
        }
      }
    }
  }
}
