package dev.sasikanth.readability.models

import kotlin.jvm.JvmInline

@JvmInline
value class ContentScore(val contentScore: Int) {

  companion object {
    val ZERO = ContentScore(0)
  }

  operator fun plus(other: Int): ContentScore {
    return ContentScore(contentScore + other)
  }

  operator fun minus(other: Int): ContentScore {
    return ContentScore(contentScore - other)
  }

  operator fun div(other: Int): ContentScore {
    return ContentScore(contentScore / other)
  }

  operator fun div(other: ContentScore): ContentScore {
    return ContentScore(contentScore / other.contentScore)
  }

  operator fun times(other: Int): ContentScore {
    return ContentScore(contentScore * other)
  }

  operator fun compareTo(other: ContentScore?): Int {
    return contentScore compareTo (other?.contentScore ?: 0)
  }
}
