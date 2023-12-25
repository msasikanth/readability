package dev.sasikanth.readability.utils

val VIDEOS =
  "(?i)https?://(www\\.)?((dailymotion|youtube|youtube-nocookie|player\\.vimeo|v\\.qq)\\.com|(archive|upload\\.wikimedia)\\.org|player\\.twitch\\.tv)".toRegex()
var WHITESPACE = "^\\s*$".toRegex()
val NORMALIZE = """\\s{2,}""".toRegex()
val SRC_SET_URL = """(\\S+)(\\s+[\\d.]+[xw])?(\\s*(?:,|\$))""".toRegex(RegexOption.MULTILINE)
val HAS_CONTENT = "\\S$".toRegex()
val BYLINE = "byline|author|dateline|writtenby|p-author".toRegex(RegexOption.IGNORE_CASE)
val TOKENIZE = "\\W+".toRegex()
val HAS_URL = "^#.+".toRegex()
val COMMAS = "\\u002C|\\u060C|\\uFE50|\\uFE10|\\uFE11|\\u2E41|\\u2E34|\\u2E32|\\uFF0C".toRegex()
val POSITIVE =
  "article|body|content|entry|hentry|h-entry|main|page|pagination|post|text|blog|story".toRegex(RegexOption.IGNORE_CASE)
val NEGATIVE =
  "-ad-|hidden|^hid$| hid$| hid |^hid |banner|combx|comment|com-|contact|foot|footer|footnote|gdpr|masthead|media|meta|outbrain|promo|related|scroll|share|shoutbox|sidebar|skyscraper|sponsor|shopping|tags|tool|widget".toRegex(
    RegexOption.IGNORE_CASE
  )
val B64_DATA_URL = "^data:\\s*([^\\s;,]+)\\s*;\\s*base64\\s*,".toRegex(RegexOption.IGNORE_CASE)
val SHARE_ELEMENTS = """(\b|_)(share|sharedaddy)(\b|_)""".toRegex(RegexOption.IGNORE_CASE)
