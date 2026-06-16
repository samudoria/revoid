package dev.jadxmcp
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import java.io.File
fun main() {
    val jadx = JadxDecompiler(JadxArgs().apply { setInputFile(File("test-app/build/sample.jar")) })
    jadx.load()
    println("Resources:")
    jadx.resources.forEach { res ->
        println(" - ${res.originalName}")
        if (res.originalName.endsWith("AndroidManifest.xml")) {
            val content = res.loadContent()
            println("XML content:")
            println(content.text?.codeStr)
        }
    }
}
