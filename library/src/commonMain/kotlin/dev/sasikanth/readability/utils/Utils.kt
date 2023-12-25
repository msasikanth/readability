package dev.sasikanth.readability.utils

import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode

// The commented out elements qualify as phrasing content but tend to be
// removed by readability when put into paragraphs, so we ignore them here.
private val PHRASING_ELEMS = listOf(
  // "CANVAS", "IFRAME", "SVG", "VIDEO",
  "ABBR", "AUDIO", "B", "BDO", "BR", "BUTTON", "CITE", "CODE", "DATA",
  "DATALIST", "DFN", "EM", "EMBED", "I", "IMG", "INPUT", "KBD", "LABEL",
  "MARK", "MATH", "METER", "NOSCRIPT", "OBJECT", "OUTPUT", "PROGRESS", "Q",
  "RUBY", "SAMP", "SCRIPT", "SELECT", "SMALL", "SPAN", "STRONG", "SUB",
  "SUP", "TEXTAREA", "TIME", "VAR", "WBR"
)

// These are the list of HTML entities that need to be escaped.
private val HTML_ESCAPE_MAP: Map<String, String> = mapOf(
  "lt" to "<",
  "gt" to ">",
  "amp" to "&",
  "quot" to "\"",
  "apos" to "'",
)

internal fun isElementWithoutContent(node: Element): Boolean {
  return node.text().trim().isBlank() && (node.children().size == 0 ||
      node.children().size == node.getElementsByTag("br").size + node.getElementsByTag("hr").size)
}

/**
 * Determine if a node qualifies as phrasing content.
 * https://developer.mozilla.org/en-US/docs/Web/Guide/HTML/Content_categories#Phrasing_content
 **/
internal fun isPhrasingContent(node: Node): Boolean {
  return when {
    node is TextNode -> true
    PHRASING_ELEMS.contains(node.nodeName()) -> true
    node.nodeName() in listOf("A", "DEL", "INS") -> node.childNodes().all { isPhrasingContent(it) }
    else -> false
  }
}

internal fun isWhiteSpace(node: Node): Boolean {
  return when (node) {
    is TextNode -> node.text().trim().isBlank()
    is Element -> node.nodeName() == "BR"
    else -> false
  }
}

/**
 * Check if this node has only whitespace and a single element with given tag
 * Returns false if the DIV node contains non-empty text nodes
 * or if it contains no element with given tag or more than 1 element.
 **/
internal fun hasSingleTagInsideElement(element: Element?, tagName: String): Boolean {
  if (element == null) return false

  // There should be exactly 1 element child with given tag
  if (element.children().size != 1 || element.children()[0].tagName() != tagName) {
    return false
  }

  // And there should be no text node with real content
  return !element.childNodes().any { node ->
    node is TextNode && HAS_CONTENT.containsMatchIn(node.text())
  }
}

internal fun setNodeTag(node: Element, tagName: String): Element {
  return node.tagName(tagName)
}

/**
 * Get the inner text of a node - cross browser compatibly.
 * This also strips out any excess whitespace to be found.
 */
internal fun getInnerText(element: Element, normalizeSpaces: Boolean = true): String {
  val text = element.text().trim()

  if (normalizeSpaces) {
    return text.replace(NORMALIZE, " ")
  }

  return text
}

internal fun removeAndGetNext(node: Element): Element? {
  val nextNode = getNextNode(node, true)
  node.remove()
  return nextNode
}

internal fun getAllNodesWithTag(node: Element, tagNames: List<String>): List<Element> {
  if (node.select(tagNames.joinToString(",")).isNotEmpty()) {
    return node.select(tagNames.joinToString(","))
  }

  return tagNames.flatMap { tagName -> node.getElementsByTag(tagName) }
}

/**
 * Traverse the DOM from node to node, starting at the node passed in.
 * Pass true for the second parameter to indicate this node itself
 * (and its kids) are going away, and we want the next node over.
 *
 * Calling this in a loop will traverse the DOM depth-first.
 */
internal fun getNextNode(node: Element, ignoreSelfAndKids: Boolean = true): Element? {
  return when {
    // First check for kids if those aren't being ignored
    !ignoreSelfAndKids && node.children().isNotEmpty() -> node.child(0)
    // Then for siblings...
    node.nextElementSibling() != null -> node.nextElementSibling()!!
    // And finally, move up the parent chain *and* find a sibling
    // (because this is depth-first traversal, we will have already
    // seen the parent nodes themselves).
    else -> {
      var currentNode: Element? = node
      while (currentNode != null && currentNode.nextElementSibling() == null) {
        currentNode = currentNode.parent()
      }
      currentNode?.nextElementSibling()
    }
  }
}

/**
 * Finds the next node, starting from the given node, and ignoring
 * whitespace in between. If the given node is an element, the same node is
 * returned.
 */
internal fun nextNode(node: Node?): Node? {
  var next: Node? = node
  while (next != null && (next !is Element || WHITESPACE.matches(next.text()))) {
    next = next.nextSibling()
  }

  return next
}

internal fun removeNodes(elements: List<Element>, predicate: ((Element) -> Boolean)? = null) {
  for (i in elements.indices.reversed()) {
    val node = elements[i]
    val parentNode = node.parentNode()
    if (parentNode != null) {
      if (predicate == null || predicate(node)) {
        node.remove()
      }
    }
  }
}

/**
 * Iterates over a Elements, and calls _setNodeTag for each node.
 *
 * @param elements: list of nodes to operate on
 * @param tagName: new tag name
 *
 */
internal fun replaceNodeTags(elements: List<Element>, tagName: String) {
  elements.forEach { element ->
    setNodeTag(element, tagName)
  }
}

internal fun unescapeHtmlEntities(string: String?): String? {
  if (string.isNullOrBlank()) return string

  val htmlEscapeMap = HTML_ESCAPE_MAP
  return string.replace(Regex("&(quot|amp|apos|lt|gt);")) { matchResult ->
    htmlEscapeMap[matchResult.groupValues[1]] ?: matchResult.value
  }.replace(Regex("&#(?:x([0-9a-z]{1,4})|([0-9]{1,4}));")) { matchResult ->
    val hex = matchResult.groupValues[1]
    val numStr = matchResult.groupValues[2]
    val num = if (hex.isNotBlank()) hex.toInt(16) else numStr.toInt()
    num.toChar().toString()
  }
}
