// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import com.dzid26.teslable.protocol.PairingKeys
import com.dzid26.teslable.protocol.SessionKeys
import com.dzid26.teslable.protocol.TeslaKeyPair

/** The pairing keys of one car, read from the phone's key store. */
internal class StoredPairingKeys(
    private val store: PairingKeyStore,
    private val bleName: String,
) : PairingKeys {
    override fun hasKey(): Boolean = store.hasKey(bleName)

    override fun publicKeyRaw(): ByteArray? = store.publicKeyRaw(bleName)

    override fun keyId(): ByteArray? = store.keyId(bleName)

    override fun generate(): TeslaKeyPair? = store.generate(bleName)
}

/** The session keys of one car, read from the phone's key store. */
internal class StoredSessionKeys(
    private val store: PairingKeyStore,
    private val bleName: String,
) : SessionKeys {
    override fun hasKey(): Boolean = store.hasKey(bleName)

    override fun publicKeyRaw(): ByteArray? = store.publicKeyRaw(bleName)

    override fun load(): TeslaKeyPair? = store.load(bleName)
}
