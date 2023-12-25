package dev.sasikanth.readability

import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element
import dev.sasikanth.readability.models.Attempt
import dev.sasikanth.readability.models.ContentScore
import dev.sasikanth.readability.models.Metadata
import dev.sasikanth.readability.utils.*
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal class ArticleGrabber(
  private val document: Document,
  private val metadata: Metadata,
  private val options: Readability.Options
) {

  companion object {

    private const val FLAG_STRIP_UNLIKELYS = 0x1
    private const val FLAG_WEIGHT_CLASSES = 0x2
    private const val FLAG_CLEAN_CONDITIONALLY = 0x4

    private val UNLIKELY_ROLES = listOf(
      "menu", "menubar", "complementary", "navigation", "alert", "alertdialog", "dialog"
    )
    private val DEFAULT_TAGS_TO_SCORE = "section,h2,h3,h4,h5,h6,p,td,pre".uppercase().split(",")
    private val DIV_TO_P_ELEMS = setOf("BLOCKQUOTE", "DL", "DIV", "IMG", "OL", "P", "PRE", "TABLE", "UL")
    private val ALTER_TO_DIV_EXCEPTIONS = listOf("DIV", "ARTICLE", "SECTION", "P")
    private val PRESENTATIONAL_ATTRIBUTES = listOf(
      "align",
      "background",
      "bgcolor",
      "border",
      "cellpadding",
      "cellspacing",
      "frame",
      "hspace",
      "rules",
      "style",
      "valign",
      "vspace"
    )
    private val DEPRECATED_SIZE_ATTRIBUTE_ELEMS = listOf("TABLE", "TH", "TD", "HR", "PRE")

    private val UNLIKELY_CANDIDATES =
      "-ad-|ai2html|banner|breadcrumbs|combx|comment|community|cover-wrap|disqus|extra|footer|gdpr|header|legends|menu|related|remark|replies|rss|shoutbox|sidebar|skyscraper|social|sponsor|supplemental|ad-break|agegate|pagination|pager|popup|yom-remote".toRegex(
        RegexOption.IGNORE_CASE
      )
    private val OK_MAYBE_ITS_A_CANDIDATE =
      "and|article|body|column|content|main|shadow".toRegex(RegexOption.IGNORE_CASE)

    // The default number of chars an article must have in order to return a result
    private const val DEFAULT_CHAR_THRESHOLD = 500
  }

  private var flags = FLAG_STRIP_UNLIKELYS or FLAG_WEIGHT_CLASSES or FLAG_CLEAN_CONDITIONALLY

  private val readabilityData = mutableMapOf<Element, ContentScore>()

  fun grabArticle(): Map<String, Any?>? {
    val output = mutableMapOf<String, Any?>()
    var page: Element? = document
    val isPaging = page != null
    page = page ?: document.body()

    val pageCacheHtml = page.html()

    while (true) {
      val stripUnlikelyCandidate = flagIsActive(FLAG_STRIP_UNLIKELYS)

      // First, node prepping. Trash nodes that look cruddy (like ones with the
      // class name "comment", etc.), and turn divs into P tags where they have been
      // use inappropriately (as in, where they contain no other block level elements.)
      val elementsToScore = mutableListOf<Element>()
      var node: Element? = document.root()

      var shouldRemoveTitleHeader = true

      while (node != null) {
        if (node.tagName() == "HTML") {
          output["lang"] = node.attr("lang")
        }

        val matchString = "${node.className()} ${node.id()}"

        if (!isProbablyVisible(node)) {
          node = removeAndGetNext(node)
          continue
        }

        // User is not able to see elements applied with both "aria-modal = true" and "role = dialog"
        if (node.attr("aria-modal") == "true" && node.attr("role") == "dialog") {
          node = removeAndGetNext(node)
          continue
        }

        // Check to see if this node is a byline, and remove it if it is.
        if (checkByline(node, metadata.byline, matchString)) {
          node = removeAndGetNext(node)
          continue
        }

        if (shouldRemoveTitleHeader && headerDuplicatesTitle(node, metadata.title)) {
          shouldRemoveTitleHeader = false
          node = removeAndGetNext(node)
          continue
        }

        // Remove unlikely candidates
        if (stripUnlikelyCandidate) {
          if (UNLIKELY_CANDIDATES.containsMatchIn(matchString) &&
            !OK_MAYBE_ITS_A_CANDIDATE.containsMatchIn(matchString) &&
            !hasAncestorTag(node, "table") &&
            !hasAncestorTag(node, "code") &&
            node.tagName() != "BODY" &&
            node.tagName() != "A"
          ) {
            node = removeAndGetNext(node)
            continue
          }

          if (UNLIKELY_ROLES.contains(node.attr("role"))) {
            node = removeAndGetNext(node)
            continue
          }
        }

        // Remove DIV, SECTION, and HEADER nodes without any content (e.g. text, image, video or iframe).
        val isContentTag = node.tagName() == "DIV" || node.tagName() == "SECTION" || node.tagName() == "HEADER" ||
            node.tagName() == "H1" || node.tagName() == "H2" || node.tagName() == "H3" ||
            node.tagName() == "H4" || node.tagName() == "H5" || node.tagName() == "H6"
        if (isContentTag && isElementWithoutContent(node)) {
          node = removeAndGetNext(node)
          continue
        }

        if (DEFAULT_TAGS_TO_SCORE.contains(node.tagName())) {
          elementsToScore.add(node)
        }

        // Turn all divs that don't have children block level elements into p's
        if (node.tagName() == "DIV") {
          // Put phrasing content into paragraphs.
          var p: Element? = null
          var childNode = node.firstChild()

          while (childNode != null) {
            val nextSibling = childNode.nextSibling()
            if (isPhrasingContent(childNode)) {
              if (p != null) {
                p.appendChild(childNode)
              } else if (!isWhiteSpace(childNode)) {
                p = document.createElement("p")
                childNode.replaceWith(p)
                p.appendChild(childNode)
              }
            } else if (p != null) {
              while (p.lastChild() != null && isWhiteSpace(p.lastChild()!!)) {
                p.lastChild()!!.remove()
              }
              p = null
            }
            childNode = nextSibling
          }
        }

        // Sites like http://mobile.slate.com encloses each paragraph with a DIV
        // element. DIVs with only a P element inside and no text content can be
        // safely converted into plain P elements to avoid confusing the scoring
        // algorithm with DIVs with are, in practice, paragraphs.
        if (hasSingleTagInsideElement(node, "P") && getLinkDensity(node) < 0.25) {
          val newNode = node.child(0)
          newNode.replaceWith(node)
          node = newNode
          elementsToScore.add(node)
        } else if (!hasChildBlockElement(node)) {
          node = setNodeTag(node, "P")
          elementsToScore.add(node)
        }

        node = getNextNode(node)
      }

      /**
       * Loop through all paragraphs, and assign a score to them based on how content-y they look.
       * Then add their score to their parent node.
       *
       * A score is determined by things like number of commas, class names, etc. Maybe eventually link density.
       */
      val candidates = mutableListOf<Element>()
      elementsToScore.forEach { elementToScore ->
        if (elementToScore.parentNode() == null) {
          return@forEach
        }

        // If this paragraph is less than 25 characters, don't even count it.
        val innerText = getInnerText(elementToScore)
        if (innerText.length < 25) {
          return@forEach
        }

        // Exclude nodes with no ancestor
        val ancestors = getNodeAncestors(elementToScore, 5)
        if (ancestors.isEmpty()) {
          return@forEach
        }

        var contentScore = 0

        // Add a point for the paragraph itself as a base
        contentScore += 1

        // Add points for any commas within this paragraph
        contentScore += innerText.split(COMMAS).size

        // For every 100 characters in this paragraph, add another point. Up to 3 points
        contentScore += min(floor(innerText.length / 100.0).roundToInt(), 3)

        // Initialize and score ancestors
        ancestors.forEachIndexed { level, ancestor ->
          if (!ancestor.hasParent() || ancestor.tagName().isBlank()) {
            return@forEachIndexed
          }

          val ancestorContentScore = readabilityData[ancestor]
          if (ancestorContentScore == null) {
            initializeNode(ancestor)
            candidates.add(ancestor)
          }

          // Node score divider:
          // - parent:             1 (no division)
          // - grandparent:        2
          // - great grandparent+: ancestor level * 3
          val scoreDivider = when (level) {
            0 -> 1
            1 -> 2
            else -> level * 3
          }

          readabilityData[ancestor] = ((ancestorContentScore ?: ContentScore.ZERO) + contentScore) / scoreDivider
        }
      }

      // After we've calculated scores, loop through all the possible
      // candidate nodes we found and find the one with the highest score
      val topCandidates = mutableListOf<Element>()

      for (candidate in candidates) {
        val candidateContentScore = readabilityData[candidate] ?: ContentScore.ZERO

        // Scale score based on link density
        readabilityData[candidate] = candidateContentScore * (1 - getLinkDensity(candidate)).roundToInt()

        // Insert into top candidates if eligible
        for (i in 0 until options.nbTopCandidate) {
          val aTopCandidate = topCandidates.getOrNull(i)
          if (
            aTopCandidate == null ||
            candidateContentScore > readabilityData[aTopCandidate]
          ) {
            topCandidates.add(i, candidate)
            if (topCandidates.size > options.nbTopCandidate) {
              topCandidates.removeAt(topCandidates.lastIndex)
            }
            break
          }
        }
      }

      var topCandidate: Element? = topCandidates.firstOrNull()
      var neededToCreateTopCandidate = false
      var parentOfTopCandidate: Element?

      // If we still have no top candidate, just use the body as a last resort.
      // We also have to copy the body node, so it is something we can modify.
      if (topCandidate == null || topCandidate.tagName() == "BODY") {
        // Move all the page's children into topCandidate
        topCandidate = document.createElement("DIV")
        neededToCreateTopCandidate = true
        // Move everything (not just elements, also text nodes etc.) into the container,
        // so we even include text directly in the body:
        while (page.firstChild() != null) {
          topCandidate.appendChild(page.firstChild()!!)
        }

        page.appendChild(topCandidate)

        initializeNode(topCandidate)
      } else {
        // Find a better top candidate if it contains (at least three) nodes which belong to `topCandidates` array
        // and whose scores are quite closed with current `topCandidate` node.
        val alternativeCandidateAncestors = mutableListOf<List<Element>>()
        for (i in 1 until topCandidates.size) {
          val currentCandidate = topCandidates[i]
          val currentScore = getContentScore(currentCandidate)
          val topCandidateScore = getContentScore(topCandidate)

          if ((currentScore / topCandidateScore).contentScore >= 0.75) {
            alternativeCandidateAncestors.add(getNodeAncestors(currentCandidate))
          }
        }

        val MINIMUM_TOPCANDIDATES = 3
        if (alternativeCandidateAncestors.size >= MINIMUM_TOPCANDIDATES) {
          parentOfTopCandidate = topCandidate.parent()
          while (parentOfTopCandidate != null && parentOfTopCandidate.tagName() != "BODY") {
            var listsContainingThisAncestor = 0
            for (ancestorIndex in alternativeCandidateAncestors.indices) {
              if (listsContainingThisAncestor >= MINIMUM_TOPCANDIDATES) break
              if (alternativeCandidateAncestors[ancestorIndex].contains(parentOfTopCandidate)) {
                listsContainingThisAncestor++
              }
            }

            if (listsContainingThisAncestor >= MINIMUM_TOPCANDIDATES) {
              topCandidate = parentOfTopCandidate
              break
            }

            parentOfTopCandidate = parentOfTopCandidate.parent()
          }
        }

        if (readabilityData[topCandidate] == null && topCandidate != null) {
          initializeNode(topCandidate)
        }

        // Because of our bonus system, parents of candidates might have scores
        // themselves. They get half of the node. There won't be nodes with higher
        // scores than our topCandidate, but if we see the score going *up* in the first
        // few steps up the tree, that's a decent sign that there might be more content
        // lurking in other places that we want to unify in. The sibling stuff
        // below does some of that - but only if we've looked high enough up the DOM
        // tree.
        parentOfTopCandidate = topCandidate?.parent()
        var lastScore = getContentScore(topCandidate)
        // The score shouldn't get too low
        val scoreThreshold = lastScore / 3
        while (parentOfTopCandidate?.tagName() != "BODY") {
          if (readabilityData[parentOfTopCandidate] == null) {
            parentOfTopCandidate = parentOfTopCandidate?.parent()
            continue
          }

          val parentScore = getContentScore(parentOfTopCandidate)
          if (parentScore < scoreThreshold) {
            break
          }

          if (parentScore > lastScore) {
            // Alright! We found a better parent to use.
            topCandidate = parentOfTopCandidate
            break
          }

          lastScore = getContentScore(parentOfTopCandidate)
          parentOfTopCandidate = parentOfTopCandidate?.parent()
        }

        // If the top candidate is the only child, use parent instead. This will help sibling
        // joining logic when adjacent content is actually located in parent's sibling node.
        parentOfTopCandidate = topCandidate?.parent()
        while (parentOfTopCandidate?.tagName() != "BODY" && !parentOfTopCandidate?.children().isNullOrEmpty()) {
          topCandidate = parentOfTopCandidate
          parentOfTopCandidate = topCandidate?.parent()
        }

        if (readabilityData[topCandidate] == null && topCandidate != null) {
          initializeNode(topCandidate)
        }
      }

      // Now that we have the top candidate, look through its siblings for content
      // that might also be related. Things like preambles, content split by ads
      // that we removed, etc.
      var articleContent = document.createElement("DIV")
      if (isPaging)
        articleContent.attr("id", "readability-content")

      val siblingScoreThreshold = max(10.0, getContentScore(topCandidate).contentScore * 0.2)
      // Keep potential top candidate's parent node to try to get text direction of it later.
      parentOfTopCandidate = topCandidate?.parent()
      var siblings = parentOfTopCandidate?.children().orEmpty()
      // TODO: Check if this loop can be improved to match the JS version
      var s = 0
      while (s < siblings.size) {
        var sibling = siblings[s]
        var append = false

        if (sibling == topCandidate) {
          append = true
        } else {
          var contentBonus = 0.0

          // Give a bonus if sibling nodes and top candidates have the example same class name
          if (sibling.className() == topCandidate?.className() && topCandidate.className() != "")
            contentBonus += getContentScore(topCandidate).contentScore * 0.2

          if (readabilityData[sibling] != null &&
            ((getContentScore(sibling).contentScore + contentBonus) >= siblingScoreThreshold)
          ) {
            append = true
          } else if (sibling.nodeName() == "P") {
            val linkDensity = getLinkDensity(sibling)
            val nodeContent = getInnerText(sibling)
            val nodeLength = nodeContent.length

            if (nodeLength > 80 && linkDensity < 0.25) {
              append = true
            } else if (nodeLength in 1..79 && linkDensity == 0.0 && nodeContent.contains("\\.( |$)".toRegex())) {
              append = true
            }
          }
        }

        if (append) {
          if (ALTER_TO_DIV_EXCEPTIONS.indexOf(sibling.nodeName()) == -1) {
            // We have a node that isn't a common block level element, like a form or td tag.
            // Turn it into a div, so it doesn't get filtered out later by accident.
            sibling = setNodeTag(sibling, "DIV")
          }

          articleContent.appendChild(sibling)
          // Fetch children again to make it compatible
          // with DOM parsers without live collection support.
          siblings = parentOfTopCandidate?.children().orEmpty();
          // siblings is a reference to the children array, and
          // sibling is removed from the array when we call appendChild().
          // As a result, we must revisit this index since the nodes
          // have been shifted.
          s += 1
        }
      }

      // So we have all the content that we need. Now we clean it up for presentation.
      prepArticle(articleContent)

      if (neededToCreateTopCandidate) {
        // We already created a fake div thing, and there wouldn't have been any siblings left
        // for the previous loop, so there's no point trying to create a new div, and then
        // move all the children over. Just assign IDs and class names here. No need to append
        // because that already happened anyway.
        topCandidate?.attr("id", "readability-page-1")
        topCandidate?.addClass("page")
      } else {
        val div = document.createElement("DIV")
        div.attr("id", "readability-page-1")
        div.addClass("page")
        while (articleContent.firstChild() != null) {
          div.appendChild(articleContent.firstChild()!!)
        }
        articleContent.appendChild(div)
      }

      var parseSuccessful = true
      // Now that we've gone through the full algorithm, check to see if
      // we got any meaningful content. If we didn't, we may need to re-run
      // grabArticle with different flags set. This gives us a higher likelihood of
      // finding the content, and the sieve approach gives us a higher likelihood of
      // finding the -right- content.
      val textLength = getInnerText(articleContent, true).length
      if (textLength < options.charThreshold) {
        parseSuccessful = false
        page.html(pageCacheHtml)

        val attempts = mutableListOf<Attempt>()

        if (flagIsActive(FLAG_STRIP_UNLIKELYS)) {
          removeFlag(FLAG_STRIP_UNLIKELYS)
          attempts.add(Attempt(articleContent, textLength))
        } else if (flagIsActive(FLAG_WEIGHT_CLASSES)) {
          removeFlag(FLAG_WEIGHT_CLASSES)
          attempts.add(Attempt(articleContent, textLength))
        } else if (flagIsActive(FLAG_CLEAN_CONDITIONALLY)) {
          removeFlag(FLAG_CLEAN_CONDITIONALLY)
          attempts.add(Attempt(articleContent, textLength))
        } else {
          attempts.add(Attempt(articleContent, textLength))
          // No luck after removing flags, sort attempts by text length
          attempts.sortByDescending { it.length }

          // Check if we have any meaningful content
          if (attempts.firstOrNull()?.length == 0) {
            return null
          }

          articleContent = attempts.first().articleContent
          parseSuccessful = true
        }
      }

      if (parseSuccessful) {
        output["content"] = articleContent
        return output
      }
    }
  }

  /**
   * Prepare the article node for display. Clean out any inline styles,
   * iframes, forms, strip extraneous <p> tags, etc.
   *
   * @param articleContent
   * @return void
   **/
  private fun prepArticle(articleContent: Element) {
    cleanStyles(articleContent)

    // Check for data tables before we continue, to avoid removing items in
    // those tables, which will often be isolated even though they're
    // visually linked to other content-ful elements (text, images, etc.).
    markDataTables(articleContent)

    fixLazyImages(articleContent)

    // Clean out junk from the article content
    cleanConditionally(articleContent, "form")
    cleanConditionally(articleContent, "fieldset")
    clean(articleContent, "object")
    clean(articleContent, "embed")
    clean(articleContent, "footer")
    clean(articleContent, "link")
    clean(articleContent, "aside")

    // Clean out elements with little content that have "share" in their id/class combinations from final top candidates,
    // which means we don't remove the top candidates even they have "share".

    val shareElementThreshold = DEFAULT_CHAR_THRESHOLD;

    articleContent.children().forEach { topCandidate ->
      cleanMatchedNodes(topCandidate) { node, matchString ->
        return@cleanMatchedNodes SHARE_ELEMENTS.containsMatchIn(matchString) && node.text().length < shareElementThreshold
      }
    }

    clean(articleContent, "iframe")
    clean(articleContent, "input")
    clean(articleContent, "textarea")
    clean(articleContent, "select")
    clean(articleContent, "button")
    cleanHeaders(articleContent)

    // Do these last as the previous stuff may have removed junk
    // that will affect these
    cleanConditionally(articleContent, "table")
    cleanConditionally(articleContent, "ul")
    cleanConditionally(articleContent, "div")

    // replace H1 with H2 as H1 should be only title that is displayed separately
    replaceNodeTags(getAllNodesWithTag(articleContent, listOf("h1")), "h2")

    // Remove extra paragraphs
    removeNodes(getAllNodesWithTag(articleContent, listOf("p"))) { paragraph ->
      val imgCount = paragraph.getElementsByTag("img").size
      val embedCount = paragraph.getElementsByTag("embed").size
      val objectCount = paragraph.getElementsByTag("object").size
      // At this point, nasty iframes have been removed, only remain embedded video ones.
      val iframeCount = paragraph.getElementsByTag("iframe").size
      val totalCount = imgCount + embedCount + objectCount + iframeCount

      return@removeNodes totalCount == 0 && getInnerText(paragraph, false).isBlank()
    }

    getAllNodesWithTag(articleContent, listOf("br")).forEach { br ->
      val next = nextNode(br.nextSibling())
      if (next != null && next.nodeName() == "P") {
        br.remove()
      }
    }

    // Remove single-cell tables
    getAllNodesWithTag(articleContent, listOf("table")).forEach { table ->
      val tbody = if (hasSingleTagInsideElement(table, "TBODY")) table.children().first() else table
      if (tbody == null) return@forEach

      if (hasSingleTagInsideElement(tbody, "TR")) {
        val row = tbody.children().first() ?: return@forEach

        if (hasSingleTagInsideElement(row, "TD")) {
          var cell = row.children().first() ?: return@forEach

          cell = setNodeTag(cell, if (cell.children().all { isPhrasingContent(it) }) "P" else "DIV")
          table.replaceWith(cell)
        }
      }
    }
  }

  /**
   * Clean out spurious headers from an Element.
   *
   * @param element
   **/
  private fun cleanHeaders(element: Element) {
    val headingNodes = getAllNodesWithTag(element, listOf("h1", "h2"))
    removeNodes(headingNodes) { node ->
      val shouldRemove = getClassWeight(node) < 0
      if (shouldRemove) {
        println("Removing header with low class weight: $node")
      }
      shouldRemove
    }
  }

  /**
   * Clean out elements that match the specified conditions
   *
   * @param element
   * @param filter predicate to determines whether a node should be removed
   * @return void
   **/
  private fun cleanMatchedNodes(element: Element, filter: (Element, String) -> Boolean) {
    val endOfSearchMarkerNode = getNextNode(element, true)
    var next = getNextNode(element)
    while (next != null && next != endOfSearchMarkerNode) {
      if (filter(next, next.className() + " " + next.id())) {
        next = removeAndGetNext(next)
      } else {
        next = getNextNode(next)
      }
    }
  }

  /**
   * Clean a node of all elements of type "tag".
   * (Unless it's a YouTube/vimeo video. People love movies.)
   *
   * @param element
   * @param tag to clean
   **/
  private fun clean(element: Element, tag: String) {
    val isEmbed = listOf("object", "embed", "iframe").contains(tag)

    removeNodes(getAllNodesWithTag(element, listOf(tag))) { node ->
      if (isEmbed) {
        for (attr in node.attributes()) {
          if (options.allowedVideoRegex.containsMatchIn(attr.value)) {
            return@removeNodes false
          }
        }

        if (node.tagName() == "object" && options.allowedVideoRegex.containsMatchIn(node.html())) {
          return@removeNodes false
        }
      }

      return@removeNodes true
    }
  }

  /**
   * Clean an element of all tags of type "tag" if they look fishy.
   * "Fishy" is an algorithm based on content length, class names, link density, number of images & embeds, etc.
   **/
  private fun cleanConditionally(element: Element, tag: String) {
    if (!flagIsActive(FLAG_CLEAN_CONDITIONALLY))
      return

    // Gather counts for other typical elements embedded within.
    // Traverse backwards so we can remove nodes at the same time
    // without effecting the traversal.
    //
    // TODO: Consider taking into account original contentScore here.
    removeNodes(getAllNodesWithTag(element, listOf(tag))) { node ->
      // First check if this node IS data table, in which case don't remove it.
      val isDataTable: (Element?) -> Boolean = { element -> element?.hasAttr("readabilityDataTable") ?: false }

      var isList = tag == "ul" || tag == "ol"
      if (!isList) {
        var listLength = 0
        val listNodes = getAllNodesWithTag(node, listOf("ul", "ol"))
        for (listNode in listNodes) {
          listLength += getInnerText(listNode).length
        }

        val innerTextLength = getInnerText(node).length
        isList = if (innerTextLength > 0) {
          (listLength / innerTextLength) > 0.9
        } else {
          false
        }
      }

      if (tag == "table" && isDataTable(node)) {
        return@removeNodes false
      }

      // Next check if we're inside a data table, in which case don't remove it as well.
      if (hasAncestorTag(node, "table", -1, isDataTable)) {
        return@removeNodes false
      }

      if (hasAncestorTag(node, "code")) {
        return@removeNodes false
      }

      val weight = getClassWeight(node)

      val contentScore = 0

      if (weight + contentScore < 0) {
        return@removeNodes true
      }

      if (getCharCount(node, ",") < 10) {
        // If there are not very many commas, and the number of
        // non-paragraph elements is more than paragraphs or other
        // ominous signs, remove the element.
        val p = node.getElementsByTag("p").size
        val img = node.getElementsByTag("img").size
        val li = node.getElementsByTag("li").size - 100
        val input = node.getElementsByTag("input").size
        val headingDensity = getTextDensity(node, listOf("h1", "h2", "h3", "h4", "h5", "h6"))

        var embedCount = 0
        val embeds = getAllNodesWithTag(node, listOf("object", "embed", "iframe"))

        for (embed in embeds) {
          for (attribute in embed.attributes()) {
            if (options.allowedVideoRegex.containsMatchIn(attribute.value)) {
              return@removeNodes false
            }
          }

          if (embed.tagName() == "object" && options.allowedVideoRegex.containsMatchIn(embed.html())) {
            return@removeNodes false
          }

          embedCount++
        }

        val linkDensity = getLinkDensity(node)
        val contentLength = getInnerText(node).length

        val haveToRemove =
          (img > 1 && p / img < 0.5 && !hasAncestorTag(node, "figure")) ||
              (!isList && li > p) ||
              (input > (p / 3)) ||
              (!isList && headingDensity < 0.9 && contentLength < 25 && (img == 0 || img > 2) && !hasAncestorTag(
                node,
                "figure"
              )) ||
              (!isList && weight < 25 && linkDensity > 0.2) ||
              (weight >= 25 && linkDensity > 0.5) ||
              ((embedCount == 1 && contentLength < 75) || embedCount > 1)

        if (isList && haveToRemove) {
          for (child in node.children()) {
            if (child.children().size > 1) {
              return@removeNodes haveToRemove
            }
          }
          val liCount = node.getElementsByTag("li").size
          if (img == liCount) {
            return@removeNodes false
          }
        }

        return@removeNodes haveToRemove
      }

      return@removeNodes false
    }
  }

  private fun getTextDensity(elem: Element, tags: List<String>): Double {
    val textLength = getInnerText(elem, true).length
    if (textLength == 0) {
      return 0.0
    }
    var childrenLength = 0
    val children = getAllNodesWithTag(elem, tags)
    children.forEach { child ->
      childrenLength += getInnerText(child, true).length
    }
    return childrenLength.toDouble() / textLength.toDouble()
  }

  private fun getCharCount(e: Element, s: String?): Int {
    val separator = s ?: ","
    return getInnerText(e).split(separator).size - 1
  }

  /* convert images and figures that have properties like data-src into images that can be loaded without JS */
  private fun fixLazyImages(element: Element) {
    getAllNodesWithTag(element, listOf("img", "picture", "figure")).forEach { elem ->
      // In some sites (e.g. Kotaku), they put 1px square image as base64 data uri in the src attribute.
      // So, here we check if the data uri is too short, just might as well remove it.
      val src = elem.attr("src")
      if (src.isNotBlank() && B64_DATA_URL.containsMatchIn(src)) {
        // Make sure it's not SVG, because SVG can have a meaningful image in under 133 bytes.
        val match = B64_DATA_URL.matchEntire(src)
        if (match != null && match.groupValues[1] == "image/svg+xml") {
          return
        }

        // Make sure this element has other attributes which contains image.
        // If it doesn't, then this src is important and shouldn't be removed.
        var srcCouldBeRemoved = false
        for (attr in elem.attributes()) {
          if (attr.key == "src") {
            continue
          }

          if ("\\.(jpg|jpeg|png|webp)".toRegex(RegexOption.IGNORE_CASE).containsMatchIn(attr.value)) {
            srcCouldBeRemoved = true
            break
          }
        }

        // Here we assume if image is less than 100 bytes (or 133B after encoded to base64)
        // it will be too small, therefore it might be placeholder image.
        if (srcCouldBeRemoved) {
          val base64Starts = src.indexOf("base64", ignoreCase = true) + 7
          val b64length = src.length - base64Starts

          if (b64length < 133) {
            elem.removeAttr("src")
          }
        }

        for (attr in elem.attributes()) {
          if (attr.key == "src" || attr.key == "srcset" || attr.key == "alt") {
            continue
          }
          val attrValue = attr.value

          var copyTo: String? = null
          val tagName = elem.tagName().uppercase()

          val regexSrcset = Regex("\\.(jpg|jpeg|png|webp)\\s+\\d")
          val regexSrc = Regex("^\\s*\\S+\\.(jpg|jpeg|png|webp)\\S*\\s*$")

          if (regexSrcset.matches(attrValue)) {
            copyTo = "srcset"
          } else if (regexSrc.matches(attrValue)) {
            copyTo = "src"
          }

          if (copyTo != null) {
            //if this is an img or picture, set the attribute directly
            if (tagName == "IMG" || tagName == "PICTURE") {
              elem.attr(copyTo, attrValue)
            } else if (tagName == "FIGURE" && elem.select("img, picture").isEmpty()) {
              //if the item is a <figure> that does not contain an image or picture, create one and place it inside the figure
              //see the nytimes-3 testcase for an example in the readability.js
              val img = elem.ownerDocument()?.createElement("img")
              img?.attr(copyTo, attrValue)
              if (img != null) {
                elem.appendChild(img)
              }
            }
          }
        }
      }
    }
  }

  /**
   * Look for 'data' (as opposed to 'layout') tables, for which we use
   * similar checks as
   * https://searchfox.org/mozilla-central/rev/f82d5c549f046cb64ce5602bfd894b7ae807c8f8/accessible/generic/TableAccessible.cpp#19
   */
  private fun markDataTables(root: Element) {
    val tables = root.getElementsByTag("table")
    for (table in tables) {
      val role = table.attr("role")
      if (role == "presentation") {
        table.attr("readabilityDataTable", false)
        continue
      }

      val datatable = table.attr("datatable")
      if (datatable == "0") {
        table.attr("readabilityDataTable", false)
      }

      val summary = table.attr("summary")
      if (summary.isNotBlank()) {
        table.attr("readabilityDataTable", true)
        continue
      }

      val caption = table.getElementsByTag("caption")[0]
      if (caption.hasChildNodes()) {
        table.attr("readabilityDataTable", true)
        continue
      }

      // If the table has a descendant with any of these tags, consider a data table:
      val dataTableDescendants = arrayOf("col", "colgroup", "tfoot", "thead", "th")
      val descendantExists: (String) -> Boolean = { tag ->
        table.getElementsByTag(tag).isNotEmpty()
      }

      if (dataTableDescendants.any(descendantExists)) {
        table.attr("readabilityDataTable", true)
        continue
      }

      // Nested tables indicate a layout table:
      if (table.getElementsByTag("table").isNotEmpty()) {
        table.attr("readabilityDataTable", false)
        continue
      }

      val (rows, columns) = getRowAndColumnCount(table)
      if (rows >= 10 || columns > 4) {
        table.attr("readabilityDataTable", true)
        continue
      }

      // Now just go by size entirely
      table.attr("readabilityDataTable", (rows * columns) > 10)
    }
  }

  private fun getRowAndColumnCount(table: Element): Pair<Int, Int> {
    var rows = 0
    var columns = 0

    val trs = table.getElementsByTag("tr")
    for (tr in trs) {
      val rowspan = tr.attr("rowspan").toIntOrNull() ?: 0
      rows += rowspan ?: 1

      var columnsInThisRow = 0
      val cells = tr.getElementsByTag("td")
      for (cell in cells) {
        val colspan = cell.attr("colspan").toIntOrNull() ?: 0
        columnsInThisRow += colspan ?: 1
      }
      columns = max(columns, columnsInThisRow)
    }

    return rows to columns
  }

  /**
   * Remove the style attribute on every e and under.
   * TODO: Test if getElementsByTagName(*) is faster.
   *
   * @param element
   * @return void
   **/
  private fun cleanStyles(element: Element) {
    if (element.tagName().lowercase() == "svg") {
      return
    }

    // Remove `style` and deprecated presentation attributes
    for (attr in PRESENTATIONAL_ATTRIBUTES) {
      element.removeAttr(attr)
    }

    if (DEPRECATED_SIZE_ATTRIBUTE_ELEMS.contains(element.tagName())) {
      element.removeAttr("width")
      element.removeAttr("height")
    }

    var cur = element.firstElementChild()
    while (cur != null) {
      cleanStyles(cur)
      cur = cur.nextElementSibling()
    }
  }

  private fun initializeNode(node: Element) {
    readabilityData[node] = ContentScore.ZERO

    when (node.tagName()) {
      "DIV" -> {
        readabilityData[node] = ContentScore(5)
      }

      "PRE", "TD", "BLOCKQUOTE" -> {
        readabilityData[node] = ContentScore(3)
      }

      "ADDRESS", "OL", "UL", "DL", "DD", "DT", "LI", "FORM" -> {
        readabilityData[node] = getContentScore(node) - 3
      }

      "H1", "H2", "H3", "H4", "H5", "H6", "TH" -> {
        readabilityData[node] = getContentScore(node) - 5
      }
    }

    readabilityData[node] = getContentScore(node) + getClassWeight(node)
  }

  private fun getClassWeight(element: Element): Int {
    if (!flagIsActive(FLAG_WEIGHT_CLASSES)) {
      return 0
    }

    var weight = 0

    element.classNames().forEach { className ->
      if (NEGATIVE.containsMatchIn(className)) weight -= 25
      if (POSITIVE.containsMatchIn(className)) weight += 25
    }

    element.id().let { id ->
      if (NEGATIVE.matches(id)) weight -= 25
      if (POSITIVE.matches(id)) weight += 25
    }

    return weight
  }

  private fun getNodeAncestors(node: Element, maxDepth: Int = 0): List<Element> {
    val ancestors = mutableListOf<Element>()
    var currentDepth = 0
    var currentNode: Element? = null

    while (currentNode?.hasParent() == true && (maxDepth == 0 || currentDepth < maxDepth)) {
      ancestors.add(node.parent()!!)
      currentNode = node.parent()!!
      currentDepth++
    }

    return ancestors.reversed()
  }

  /**
   * Determine whether element has any children block level elements.
   *
   * @param element
   */
  private fun hasChildBlockElement(
    element: Element,
    blockTags: Set<String> = setOf("div", "p", "pre", "table", "blockquote")
  ): Boolean {
    return element.children().any { child ->
      blockTags.contains(child.tagName()) || hasChildBlockElement(child, blockTags)
    }
  }

  /**
   * Get the density of links as a percentage of the content
   * This is the amount of text that is inside a link divided by the total text in the node.
   *
   * @param element
   * @return number (float)
   **/
  private fun getLinkDensity(element: Element): Double {
    val textLength = getInnerText(element).length
    if (textLength == 0) return 0.0

    var linkLength = 0.0

    element.select("a").forEach { linkNode ->
      val href = linkNode.attr("href")
      val coefficient = if (href.isNotEmpty() && HAS_URL.containsMatchIn(href)) 0.3 else 1.0
      linkLength += getInnerText(linkNode).length * coefficient
    }

    return linkLength / textLength
  }

  private fun hasAncestorTag(
    node: Element,
    tagName: String,
    maxDepth: Int = 3,
    predicate: ((Element?) -> Boolean)? = null
  ): Boolean {
    val targetTagName = tagName.uppercase()
    var currentDepth = 0
    var currentNode: Element? = node

    while (currentNode?.hasParent() == true) {
      if (maxDepth in 1..<currentDepth) {
        return false
      }

      if (currentNode.parent()?.tagName() == targetTagName &&
        (predicate == null || predicate(currentNode.parent()))
      ) {
        return true
      }

      currentNode = currentNode.parent()
      currentDepth++
    }

    return false
  }

  private fun headerDuplicatesTitle(node: Element, articleTitle: String): Boolean {
    if (node.tagName() != "H1" && node.tagName() != "H2") {
      return false
    }

    val heading = getInnerText(node, false)
    return textSimilarity(articleTitle, heading) > 0.75
  }

  private fun textSimilarity(textA: String, textB: String): Double {
    val tokensA = textA.lowercase().split(TOKENIZE).filter { it.isNotBlank() }
    val tokensB = textB.lowercase().split(TOKENIZE).filter { it.isNotBlank() }
    if (tokensA.isEmpty() || tokensB.isEmpty()) {
      return 0.0
    }

    val uniqTokensB = tokensB.filter { !tokensA.contains(it) }
    val distanceB = uniqTokensB.joinToString(" ").length.toDouble()

    return 1.0 - distanceB
  }

  private fun checkByline(node: Element, byline: String?, matchString: String): Boolean {
    if (!byline.isNullOrBlank()) {
      return false
    }

    val rel = node.attr("rel")
    val itemprop = node.attr("itemprop")

    return (rel == "author" || itemprop.contains("author") || BYLINE.containsMatchIn(matchString)) && isValidByline(node.text())
  }

  private fun isValidByline(byline: String?): Boolean {
    if (!byline.isNullOrBlank()) {
      val currentByline = byline.trim()
      return currentByline.isNotEmpty() && byline.length < 100
    }
    return false
  }

  private fun isProbablyVisible(node: Element): Boolean {
    return (
        // Checking for "display: none" or "visibility: hidden" styles:
        !node.attr("style").lowercase().contains("display: none") && !node.attr("style").lowercase()
          .contains("visibility: hidden") && !node.hasAttr("hidden") && (!node.hasAttr("aria-hidden") || node.attr("aria-hidden") != "true" ||
            node.classNames().contains("fallback-image"))
        )
  }

  private fun flagIsActive(flatToCheck: Int): Boolean {
    return (flags and flatToCheck) > 0
  }

  private fun getContentScore(node: Element?): ContentScore {
    if (node == null) ContentScore.ZERO
    return readabilityData[node] ?: ContentScore.ZERO
  }

  private fun removeFlag(flag: Int) {
    this.flags = flags and flag.inv()
  }
}