package com.gatecontrol.android.media.offline

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.gatecontrol.android.drive.vault.DriveVaultCrypto
import com.gatecontrol.android.drive.vault.DriveVaultVideoSource

/**
 * The player's way to its bytes with downloads: a `gcoffline://<id>/<part>` URI ([MediaOfflineLogic.uri]) is read
 * from the encrypted file on the phone, chunk by chunk ([DriveVaultVideoSource], nothing decrypted written anywhere);
 * anything else from the network ([network]: Jellyfin or a share).
 */
@OptIn(UnstableApi::class)
class MediaOfflineDataSource(
    private val open: (id: String, part: String) -> Pair<java.io.File, DriveVaultCrypto.Content>?,
    private val network: DataSource,
) : DataSource {
    private var current: DataSource? = null
    private var content: DriveVaultCrypto.Content? = null
    private val listeners = mutableListOf<TransferListener>()

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        network.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val local = MediaOfflineLogic.parse(dataSpec.uri.toString())
        if (local == null) {
            current = network
            return network.open(dataSpec)
        }
        val (file, c) = open(local.first, local.second) ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        content = c
        val source = DriveVaultVideoSource(file, c).also { s -> listeners.forEach(s::addTransferListener) }
        current = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current?.read(buffer, offset, length) ?: throw java.io.IOException("not open")

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders.orEmpty()

    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
            content?.close()
            content = null
        }
    }
}
