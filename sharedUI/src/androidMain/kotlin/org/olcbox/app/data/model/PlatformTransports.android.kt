package org.olcbox.app.data.model

actual fun isTransportSupportedOnCurrentPlatform(transport: String): Boolean {
    return transport == LocationConfig.TRANSPORT_DATACHANNEL ||
            transport == LocationConfig.TRANSPORT_VP8CHANNEL ||
            transport == LocationConfig.TRANSPORT_SEICHANNEL
}
