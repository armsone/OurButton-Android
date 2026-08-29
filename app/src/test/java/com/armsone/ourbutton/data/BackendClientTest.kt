package com.armsone.ourbutton.data

import com.armsone.ourbutton.model.CallEvent
import com.armsone.ourbutton.model.FamilyRole
import com.armsone.ourbutton.model.FamilySpace
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.UUID

class BackendClientTest {
    @Test
    fun membershipRegistrationDoesNotRequirePushTokenInClientPayload() {
        var received = JSONObject()
        withServer { server ->
            server.createContext("/v1/devices") { exchange ->
                received = JSONObject(String(exchange.requestBody.readBytes()))
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }
            val client = HttpBackendClient(BackendConfiguration(baseURL(server)))

            runBlocking {
                client.registerDevice(
                    token = null,
                    space = FamilySpace(UUID.randomUUID(), "우리 가족", SECRET),
                    deviceId = UUID.randomUUID(),
                    name = "엄마",
                    role = FamilyRole.Parent,
                    environment = "production",
                )
            }
        }

        assertFalse(received.has("token"))
        assertFalse(received.has("notificationsMuted"))
        assertEquals("android", received.getString("platform"))
    }

    @Test
    fun zeroDeliveredIsNotReportedAsSendSuccess() {
        val error = withServer { server ->
            server.createContext("/v1/spaces/") { exchange ->
                val response = """{"delivered":0,"attempted":0}""".toByteArray()
                exchange.sendResponseHeaders(202, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            val client = HttpBackendClient(BackendConfiguration(baseURL(server)))
            assertThrows(BackendException.NoRecipients::class.java) {
                runBlocking {
                    client.send(
                        CallEvent(CallEvent.Kind.DingDong, UUID.randomUUID(), "엄마"),
                        SECRET,
                    )
                }
            }
        }
        assertEquals("현재 전달할 수 있는 가족 기기가 없어요. 상대 기기에서 앱을 열거나 원격 알림을 켜 주세요.", error.message)
    }

    @Test
    fun notificationMuteUsesExistingDeviceEndpointWithoutOverwritingMembership() {
        var received = JSONObject()
        var requestCount = 0
        val space = FamilySpace(UUID.randomUUID(), "우리 가족", SECRET)
        val deviceID = UUID.randomUUID()
        withServer { server ->
            server.createContext("/v1/devices") { exchange ->
                requestCount += 1
                received = JSONObject(String(exchange.requestBody.readBytes()))
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }
            runBlocking {
                val client = HttpBackendClient(BackendConfiguration(baseURL(server)))
                client.setNotificationsMuted(space, deviceID, true)
                client.setNotificationsMuted(space, deviceID, true)
            }
        }

        assertEquals(space.id.toString(), received.getString("spaceID"))
        assertEquals(SECRET, received.getString("secret"))
        assertEquals(deviceID.toString(), received.getString("deviceID"))
        assertEquals("android", received.getString("platform"))
        assertTrue(received.getBoolean("notificationsMuted"))
        assertFalse(received.has("token"))
        assertFalse(received.has("name"))
        assertFalse(received.has("role"))
        assertEquals(2, requestCount)
    }

    @Test
    fun memberMuteStatusAndLegacyDefaultAreDecodedExactly() {
        val firstID = UUID.randomUUID()
        val secondID = UUID.randomUUID()
        val members = withServer { server ->
            server.createContext("/v1/spaces/") { exchange ->
                val response = """{"members":[
                    {"deviceID":"$firstID","name":"엄마","role":"parent","notificationsMuted":true},
                    {"deviceID":"$secondID","name":"첫째","role":"child"}
                ]}""".toByteArray()
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            runBlocking {
                HttpBackendClient(BackendConfiguration(baseURL(server))).fetchMembers(
                    FamilySpace(UUID.randomUUID(), "우리 가족", SECRET),
                )
            }
        }

        assertTrue(members.first { it.deviceID == firstID }.notificationsMuted)
        assertFalse(members.first { it.deviceID == secondID }.notificationsMuted)
    }

    @Test
    fun allMutedQueuedResponseIsAcceptedAsQuietStorageNotDelivery() {
        val receipt = withServer { server ->
            server.createContext("/v1/spaces/") { exchange ->
                val response = """{"delivered":0,"attempted":0,"muted":2,"queued":true}""".toByteArray()
                exchange.sendResponseHeaders(202, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            runBlocking {
                HttpBackendClient(BackendConfiguration(baseURL(server))).send(
                    CallEvent(CallEvent.Kind.DingDong, UUID.randomUUID(), "엄마"),
                    SECRET,
                )
            }
        }
        assertEquals(0, receipt.delivered)
        assertEquals(0, receipt.attempted)
        assertEquals(2, receipt.muted)
        assertTrue(receipt.queued)
    }

    @Test
    fun tokenlessOfflineQueuedResponseIsAcceptedAsWaitingNotFailure() {
        val receipt = withServer { server ->
            server.createContext("/v1/spaces/") { exchange ->
                val response = """{"delivered":0,"attempted":0,"queued":true,"deliveryState":"queued","queuedRecipients":2,"unreachable":2}""".toByteArray()
                exchange.sendResponseHeaders(202, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            runBlocking {
                HttpBackendClient(BackendConfiguration(baseURL(server))).send(
                    CallEvent(CallEvent.Kind.QuietAlert, UUID.randomUUID(), "대표님"), SECRET,
                )
            }
        }
        assertTrue(receipt.queued)
        assertEquals(0, receipt.delivered)
    }

    @Test
    fun acknowledgedIdempotentRetryClearsOutboxWithoutClaimingNewPush() {
        val receipt = withServer { server ->
            server.createContext("/v1/spaces/") { exchange ->
                val response = """{"acknowledged":true}""".toByteArray()
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            runBlocking {
                HttpBackendClient(BackendConfiguration(baseURL(server))).send(
                    CallEvent(CallEvent.Kind.QuietAlert, UUID.randomUUID(), "대표님"), SECRET,
                )
            }
        }
        assertTrue(receipt.acknowledged)
        assertEquals(0, receipt.delivered)
    }

    @Test
    fun inboxUsesDeviceCursorAndIdempotentAckContract() {
        val space = FamilySpace(UUID.randomUUID(), "우리 가족", SECRET)
        val deviceID = UUID.randomUUID()
        val event = CallEvent(CallEvent.Kind.DingDong, space.id, "김부장").also {
            it.senderRole = FamilyRole.General
        }
        var inboxPath = ""
        var ackPath = ""
        var ackBody = JSONObject()
        val page = withServer { server ->
            server.createContext("/v1/spaces/") { exchange ->
                if (exchange.requestMethod == "GET") {
                    inboxPath = exchange.requestURI.toString()
                    val response = JSONObject()
                        .put("events", JSONArray().put(JSONObject(String(com.armsone.ourbutton.model.CallEventCoder.encode(event)))))
                        .put("cursor", "opaque:cursor")
                        .put("hasMore", false)
                        .toString().toByteArray()
                    exchange.sendResponseHeaders(200, response.size.toLong())
                    exchange.responseBody.use { it.write(response) }
                } else {
                    ackPath = exchange.requestURI.path
                    ackBody = JSONObject(String(exchange.requestBody.readBytes()))
                    val response = """{"acknowledged":true,"duplicate":false}""".toByteArray()
                    exchange.sendResponseHeaders(200, response.size.toLong())
                    exchange.responseBody.use { it.write(response) }
                }
            }
            runBlocking {
                val client = HttpBackendClient(BackendConfiguration(baseURL(server)))
                val fetched = client.fetchInbox(space, deviceID, "old cursor")
                client.acknowledgeInbox(space, deviceID, event.id)
                fetched
            }
        }
        assertTrue(inboxPath.contains("deviceID=$deviceID"))
        assertTrue(inboxPath.contains("cursor=old+cursor"))
        assertEquals(event.id, page.events.single().id)
        assertEquals(FamilyRole.General, page.events.single().senderRole)
        assertEquals("opaque:cursor", page.cursor)
        assertFalse(page.hasMore)
        assertEquals("/v1/spaces/${space.id}/events/${event.id}/ack", ackPath)
        assertEquals(deviceID.toString(), ackBody.getString("deviceID"))
    }

    @Test
    fun generalMemberRoleDecodesWithoutChangingWireMeaning() {
        val id = UUID.randomUUID()
        val members = withServer { server ->
            server.createContext("/v1/spaces/") { exchange ->
                val response = """{"members":[{"deviceID":"$id","name":"김부장","role":"general","notificationsMuted":false}]}""".toByteArray()
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            runBlocking {
                HttpBackendClient(BackendConfiguration(baseURL(server))).fetchMembers(space =
                    FamilySpace(UUID.randomUUID(), "우리 가족", SECRET))
            }
        }
        assertEquals(FamilyRole.General, members.single().role)
    }

    private fun <T> withServer(block: (HttpServer) -> T): T {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        return try { block(server) } finally { server.stop(0) }
    }

    private fun baseURL(server: HttpServer): String = "http://127.0.0.1:${server.address.port}/"

    private companion object {
        const val SECRET = "0123456789abcdef0123456789abcdef"
    }
}
