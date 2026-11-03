// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ble

import com.dzid26.teslable.protocol.AntiReplayWindow
import com.dzid26.teslable.protocol.ChargingStateKind
import com.dzid26.teslable.protocol.ShiftStateKind
import com.dzid26.teslable.protocol.TeslaCommands
import com.dzid26.teslable.protocol.TeslaCrypto
import com.dzid26.teslable.protocol.TeslaKeys
import com.dzid26.teslable.protocol.TeslaPairing
import com.dzid26.teslable.protocol.TeslaSession
import com.dzid26.teslable.protocol.TeslaSessionRequests
import com.dzid26.teslable.protocol.TeslaVcsec
import com.dzid26.teslable.protocol.asleep
import com.dzid26.teslable.protocol.chargingStateKind
import com.dzid26.teslable.protocol.climateOn
import com.dzid26.teslable.protocol.locked
import com.dzid26.teslable.protocol.sentryOn
import com.dzid26.teslable.protocol.shiftStateKind
import com.dzid26.teslable.protocol.userPresent
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.OperationStatus_E
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trips the simulated car against the real client code: every request is
 * built with the core protocol helpers and every reply is parsed with the
 * production parsers.
 */
class FakeCarProtocolTest {
    private val vin = "5YJ3DEMO0P0000001"
    private val client = TeslaKeys.generate()
    private val protocol = FakeCarProtocol(vin)

    @Test
    fun `status starts locked and asleep`() {
        val response = protocol.handle(TeslaVcsec.buildStatusRequest()).single()
        val status = TeslaVcsec.parseStatusResponse(response.bytes)

        assertNotNull(status)
        assertTrue(status!!.locked)
        assertTrue(status.asleep)
        assertFalse(status.userPresent)
    }

    @Test
    fun `whitelist reports the enrolled key`() {
        protocol.setEnrolledKey(client.publicKeyRaw)

        val info =
            TeslaVcsec.parseWhitelistInfoResponse(
                protocol.handle(TeslaVcsec.buildWhitelistInfoRequest()).single().bytes,
            )
        assertNotNull(info)
        assertEquals(1, info!!.numberOfEntries)
        assertTrue(
            info.whitelistEntries
                .first()
                .publicKeySHA1
                .toByteArray()
                .copyOf(TeslaKeys.keyId(client.publicKeyRaw).size)
                .contentEquals(TeslaKeys.keyId(client.publicKeyRaw)),
        )

        val entry =
            TeslaVcsec.parseWhitelistEntryResponse(
                protocol.handle(TeslaVcsec.buildWhitelistEntryRequest(0)).single().bytes,
            )
        assertNotNull(entry)
        assertEquals(0, entry!!.slot)
        assertArrayEquals(client.publicKeyRaw, entry.publicKey?.PublicKeyRaw?.toByteArray())
    }

    @Test
    fun `empty whitelist when no key is enrolled`() {
        val info =
            TeslaVcsec.parseWhitelistInfoResponse(
                protocol.handle(TeslaVcsec.buildWhitelistInfoRequest()).single().bytes,
            )
        assertEquals(0, info!!.numberOfEntries)
    }

    @Test
    fun `pairing waits for the card then succeeds`() {
        val responses = protocol.handle(TeslaPairing.buildAddKeyRequest(client.publicKeyRaw))

        assertEquals(2, responses.size)
        assertEquals(
            TeslaPairing.Result.WAITING_FOR_CARD,
            TeslaPairing.parseAddKeyResponse(responses[0].bytes),
        )
        assertTrue(responses[1].delayMs > 0)
        assertEquals(TeslaPairing.Result.OK, TeslaPairing.parseAddKeyResponse(responses[1].bytes))
    }

    @Test
    fun `wake decrypts, replies ok, and clears asleep`() {
        val session = session(Domain.DOMAIN_VEHICLE_SECURITY)
        val plaintext =
            authenticated(
                session,
                Domain.DOMAIN_VEHICLE_SECURITY,
                TeslaCommands.buildWakeRequest(),
            )

        assertEquals(
            OperationStatus_E.OPERATIONSTATUS_OK,
            TeslaVcsec.parseCommandStatus(plaintext),
        )
        assertFalse(protocol.asleep)
    }

    @Test
    fun `charge decrypts to a charge state`() {
        val session = session(Domain.DOMAIN_INFOTAINMENT)
        val plaintext =
            authenticated(
                session,
                Domain.DOMAIN_INFOTAINMENT,
                TeslaCommands.buildChargeStateRequest(),
            )

        val charge = TeslaCommands.parseChargeState(plaintext)
        assertNotNull(charge)
        assertEquals(78, charge!!.battery_level)
        assertEquals(78, charge.usable_battery_level)
        assertEquals(85, charge.charge_limit_soc)
        assertEquals(ChargingStateKind.Disconnected, charge.chargingStateKind)
        assertEquals(234f, charge.battery_range)
        assertNull(charge.est_battery_range)
        // History takes time only from the car's own stamp.
        assertNotNull(charge.timestamp)
    }

    @Test
    fun `charging reports the charging state`() {
        protocol.setCharging(true)

        val session = session(Domain.DOMAIN_INFOTAINMENT)
        val plaintext =
            authenticated(
                session,
                Domain.DOMAIN_INFOTAINMENT,
                TeslaCommands.buildChargeStateRequest(),
            )

        assertEquals(ChargingStateKind.Charging, TeslaCommands.parseChargeState(plaintext)!!.chargingStateKind)
    }

    @Test
    fun `drive decrypts to a parked drive state stamped by the car`() {
        val session = session(Domain.DOMAIN_INFOTAINMENT)
        val plaintext =
            authenticated(
                session,
                Domain.DOMAIN_INFOTAINMENT,
                TeslaCommands.buildDriveStateRequest(),
            )

        val drive = TeslaCommands.parseDriveState(plaintext)
        assertNotNull(drive)
        assertEquals(ShiftStateKind.P, drive!!.shiftStateKind)
        assertEquals(0, drive.speed)
        assertEquals(0, drive.power)
        assertNotNull(drive.odometer_in_hundredths_of_a_mile)
        // History takes time only from the car's own stamp.
        assertNotNull(drive.timestamp)
    }

    @Test
    fun `the odometer stays put between drive replies`() {
        val session = session(Domain.DOMAIN_INFOTAINMENT)
        val request = TeslaCommands.buildDriveStateRequest()
        val first = TeslaCommands.parseDriveState(authenticated(session, Domain.DOMAIN_INFOTAINMENT, request))
        val second = TeslaCommands.parseDriveState(authenticated(session, Domain.DOMAIN_INFOTAINMENT, request))

        assertNotNull(first!!.odometer_in_hundredths_of_a_mile)
        assertEquals(first.odometer_in_hundredths_of_a_mile, second!!.odometer_in_hundredths_of_a_mile)
    }

    @Test
    fun `a drive request is not mistaken for a wake command`() {
        // An Action (field 2 = vehicleAction) can be misread as an RKE action, which would wake the car.
        val session = session(Domain.DOMAIN_INFOTAINMENT)
        authenticated(session, Domain.DOMAIN_INFOTAINMENT, TeslaCommands.buildDriveStateRequest())

        assertTrue(protocol.asleep)
    }

    @Test
    fun `closures decrypts to a closures state with sentry off`() {
        val session = session(Domain.DOMAIN_INFOTAINMENT)
        val plaintext = authenticated(session, Domain.DOMAIN_INFOTAINMENT, TeslaCommands.buildClosuresStateRequest())

        val closures = TeslaCommands.parseClosuresState(plaintext)
        assertNotNull(closures)
        assertNotNull(closures!!.sentry_mode_state)
        assertFalse(closures.sentryOn)
        // The other categories' parsers find nothing in it.
        assertNull(TeslaCommands.parseClimateState(plaintext))
        assertNull(TeslaCommands.parseDriveState(plaintext))
    }

    @Test
    fun `climate decrypts to a climate state with the climate off`() {
        val session = session(Domain.DOMAIN_INFOTAINMENT)
        val plaintext = authenticated(session, Domain.DOMAIN_INFOTAINMENT, TeslaCommands.buildClimateStateRequest())

        val climate = TeslaCommands.parseClimateState(plaintext)
        assertNotNull(climate)
        assertEquals(false, climate!!.is_climate_on)
        assertFalse(climate.climateOn)
        assertNotNull(climate.timestamp)
        assertNull(TeslaCommands.parseClosuresState(plaintext))
    }

    @Test
    fun `closures and climate requests are not mistaken for a wake command`() {
        val session = session(Domain.DOMAIN_INFOTAINMENT)
        authenticated(session, Domain.DOMAIN_INFOTAINMENT, TeslaCommands.buildClosuresStateRequest())
        authenticated(session, Domain.DOMAIN_INFOTAINMENT, TeslaCommands.buildClimateStateRequest())

        assertTrue(protocol.asleep)
    }

    @Test
    fun `charging stops at the limit`() {
        // The car only charges while awake.
        val session = session(Domain.DOMAIN_VEHICLE_SECURITY)
        authenticated(session, Domain.DOMAIN_VEHICLE_SECURITY, TeslaCommands.buildWakeRequest())
        protocol.setCharging(true)

        repeat(20) { protocol.chargeOnePercent() }

        assertEquals(85, protocol.batteryLevel)
    }

    @Test
    fun `discharge only happens while awake`() {
        protocol.dischargeOnePercent()
        assertEquals(78, protocol.batteryLevel)

        val session = session(Domain.DOMAIN_VEHICLE_SECURITY)
        authenticated(session, Domain.DOMAIN_VEHICLE_SECURITY, TeslaCommands.buildWakeRequest())
        protocol.dischargeOnePercent()
        assertEquals(77, protocol.batteryLevel)
    }

    private fun session(domain: Domain): TeslaSession {
        val uuid = TeslaCrypto.randomBytes(16)
        val request =
            TeslaSessionRequests.buildSessionInfoRequest(
                domain = domain,
                publicKeyRaw = client.publicKeyRaw,
                routingAddress = TeslaCrypto.randomBytes(16),
                uuid = uuid,
            )
        val response =
            RoutableMessage.ADAPTER.decode(
                protocol.handle(request).single().bytes,
            )
        val session =
            TeslaSession.import(
                key = client,
                vin = vin,
                challenge = uuid,
                encodedInfo = response.session_info!!.toByteArray(),
                tag =
                    response.signature_data!!
                        .session_info_tag!!
                        .tag!!
                        .toByteArray(),
            )
        return checkNotNull(session)
    }

    private fun authenticated(
        session: TeslaSession,
        domain: Domain,
        payload: ByteArray,
    ): ByteArray {
        val uuid = TeslaCrypto.randomBytes(16)
        val message =
            TeslaSessionRequests.buildAuthenticatedRequest(
                domain = domain,
                payload = payload,
                routingAddress = TeslaCrypto.randomBytes(16),
                uuid = uuid,
            )
        val encrypted = session.encrypt(message, 5)!!
        val requestId = session.requestId(encrypted)!!
        val response =
            RoutableMessage.ADAPTER.decode(
                protocol.handle(encrypted.encode()).single().bytes,
            )
        return session.decrypt(response, requestId, AntiReplayWindow())!!
    }
}
