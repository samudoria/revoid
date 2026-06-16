package dev.jadxmcp
import java.io.File
fun main() {
    val engine = JadxEngine()
    engine.loadApk("test-app/build/sample.jar")
    
    val jadxField = JadxEngine::class.java.getDeclaredField("decompiler")
    jadxField.isAccessible = true
    val jadx = jadxField.get(engine) as jadx.api.JadxDecompiler
    
    val cls = jadx.classes.find { it.fullName == "com.example.testapp.AuthManager" }!!
    val mth = cls.methods.find { it.name == "login" }!!
    val mn = mth.methodNode
    mn.unload()
    mn.load()
    println("After JadxEngine unload+load: isLoaded=${mn.isLoaded}, instructions.size=${mn.instructions?.size}")
}
