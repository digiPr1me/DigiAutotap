package io.github.digipr1me.digiautotap.core

/**
 * templates/ and digits/, by their path in the repository
 * ("templates/arrow.png"). The readers get bytes, never a file path:
 * in the APK they are assets, which are not the file system, and nothing is
 * copied out on the first start (PLAN_ANDROID_5_SHELL.md 4). The tests fill
 * this from the class path, the app from its AssetManager. `recognise` needs
 * none of it; P6 finds the seam here.
 */
fun interface AssetSource {
    /** The file's bytes, or null where the build carries no such file. */
    fun bytes(path: String): ByteArray?
}

/** The class path, which is where core's tests find the repository's folders. */
object ClassPathAssets : AssetSource {
    override fun bytes(path: String): ByteArray? =
        ClassPathAssets::class.java.classLoader.getResourceAsStream(path)?.use { it.readBytes() }
}
