package dev.sasikanth.readability.models

data class Article(
  val title: String,
  val content: String?,
  val html: String?,
  val excerpt: String,
  val lang: String?,
)
