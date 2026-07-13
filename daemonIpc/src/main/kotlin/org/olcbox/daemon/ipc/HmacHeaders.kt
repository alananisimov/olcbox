package org.olcbox.daemon.ipc

object HmacHeaders {
    const val KEY_PATH = "X-Ipc-Key-Path"
    const val TIMESTAMP = "X-HMAC-Timestamp"
    const val SIGNATURE = "X-HMAC-Signature"
}
