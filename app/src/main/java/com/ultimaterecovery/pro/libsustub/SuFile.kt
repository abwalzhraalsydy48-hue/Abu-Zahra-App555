
package com.ultimaterecovery.pro.libsustub

import java.io.File

class SuFile(path: String) : File(path) {
    fun existsAsRoot(): Boolean = exists()
    fun canReadAsRoot(): Boolean = canRead()
    fun listAsRoot(): Array<String>? = list()
}
