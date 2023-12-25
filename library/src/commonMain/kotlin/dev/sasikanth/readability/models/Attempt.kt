package dev.sasikanth.readability.models

import com.fleeksoft.ksoup.nodes.Element

data class Attempt(
  val articleContent: Element,
  val length: Int
)
