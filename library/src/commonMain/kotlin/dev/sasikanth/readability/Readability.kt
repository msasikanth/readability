package dev.sasikanth.readability

import com.eygraber.uri.Url
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.TextNode
import dev.sasikanth.readability.models.Article
import dev.sasikanth.readability.models.Metadata
import dev.sasikanth.readability.utils.*

/**
 * This is code is a port of Mozilla's [Readability.js](https://github.com/mozilla/readability/blob/main/Readability.js)
 */
class Readability private constructor(
  private val document: Document,
  private val options: Options
) {

  companion object {
    // Max number of nodes supported by this parser. Default: 0 (no limit)
    private const val DEFAULT_MAX_ELEMS_TO_PARSE = 0

    // The number of top candidates to consider when analysing how
    // tight the competition is among candidates.
    private const val DEFAULT_N_TOP_CANDIDATES = 5

    // The default number of chars an article must have in order to return a result
    private const val DEFAULT_CHAR_THRESHOLD = 500

    // These are the classes that readability sets itself
    private val CLASSES_TO_PRESERVE = listOf("page")

    fun parse(html: String, options: Options = Options()): Article? {
      val document = Ksoup.parse(html = html)
      val readability = Readability(
        document = document,
        options = options
      )

      // Avoid parsing too large documents, as per configuration option
      if (options.maxElemsToParse > 0) {
        val numTags = document.getElementsByTag("*").size
        if (numTags > options.maxElemsToParse) {
          throw Exception("Aborting parsing document; $numTags elements found")
        }
      }

      // Unwrap image from noscript
      readability.unwrapNoScriptImages()

      // Remove script tags from the document
      readability.removeScripts()

      readability.prepDocument()

      val metadata = readability.getArticleMetadata()

      val articleGrabber = ArticleGrabber(
        document = document,
        metadata = metadata,
        options = options
      )
      val article = articleGrabber.grabArticle()
      val articleContent = (article?.get("content") as? Element) ?: return null

      readability.postProcessContent(articleContent)

      var excerpt: String = metadata.excerpt.orEmpty()
      if (excerpt.isBlank()) {
        val paragraphs = articleContent.getElementsByTag("p")
        if (paragraphs.isNotEmpty()) {
          excerpt = paragraphs.first()!!.text().trim()
        }
      }

      return Article(
        title = metadata.title,
        content = articleContent.text(),
        html = articleContent.html(),
        excerpt = excerpt,
        lang = article["lang"] as? String
      )
    }
  }

  private fun getArticleMetadata(): Metadata {
    val values = mutableMapOf<String, String>()
    val metaElements = document.getElementsByTag("meta")
    val propertyPattern =
      """\s*(article|dc|dcterm|og|twitter)\s*:\s*(author|creator|description|published_time|title|site_name)\s*""".toRegex(
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
      )
    val namePattern =
      """^\s*(?:(dc|dcterm|og|twitter|weibo:(article|webpage))\s*[.:]\s*)?(author|creator|description|title|site_name)\s*$""".toRegex(
        setOf(RegexOption.IGNORE_CASE)
      )

    // Find description tags
    metaElements.forEach { element ->
      val elementName = element.attr("name")
      val elementProperty = element.attr("property")
      val content = element.attr("content")

      if (content.isBlank()) return@forEach

      var name: String? = null
      if (elementProperty.isNotBlank()) {
        val matches = propertyPattern.find(elementProperty)
        if (matches != null) {
          name = matches.value.lowercase().replace("\\s".toRegex(), "")
          values[name] = content.trim()
        }
      }
      if (name == null && elementName.isNotBlank() && namePattern.matches(elementName)) {
        name = elementName.lowercase().replace("\\s".toRegex(), "").replace(".".toRegex(), ":")
        values[name] = content.trim()
      }
    }

    var title = values["dc:title"] ?: values["dcterm:title"] ?: values["og:title"] ?: values["weibo:article:title"]
    ?: values["weibo:webpage:title"] ?: values["title"] ?: values["twitter:title"]

    if (title == null) {
      title = getArticleTitle(document)
    }

    val byline = values["dc:creator"] ?: values["dcterm:creator"] ?: values["author"]
    val excerpt = values["dc:description"] ?: values["dcterm:description"] ?: values["og:description"]
    ?: values["weibo:article:description"] ?: values["weibo:webpage:description"] ?: values["description"]
    ?: values["twitter:description"]
    val siteName = values["og:site_name"]
    val publishedTime = values["article:published_time"]

    return Metadata(
      title = unescapeHtmlEntities(title)!!,
      byline = unescapeHtmlEntities(byline),
      excerpt = unescapeHtmlEntities(excerpt),
      siteName = unescapeHtmlEntities(siteName),
      publishedTime = unescapeHtmlEntities(publishedTime)
    )
  }

  private fun getArticleTitle(document: Document): String {
    fun wordCount(string: String): Int {
      return string.split("\\s").size
    }

    var curTitle = ""
    var origTitle = ""

    try {
      curTitle = document.title().trim()
      origTitle = document.title().trim()

      // If they had an element with id "title" in their HTML
      val titleElement = document.getElementsByTag("title").first()
      if (titleElement != null) {
        val innerTitle = getInnerText(titleElement)
        curTitle = innerTitle
        origTitle = innerTitle
      }
    } catch (e: Exception) {
      // no-op
    }

    var titleHadHierarchicalSeparators = false

    // If there's a separator in the title, first remove the final part
    if (Regex("[|\\-\\\\/>»]").containsMatchIn(curTitle)) {
      titleHadHierarchicalSeparators = Regex("[\\\\/>»]").containsMatchIn(curTitle)
      curTitle = origTitle.replace(Regex("(.*)[|\\-\\\\/>»] .*", RegexOption.IGNORE_CASE), "$1")

      // If the resulting title is too short (3 words or fewer), remove
      // the first part instead:
      if (wordCount(curTitle) < 3) {
        curTitle = origTitle.replace(Regex("[^|\\-\\\\/>»]*[|\\-\\\\/>»](.*)", RegexOption.IGNORE_CASE), "$1")
      }
    } else if (curTitle.indexOf(": ") != -1) {
      // Check if we have a heading containing this exact string, so we
      // could assume it's the full title
      val headings = document.getElementsByTag("h1") + document.getElementsByTag("h2")
      val trimmedTitle = curTitle.trim()
      val match = headings.any {
        it.text().trim() == trimmedTitle
      }

      // If we don't, let's extract the title out of the original title string.
      if (!match) {
        curTitle = origTitle.substring(origTitle.lastIndexOf(":") + 1)

        // If the title is now too short, try the first colon instead:
        if (wordCount(curTitle) < 3) {
          curTitle = origTitle.substring(origTitle.indexOf(":") + 1)
          // But if we have too many words before the colon there's something weird
          // with the titles and the H tags so let's just use the original title instead
        } else if (wordCount(origTitle.substring(0, origTitle.indexOf(":"))) > 5) {
          curTitle = origTitle
        }
      }
    } else if (curTitle.length > 150 || curTitle.length < 15) {
      val hOnes = document.getElementsByTag("h1")
      if (hOnes.size == 1) {
        curTitle = getInnerText(hOnes[0])
      }
    }

    curTitle = curTitle.trim().replace(NORMALIZE, " ")
    // If we now have 4 words or fewer as our title, and either no
    // 'hierarchical' separators (\, /, > or ») were found in the original
    // title, or we decreased the number of words by more than 1 word, use
    // the original title.
    val curTitleWordCount = wordCount(curTitle)
    if (curTitleWordCount <= 4 && (!titleHadHierarchicalSeparators || curTitleWordCount != wordCount(origTitle))) {
      curTitle = origTitle
    }

    return curTitle
  }

  /**
   * Prepare the HTML document for readability to scrape it.
   * This includes things like stripping javascript, CSS, and handling terrible markup.
   **/
  private fun prepDocument() {
    removeNodes(getAllNodesWithTag(document, listOf("style")))
    replaceBrs(document, document.body())
    replaceNodeTags(getAllNodesWithTag(document, listOf("font")), "SPAN")
  }

  private fun postProcessContent(articleContent: Element) {
    // Readability cannot open relative uris, so we convert them to absolute uris.
    fixRelativeUris(document, articleContent)
    simplifyNestedElements(articleContent)
    if (!options.keepClasses) {
      cleanClasses(articleContent, options)
    }
  }

  private fun cleanClasses(node: Element, options: Options) {
    val classesToPreserve = options.classesToPreserve
    val className = node.attr("class")
      .split("/\\s+")
      .filter { cls -> classesToPreserve.contains(cls) }
      .joinToString(separator = " ")

    if (className.isNotBlank()) {
      node.attr("class", className)
    } else {
      node.removeAttr("class")
    }

    node.childNodes().forEach { child ->
      if (child is Element) {
        cleanClasses(child, options)
      }
    }
  }

  private fun simplifyNestedElements(articleContent: Element) {
    var node: Element? = articleContent
    while (node != null) {
      if (node.parentNode() != null && listOf("DIV", "SECTION").contains(node.tagName()) && !(node.id()
          .isNotBlank() && node.id().startsWith("readability"))
      ) {
        if (isElementWithoutContent(node)) {
          node = removeAndGetNext(node)
          continue
        } else if (hasSingleTagInsideElement(node, "DIV") || hasSingleTagInsideElement(node, "SECTION")) {
          val child = node.children()[0]
          node.attributes().forEach { attr ->
            child.attr(attr.key, attr.value)
          }
          node.replaceWith(child)
          node = child
          continue
        }
      }

      node = getNextNode(node)
    }
  }

  private fun fixRelativeUris(document: Document, articleContent: Element) {
    val baseUri = document.baseUri()
    val documentUri = document.location()

    fun toAbsoluteUri(uri: String): String {
      // Leave hash links alone if the base URI matches the document URI:
      if (baseUri == documentUri && uri.startsWith("#")) {
        return uri
      }

      // Otherwise. resolve against base URI:
      try {
        return Url.Builder()
          .appendEncodedPath(uri)
          .appendEncodedPath(baseUri)
          .build()
          .toString()
          .removePrefix("/")
          .removeSuffix("/")
      } catch (e: Exception) {
        // Something went wrong, just return the original:
      }

      return uri
    }

    val links = getAllNodesWithTag(articleContent, listOf("a"))
    links.forEach { link ->
      val href = link.attr("href")
      if (href.isNotBlank()) {
        // Remove links with javascript: URIs, since
        // they won't work after scripts have been remove from the page.
        if (href.indexOf("javascript:") == 0) {
          // if the link only contains simple text content, it can be converted to a text node
          if (link.childNodes().size == 1 && link.childNodes()[0] is TextNode) {
            val text = TextNode(link.text())
            link.replaceWith(text)
          } else {
            // if the link has multiple children, they should all be preserved
            val container = document.createElement("span")
            while (link.firstChild() != null) {
              container.appendChild(link.firstChild()!!)
            }
            link.replaceWith(container)
          }
        } else {
          link.attr("href", toAbsoluteUri(href))
        }
      }
    }

    val medias = getAllNodesWithTag(
      articleContent, listOf(
        "img", "picture", "figure", "video", "audio", "source"
      )
    )

    medias.forEach { media ->
      val src = media.attr("src")
      val poster = media.attr("poster")
      val srcset = media.attr("srcset")

      if (src.isNotBlank()) {
        media.attr("src", toAbsoluteUri(src))
      }

      if (poster.isNotBlank()) {
        media.attr("poster", toAbsoluteUri(poster))
      }

      if (srcset.isNotBlank()) {
        val newSrcSet = srcset.replace(SRC_SET_URL) { matchResult ->
          val p1 = matchResult.groupValues[1]
          val p2 = matchResult.groupValues[2]
          val p3 = matchResult.groupValues[3]

          toAbsoluteUri(p1) + (p2.ifEmpty { "" }) + p3
        }

        media.attr("srcset", newSrcSet)
      }
    }
  }

  /**
   * Find all <noscript> that are located after <img> nodes, and which contain only one
   * <img> element. Replace the first image with the image from inside the <noscript> tag,
   * and remove the <noscript> tag. This improves the quality of the images we use on
   * some sites (e.g. Medium).
   **/
  private fun unwrapNoScriptImages() {
    val imgRegex = "\\.(jpg|jpeg|png|webp)".toRegex(RegexOption.IGNORE_CASE)

    // Find img without source or attributes that might contains image, and remove it.
    // This is done to prevent a placeholder img is replaced by img from noscript in next step.
    document.getElementsByTag("img").forEach { img ->
      for (attr in img.attributes()) {
        when (attr.key) {
          "src", "srcset", "data-src", "data-srcset" -> return
        }

        if (imgRegex.containsMatchIn(attr.value)) {
          return
        }
      }

      img.remove()
    }

    // Next find noscript and try to extract its image
    document.getElementsByTag("noscript").forEach { noscript ->
      // Parse content of noscript and make sure it only contains image
      val tmp = document.createElement("div")
      tmp.html(noscript.html())

      if (!isSingleImage(tmp)) {
        return
      }

      // If noscript has previous sibling, and it only contains image,
      // replace it with noscript content. However, we also keep old
      // attributes that might contains image.
      val prevElement = noscript.previousElementSibling()
      if (prevElement != null && isSingleImage(prevElement)) {
        var prevImg: Element = prevElement
        if (prevImg.tagName() != "IMG") {
          prevImg = prevElement.getElementsByTag("img")[0]
        }

        val newImg = tmp.getElementsByTag("img")[0]
        for (attr in prevImg.attributes()) {
          if (attr.value.isBlank()) {
            continue
          }

          if (attr.key == "src" || attr.key == "srcset" || imgRegex.containsMatchIn(attr.value)) {
            if (newImg.attr(attr.key) == attr.value) {
              continue
            }

            var attrName = attr.key
            if (newImg.hasAttr(attrName)) {
              attrName = "data-old-$attrName"
            }

            newImg.attr(attrName, attr.value)
          }
        }

        noscript.replaceWith(prevElement)
      }
    }
  }

  /**
   * Check if node is image, or if node contains exactly only one image
   * whether as a direct child or as its descendants.
   **/
  private fun isSingleImage(element: Element): Boolean {
    if (element.tagName() == "IMG") {
      return true
    }

    if (element.childrenSize() != 1 || element.text().trim().isNotBlank()) {
      return false
    }

    return isSingleImage(element.child(0))
  }

  /**
   * Removes script tags from the document.
   **/
  private fun removeScripts() {
    removeNodes(getAllNodesWithTag(document, listOf("script", "noscript")))
  }

  /**
   * Replaces 2 or more successive <br> elements with a single <p>.
   * Whitespace between <br> elements are ignored. For example:
   *   <div>foo<br>bar<br> <br><br>abc</div>
   * will become:
   *   <div>foo<br>bar<p>abc</p></div>
   */
  private fun replaceBrs(
    document: Document,
    body: Element
  ) {
    getAllNodesWithTag(body, listOf("br")).forEach { br ->
      var next = br.nextSibling()

      // Whether 2 or more <br> elements have been found and replaced with a
      // <p> block.
      var replaced = false

      // If we find a <br> chain, remove the <br>s until we hit another node
      // or non-whitespace. This leaves behind the first <br> in the chain
      // (which will be replaced with a <p> later).
      while (next != null) {
        next = nextNode(next)
        if (next != null && next.nodeName() == "BR") {
          replaced = true
          val brSibling = next.nextSibling()
          next.remove()
          next = brSibling
        }
      }

      // If we removed a <br> chain, replace the remaining <br> with a <p>. Add
      // all sibling nodes as children of the <p> until we hit another <br>
      // chain.
      if (replaced) {
        val p = document.createElement("p")
        br.replaceWith(p)

        next = p.nextSibling()
        while (next != null) {
          // If we've hit another <br></br>, we're done adding children to this <p>.
          if (next.nodeName() == "BR") {
            val nextElem = nextNode(next.nextSibling())
            if (nextElem != null && nextElem.nodeName() == "BR") {
              break
            }
          }

          if (isPhrasingContent(next)) break

          // Otherwise, make this node a child of the new <p>
          val sibling = next.nextSibling()
          p.appendChild(next)
          next = sibling

          while (p.lastChild() != null && isWhiteSpace(p.lastChild()!!)) {
            p.lastChild()?.remove()
          }

          if (p.parent()?.tagName() == "P") {
            setNodeTag(p.parent()!!, "DIV")
          }
        }
      }
    }
  }

  data class Options(
    val maxElemsToParse: Int = DEFAULT_MAX_ELEMS_TO_PARSE,
    val nbTopCandidate: Int = DEFAULT_N_TOP_CANDIDATES,
    val charThreshold: Int = DEFAULT_CHAR_THRESHOLD,
    val classesToPreserve: List<String> = CLASSES_TO_PRESERVE,
    val allowedVideoRegex: Regex = VIDEOS,
    val keepClasses: Boolean = false
  )
}
