// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ble

import com.dzid26.teslable.protocol.Metadata
import com.dzid26.teslable.protocol.TeslaCrypto
import com.dzid26.teslable.protocol.TeslaKeys
import com.dzid26.teslable.protocol.TeslaSession
import com.tesla.generated.carserver.common.Void
import com.tesla.generated.carserver.server.Action
import com.tesla.generated.carserver.server.GetVehicleData
import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.carserver.vehicle.ClimateState
import com.tesla.generated.carserver.vehicle.ClosuresState
import com.tesla.generated.carserver.vehicle.DriveState
import com.tesla.generated.carserver.vehicle.LocationState
import com.tesla.generated.carserver.vehicle.ShiftState
import com.tesla.generated.carserver.vehicle.TirePressureState
import com.tesla.generated.carserver.vehicle.VehicleData
import com.tesla.generated.signatures.AES_GCM_Response_Signature_Data
import com.tesla.generated.signatures.HMAC_Signature_Data
import com.tesla.generated.signatures.SessionInfo
import com.tesla.generated.signatures.SignatureData
import com.tesla.generated.signatures.SignatureType
import com.tesla.generated.signatures.Tag
import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.CommandStatus
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.InformationRequestType
import com.tesla.generated.vcsec.KeyIdentifier
import com.tesla.generated.vcsec.OperationStatus_E
import com.tesla.generated.vcsec.PublicKey
import com.tesla.generated.vcsec.ToVCSECMessage
import com.tesla.generated.vcsec.UnsignedMessage
import com.tesla.generated.vcsec.UserPresence_E
import com.tesla.generated.vcsec.VehicleLockState_E
import com.tesla.generated.vcsec.VehicleSleepStatus_E
import com.tesla.generated.vcsec.VehicleStatus
import com.tesla.generated.vcsec.WhitelistEntryInfo
import com.tesla.generated.vcsec.WhitelistInfo
import okio.ByteString.Companion.toByteString
import java.security.MessageDigest
import java.time.Instant
import com.tesla.generated.carserver.server.Response as CarServerResponse

/**
 * The simulated car's protocol brain. Pure JVM so it can be unit tested against
 * the real client code without Android; [FakeTeslaTransport] adds scheduling.
 *
 * It speaks enough of the protocol for the real controller: plaintext VCSEC
 * status and whitelist replies, the add-key pairing flow with a simulated card
 * tap, session handshakes with the core crypto, and encrypted wake/charge/drive/closures/climate
 * responses.
 */
class FakeCarProtocol(
    private val vin: String,
) {
    data class Response(
        val bytes: ByteArray,
        val delayMs: Long = 0,
        /** Runs when the transport delivers this response, after [delayMs]. */
        val onDelivered: (() -> Unit)? = null,
    )

    var asleep: Boolean = true
        private set

    var locked: Boolean = true
        private set

    var batteryLevel: Int = 78
        private set

    /** The demo scenario: plugged in and charging until the limit is reached. */
    private var charging = false
    private var chargeSessionStartLevel = batteryLevel

    private val sessions = mutableMapOf<Domain, ByteArray>()
    private val epochs = mutableMapOf<Domain, ByteArray>()
    private var responseCounter = 100
    private var clientPublicKey: ByteArray? = null
    private var clientKeySha1: ByteArray? = null

    val chargeLimit: Int get() = CHARGE_LIMIT

    fun setEnrolledKey(publicKeyRaw: ByteArray?) {
        clientPublicKey = publicKeyRaw
        clientKeySha1 = publicKeyRaw?.let(::sha1)
    }

    fun setCharging(value: Boolean) {
        charging = value
        if (value) chargeSessionStartLevel = batteryLevel
    }

    fun dischargeOnePercent() {
        if (!asleep && !charging && batteryLevel > 5) batteryLevel--
    }

    fun chargeOnePercent() {
        if (!asleep && charging && batteryLevel < CHARGE_LIMIT) batteryLevel++
    }

    fun handle(bytes: ByteArray): List<Response> {
        val out = mutableListOf<Response>()
        handleMessage(bytes, out)
        return out
    }

    private fun handleMessage(
        bytes: ByteArray,
        out: MutableList<Response>,
    ) {
        val message = runCatching { RoutableMessage.ADAPTER.decode(bytes) }.getOrNull()
        if (message != null) {
            if (message.session_info_request != null) {
                handleSessionRequest(message, out)
                return
            }
            if (message.signature_data?.AES_GCM_Personalized_data != null) {
                handleAuthenticated(message, out)
                return
            }
            val payload = message.protobuf_message_as_bytes?.toByteArray()
            if (payload != null &&
                runCatching { UnsignedMessage.ADAPTER.decode(payload) }.getOrNull() != null
            ) {
                handleVcsec(
                    payload = payload,
                    domain = message.to_destination?.domain ?: Domain.DOMAIN_VEHICLE_SECURITY,
                    uuid = message.uuid?.toByteArray(),
                    out = out,
                )
                return
            }
        }
        val toVcsec = runCatching { ToVCSECMessage.ADAPTER.decode(bytes) }.getOrNull()
        if (toVcsec?.signedMessage != null) {
            handlePairing(toVcsec, out)
        }
    }

    private fun handleVcsec(
        payload: ByteArray,
        domain: Domain,
        uuid: ByteArray?,
        out: MutableList<Response>,
    ) {
        val request = runCatching { UnsignedMessage.ADAPTER.decode(payload) }.getOrNull() ?: return
        val info = request.VCSEC_InformationRequest ?: return
        when (info.informationRequestType) {
            InformationRequestType.INFORMATION_REQUEST_TYPE_GET_STATUS -> {
                out +=
                    plaintext(
                        domain,
                        FromVCSECMessage(
                            vehicleStatus =
                                VehicleStatus(
                                    vehicleLockState =
                                        if (locked) {
                                            VehicleLockState_E.VEHICLELOCKSTATE_LOCKED
                                        } else {
                                            VehicleLockState_E.VEHICLELOCKSTATE_UNLOCKED
                                        },
                                    vehicleSleepStatus =
                                        if (asleep) {
                                            VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_ASLEEP
                                        } else {
                                            VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_AWAKE
                                        },
                                    userPresence = UserPresence_E.VEHICLE_USER_PRESENCE_NOT_PRESENT,
                                ),
                        ).encode(),
                        uuid,
                    )
            }

            InformationRequestType.INFORMATION_REQUEST_TYPE_GET_WHITELIST_INFO -> {
                val sha1 = clientKeySha1
                val entries =
                    if (sha1 != null) {
                        listOf(KeyIdentifier(publicKeySHA1 = sha1.toByteString()))
                    } else {
                        emptyList()
                    }
                out +=
                    plaintext(
                        domain,
                        FromVCSECMessage(
                            whitelistInfo =
                                WhitelistInfo(
                                    numberOfEntries = entries.size,
                                    whitelistEntries = entries,
                                    slotMask = if (entries.isEmpty()) 0 else 1,
                                ),
                        ).encode(),
                        uuid,
                    )
            }

            InformationRequestType.INFORMATION_REQUEST_TYPE_GET_WHITELIST_ENTRY_INFO -> {
                val sha1 = clientKeySha1
                val publicKey = clientPublicKey
                if (sha1 != null && publicKey != null) {
                    out +=
                        plaintext(
                            domain,
                            FromVCSECMessage(
                                whitelistEntryInfo =
                                    WhitelistEntryInfo(
                                        keyId = KeyIdentifier(publicKeySHA1 = sha1.toByteString()),
                                        publicKey = PublicKey(PublicKeyRaw = publicKey.toByteString()),
                                        slot = 0,
                                    ),
                            ).encode(),
                            uuid,
                        )
                }
            }

            else -> Unit
        }
    }

    private fun handlePairing(
        request: ToVCSECMessage,
        out: MutableList<Response>,
    ) {
        val payload = request.signedMessage?.protobufMessageAsBytes?.toByteArray() ?: return
        val unsigned = runCatching { UnsignedMessage.ADAPTER.decode(payload) }.getOrNull() ?: return
        val addKey = unsigned.VCSEC_WhitelistOperation?.addKeyToWhitelistAndAddPermissions ?: return
        val publicKey = addKey.key?.PublicKeyRaw?.toByteArray() ?: return
        // The car only enrolls the key once the card tap is confirmed; until
        // then the whitelist stays empty and the app keeps waiting.
        out += commandStatus(OperationStatus_E.OPERATIONSTATUS_WAIT)
        out +=
            commandStatus(
                OperationStatus_E.OPERATIONSTATUS_OK,
                CARD_TAP_MS,
                onDelivered = { setEnrolledKey(publicKey) },
            )
    }

    private fun handleSessionRequest(
        request: RoutableMessage,
        out: MutableList<Response>,
    ) {
        val domain = request.to_destination?.domain ?: return
        val clientPublic = request.session_info_request?.public_key?.toByteArray() ?: return
        val challenge = request.uuid?.toByteArray() ?: return
        // Opening a session does not enroll the key; only the add-key pairing
        // flow (or the transport pre-loading an already-paired app) does.

        val carKey = TeslaKeys.generate()
        val sessionKey = carKey.sessionKeyWith(clientPublic)
        sessions[domain] = sessionKey
        val epoch = epochs.getOrPut(domain) { TeslaCrypto.randomBytes(EPOCH_BYTES) }
        val info =
            SessionInfo(
                counter = SESSION_COUNTER,
                publicKey = carKey.publicKeyRaw.toByteString(),
                epoch = epoch.toByteString(),
                clock_time = (System.currentTimeMillis() / 1000).toInt(),
            ).encode()
        val tag = TeslaSession.sessionInfoHmac(sessionKey, vin, challenge, info)
        out +=
            Response(
                RoutableMessage(
                    from_destination = Destination(domain = domain),
                    request_uuid = challenge.toByteString(),
                    session_info = info.toByteString(),
                    signature_data =
                        SignatureData(
                            session_info_tag = HMAC_Signature_Data(tag = tag.toByteString()),
                        ),
                ).encode(),
            )
    }

    private fun handleAuthenticated(
        request: RoutableMessage,
        out: MutableList<Response>,
    ) {
        val domain = request.to_destination?.domain ?: return
        val key = sessions[domain] ?: return
        val gcm = request.signature_data?.AES_GCM_Personalized_data ?: return
        val ciphertext = request.protobuf_message_as_bytes?.toByteArray() ?: return
        val uuid = request.uuid?.toByteArray() ?: return

        val metadata =
            Metadata
                .sha256()
                .add(
                    Tag.TAG_SIGNATURE_TYPE.value,
                    byteArrayOf(SignatureType.SIGNATURE_TYPE_AES_GCM_PERSONALIZED.value.toByte()),
                ).add(Tag.TAG_DOMAIN.value, byteArrayOf(domain.value.toByte()))
                .add(Tag.TAG_PERSONALIZATION.value, vin.toByteArray(Charsets.US_ASCII))
                .add(Tag.TAG_EPOCH.value, gcm.epoch.toByteArray())
                .addUint32(Tag.TAG_EXPIRES_AT.value, gcm.expires_at)
                .addUint32(Tag.TAG_COUNTER.value, gcm.counter)
        if (request.flags > 0) {
            metadata.addUint32(Tag.TAG_FLAGS.value, request.flags)
        }
        val plaintext =
            TeslaCrypto.decryptGcm(
                key,
                gcm.nonce.toByteArray(),
                ciphertext,
                gcm.tag.toByteArray(),
                metadata.checksum(byteArrayOf()),
            ) ?: return
        val requestTag = gcm.tag.toByteArray()

        // Vehicle data first: Wire matches on field numbers, so an Action (field
        // 2 = vehicleAction) can otherwise be misread as an UnsignedMessage RKE
        // action.
        val action = runCatching { Action.ADAPTER.decode(plaintext) }.getOrNull()
        val vehicleData = action?.vehicleAction?.getVehicleData
        val reply = vehicleData?.let(::vehicleDataResponse)
        if (reply != null) {
            out += authenticated(domain, uuid, requestTag, reply)
            return
        }

        val unsigned = runCatching { UnsignedMessage.ADAPTER.decode(plaintext) }.getOrNull()
        if (unsigned?.RKEAction != null) {
            asleep = false
            out +=
                authenticated(
                    domain,
                    uuid,
                    requestTag,
                    FromVCSECMessage(
                        commandStatus =
                            CommandStatus(
                                operationStatus = OperationStatus_E.OPERATIONSTATUS_OK,
                            ),
                    ).encode(),
                )
        }
    }

    /** The simulated reply to a `GetVehicleData` request, or null for a category it does not simulate. */
    private fun vehicleDataResponse(request: GetVehicleData): ByteArray? =
        when {
            request.getChargeState != null -> chargeResponse()
            request.getDriveState != null -> driveResponse()
            request.getClosuresState != null -> closuresResponse()
            request.getClimateState != null -> climateResponse()
            request.getLocationState != null -> locationResponse()
            request.getTirePressureState != null -> tirePressureResponse()
            else -> null
        }

    private fun chargeResponse(): ByteArray =
        CarServerResponse(
            vehicleData =
                VehicleData(
                    charge_state =
                        ChargeState(
                            battery_level = batteryLevel,
                            usable_battery_level = batteryLevel,
                            battery_range = batteryLevel * RATED_MILES_PER_PERCENT,
                            charge_limit_soc = CHARGE_LIMIT,
                            charge_energy_added =
                                if (charging) {
                                    (batteryLevel - chargeSessionStartLevel) * KWH_PER_PERCENT
                                } else {
                                    0f
                                },
                            charge_miles_added_rated =
                                if (charging) {
                                    (batteryLevel - chargeSessionStartLevel) * RATED_MILES_PER_PERCENT
                                } else {
                                    0f
                                },
                            charging_state =
                                if (charging) {
                                    ChargeState.ChargingState(Charging = Void())
                                } else {
                                    ChargeState.ChargingState(Disconnected = Void())
                                },
                            // Like the real car, stamp every sample: history takes its time from here.
                            timestamp = Instant.ofEpochMilli(System.currentTimeMillis()),
                        ),
                ),
        ).encode()

    /** Parked in P with the odometer standing still, stamped by the car like the charge reply. */
    private fun driveResponse(): ByteArray =
        CarServerResponse(
            vehicleData =
                VehicleData(
                    drive_state =
                        DriveState(
                            shift_state = ShiftState(P = Void()),
                            speed = 0,
                            power = 0,
                            odometer_in_hundredths_of_a_mile = ODOMETER_HUNDREDTHS_OF_A_MILE,
                            // A navigation in progress, so the notification shows its trip stats.
                            active_route_destination = ROUTE_DESTINATION,
                            active_route_minutes_to_arrival = ROUTE_MINUTES_TO_ARRIVAL,
                            active_route_miles_to_arrival = ROUTE_MILES_TO_ARRIVAL,
                            active_route_traffic_minutes_delay = ROUTE_TRAFFIC_MINUTES,
                            active_route_energy_at_arrival = ROUTE_PERCENT_AT_ARRIVAL,
                            // Like the real car, stamp every sample: history takes its time from here.
                            timestamp = Instant.ofEpochMilli(System.currentTimeMillis()),
                        ),
                ),
        ).encode()

    /** Sentry mode off: the simulated car never enables it. */
    private fun closuresResponse(): ByteArray =
        CarServerResponse(
            vehicleData =
                VehicleData(
                    closures_state =
                        ClosuresState(
                            sentry_mode_state = ClosuresState.SentryModeState(Off = Void()),
                            locked = locked,
                            // Like the real car, stamp every sample.
                            timestamp = Instant.ofEpochMilli(System.currentTimeMillis()),
                        ),
                ),
        ).encode()

    /** Climate off: the simulated car never preconditions and has no keeper mode on. */
    private fun climateResponse(): ByteArray =
        CarServerResponse(
            vehicleData =
                VehicleData(
                    climate_state =
                        ClimateState(
                            is_climate_on = false,
                            climate_keeper_mode = ClimateState.ClimateKeeperMode(Off = Void()),
                            // Like the real car, stamp every sample.
                            timestamp = Instant.ofEpochMilli(System.currentTimeMillis()),
                        ),
                ),
        ).encode()

    /** The simulated car is parked, so it reports no position; the shape is enough to exercise the read. */
    private fun locationResponse(): ByteArray =
        CarServerResponse(
            vehicleData =
                VehicleData(location_state = LocationState(timestamp = Instant.ofEpochMilli(System.currentTimeMillis()))),
        ).encode()

    private fun tirePressureResponse(): ByteArray =
        CarServerResponse(
            vehicleData =
                VehicleData(tire_pressure_state = TirePressureState(timestamp = Instant.ofEpochMilli(System.currentTimeMillis()))),
        ).encode()

    private fun authenticated(
        domain: Domain,
        requestUuid: ByteArray,
        requestTag: ByteArray,
        plaintext: ByteArray,
    ): Response {
        val key = sessions[domain] ?: return Response(ByteArray(0))
        val counter = ++responseCounter
        val nonce = TeslaCrypto.randomBytes(TeslaCrypto.NONCE_SIZE)
        val requestId =
            byteArrayOf(
                SignatureType.SIGNATURE_TYPE_AES_GCM_PERSONALIZED.value.toByte(),
            ) + requestTag
        val metadata =
            Metadata
                .sha256()
                .add(
                    Tag.TAG_SIGNATURE_TYPE.value,
                    byteArrayOf(SignatureType.SIGNATURE_TYPE_AES_GCM_RESPONSE.value.toByte()),
                ).add(Tag.TAG_DOMAIN.value, byteArrayOf(domain.value.toByte()))
                .add(Tag.TAG_PERSONALIZATION.value, vin.toByteArray(Charsets.US_ASCII))
                .addUint32(Tag.TAG_COUNTER.value, counter)
                .addUint32(Tag.TAG_FLAGS.value, 0)
                .add(Tag.TAG_REQUEST_HASH.value, requestId)
                .addUint32(Tag.TAG_FAULT.value, 0)
        val (ciphertext, tag) =
            TeslaCrypto.encryptGcm(
                key,
                nonce,
                plaintext,
                metadata.checksum(byteArrayOf()),
            )
        return Response(
            RoutableMessage(
                from_destination = Destination(domain = domain),
                request_uuid = requestUuid.toByteString(),
                protobuf_message_as_bytes = ciphertext.toByteString(),
                signature_data =
                    SignatureData(
                        AES_GCM_Response_data =
                            AES_GCM_Response_Signature_Data(
                                nonce = nonce.toByteString(),
                                counter = counter,
                                tag = tag.toByteString(),
                            ),
                    ),
            ).encode(),
        )
    }

    private fun commandStatus(
        status: OperationStatus_E,
        delayMs: Long = 0,
        onDelivered: (() -> Unit)? = null,
    ): Response =
        Response(
            FromVCSECMessage(commandStatus = CommandStatus(operationStatus = status)).encode(),
            delayMs,
            onDelivered,
        )

    private fun plaintext(
        domain: Domain,
        payload: ByteArray,
        uuid: ByteArray?,
    ): Response {
        val message =
            RoutableMessage(
                from_destination = Destination(domain = domain),
                protobuf_message_as_bytes = payload.toByteString(),
                flags = 0,
            )
        return Response(
            if (uuid == null) {
                message.encode()
            } else {
                message.copy(request_uuid = uuid.toByteString()).encode()
            },
        )
    }

    private fun sha1(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(bytes)

    private companion object {
        const val CARD_TAP_MS = 4_000L
        const val SESSION_COUNTER = 500
        const val EPOCH_BYTES = 16
        const val CHARGE_LIMIT = 85
        const val RATED_MILES_PER_PERCENT = 3.0f
        const val KWH_PER_PERCENT = 0.75f

        /** 12,345.67 miles: constant, because the simulated car never drives. */
        const val ODOMETER_HUNDREDTHS_OF_A_MILE = 1_234_567
        const val ROUTE_DESTINATION = "Demo destination"
        const val ROUTE_MILES_TO_ARRIVAL = 9.5f
        const val ROUTE_MINUTES_TO_ARRIVAL = 18f
        const val ROUTE_PERCENT_AT_ARRIVAL = 63f
        const val ROUTE_TRAFFIC_MINUTES = 2f
    }
}
