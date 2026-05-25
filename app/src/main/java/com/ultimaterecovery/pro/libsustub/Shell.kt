
package com.ultimaterecovery.pro.libsustub

data class ShellResult(
    val stdout: List<String>,
    val stderr: List<String>,
    val exitCode: Int,
    val success: Boolean = exitCode == 0
)

object Shell {
    fun isRootGranted(): Boolean = false
    fun exec(command: String): ShellResult = ShellResult(emptyList(), emptyList(), 1)
    fun execAsync(command: String, callback: (ShellResult) -> Unit) {}
}
