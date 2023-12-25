package dev.sasikanth.readability

import java.awt.Desktop
import java.io.File
import java.io.FileWriter

fun main() {
  val html = """
    <figure>
      <img alt="Illustration of Amazon’s wordmark on an orange, black, and tan background made up of overlapping lines." src="https://cdn.vox-cdn.com/thumbor/bEECdLmZvlURnnqLlnH_r23j3Gw=/0x0:2040x1360/1310x873/cdn.vox-cdn.com/uploads/chorus_image/image/72999506/acastro_STK103__03.0.jpg" /> 
      <figcaption>Illustration by Alex Castro / The Verge</figcaption> 
    </figure>
    <p id="HjO7Ne">Earlier this year, Amazon <a href="https://www.theverge.com/2023/9/22/23885242/amazon-prime-tv-movies-streaming-ads-subscription-date">announced plans to start incorporating ads</a> into movies and TV shows streamed from its Prime Video service, and now the company has revealed a specific date when you’ll start seeing them: it’s January 29th. “This will allow us to continue investing in compelling content and keep increasing that investment over a long period of time,” the company said in an email to customers about the pending shift to “limited advertisements.” </p> 
    <p id="LaYS46">“We aim to have meaningfully fewer ads than linear TV and other streaming TV providers. No action is required from you, and there is no change to the current price of your Prime membership,” the company wrote. Customers have the option of paying an additional ${'$'}2.99 per month to keep...</p> <p> <a href="https://www.theverge.com/2023/12/26/24015595/amazon-prime-video-ads-coming-january-29">Continue reading&hellip;</a> </p>
  """.trimIndent()
  val article = Readability.parse(html = html)

  println("---")
  println("HTML:")
  println(article?.html)
  println("---")
  println("TEXT:")
  println(article?.content)
  println("---")
  println("---")
  println("EXCERPT")
  println(article?.excerpt)
  println("---")

  val file = File("test.html")

  val writer = FileWriter(file)
  article?.html?.let { writer.write(it) }
  writer.close()

  Desktop.getDesktop().browse(file.toURI())
}
