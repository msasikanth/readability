package dev.sasikanth.readability.models

data class Metadata(
  val title: String,
  val byline: String?,
  val excerpt: String?,
  val siteName: String?,
  val publishedTime: String?
)
