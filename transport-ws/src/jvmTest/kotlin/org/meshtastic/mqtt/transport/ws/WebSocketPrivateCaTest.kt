/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.meshtastic.mqtt.transport.ws

import io.ktor.network.tls.TLSException
import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.meshtastic.mqtt.MqttEndpoint
import java.io.File
import java.security.KeyStore
import java.security.cert.CertPathBuilderException
import java.security.cert.CertPathValidatorException
import java.util.logging.Level
import java.util.logging.Logger
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * End-to-end proof of issue #107's acceptance criterion: a `wss://` broker whose certificate is
 * anchored in a private CA is reachable through the [WebSocketTransportFactory] hook alone, with
 * no app-wide or JVM-wide trust changes.
 *
 * The server speaks WebSocket, not MQTT — what is under test is the TLS trust decision and the
 * transport's binary framing, not the protocol layered on top.
 */
class WebSocketPrivateCaTest {
    private val alias = "mqtt-ws-test"
    private val password = "changeit"
    private var keyStoreFile: File? = null
    private lateinit var keyStore: KeyStore
    private var port = 0
    private var server: EmbeddedServer<*, *>? = null
    private val nettyLogger: Logger = Logger.getLogger("io.netty")
    private var previousNettyLevel: Level? = null

    @BeforeTest
    fun startServer() {
        // Two of the three tests deliberately fail the handshake; Netty logs each one at WARNING.
        // Silence it so a passing run has clean output. The Logger must be held in a field —
        // java.util.logging discards the configuration of unreferenced Logger instances. Restored
        // in stopServer() since this is a JVM-wide mutation shared with every other test in the module.
        previousNettyLevel = nettyLogger.level
        nettyLogger.level = Level.OFF

        keyStore =
            buildKeyStore {
                certificate(alias) {
                    this.password = this@WebSocketPrivateCaTest.password
                    domains = listOf("localhost")
                }
            }
        val file = File.createTempFile("mqtt-ws-test", ".jks")
        file.deleteOnExit()
        keyStoreFile = file
        file.outputStream().use { keyStore.store(it, password.toCharArray()) }

        val embedded =
            embeddedServer(Netty, environment = applicationEnvironment { }, configure = {
                sslConnector(
                    keyStore = keyStore,
                    keyAlias = alias,
                    keyStorePassword = { password.toCharArray() },
                    privateKeyPassword = { password.toCharArray() },
                ) {
                    // Port 0: let the OS pick, then read it back from the bound connector, so two
                    // concurrent runs of this test can never collide on a port.
                    this.port = 0
                    this.keyStorePath = keyStoreFile
                }
            }) {
                install(WebSockets)
                routing {
                    webSocket("/mqtt") {
                        for (frame in incoming) {
                            if (frame is Frame.Binary) send(Frame.Binary(true, frame.data))
                        }
                    }
                }
            }
        embedded.start(wait = false)
        server = embedded
        // resolvedConnectors() only returns once the connector is actually bound, so the tests
        // cannot race the server's startup.
        port =
            runBlocking {
                embedded.engine
                    .resolvedConnectors()
                    .first()
                    .port
            }
    }

    @AfterTest
    fun stopServer() {
        // Each cleanup step must run even if an earlier one throws — mirrors the nested
        // try/finally pattern in TcpTransport.close().
        try {
            server?.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        } finally {
            try {
                keyStoreFile?.delete()
            } finally {
                nettyLogger.level = previousNettyLevel
            }
        }
    }

    private val endpoint get() = MqttEndpoint.WebSocket("wss://localhost:$port/mqtt")

    /** A trust store holding only the generated certificate — the "private CA". */
    private fun privateCaTrustManager(): X509TrustManager {
        val trustStore =
            KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("private-ca", keyStore.getCertificate(alias))
            }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(trustStore)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    @Test
    fun handshakeFailsWithoutTheHook() =
        runBlocking {
            val transport = WebSocketTransportFactory().create(endpoint)
            try {
                val failure =
                    assertFailsWith<Exception> {
                        withTimeout(20_000) { transport.connect(endpoint) }
                    }
                // Assert the *reason*: certificate trust, not a bad URL or an unstarted server.
                // The JVM default trust manager reports SunCertPathBuilderException / CertPathValidatorException.
                val chain = generateSequence<Throwable>(failure) { it.cause }.toList()
                assertTrue(
                    chain.any { it is CertPathBuilderException || it is CertPathValidatorException },
                    "expected a certificate-path failure, got: ${chain.map { it::class.qualifiedName }}",
                )
            } finally {
                transport.close()
            }
        }

    @Test
    fun handshakeSucceedsAndFramesRoundTripWithTheHook() =
        runBlocking {
            val tm = privateCaTrustManager()
            val transport = WebSocketTransportFactory { trustManager = tm }.create(endpoint)
            try {
                withTimeout(20_000) {
                    transport.connect(endpoint)
                    assertTrue(transport.isConnected)
                    val payload = byteArrayOf(0x10, 0x02, 0x00, 0x04)
                    transport.send(payload)
                    assertContentEquals(payload, transport.receive())
                }
            } finally {
                transport.close()
            }
        }

    /**
     * Settles the question the design left open: a `serverName` assigned inside the hook is *not*
     * overwritten by CIO's per-request SNI — it wins, and ktor then verifies the certificate's
     * subject names against it, so a value that does not match the broker breaks the handshake.
     */
    @Test
    fun serverNameSetInsideTheHookWinsOverCioRequestSni() =
        runBlocking {
            val tm = privateCaTrustManager()
            val transport =
                WebSocketTransportFactory {
                    trustManager = tm
                    serverName = "not-the-server.example.com"
                }.create(endpoint)
            try {
                val failure =
                    assertFailsWith<TLSException> {
                        withTimeout(20_000) { transport.connect(endpoint) }
                    }
                // The type check alone cannot distinguish "serverName reached certificate
                // verification" from "serverName was silently discarded and some unrelated TLS
                // failure occurred" — both would surface as TLSException here. The substring
                // check on the message is what proves the hook's serverName specifically drove
                // subject-name verification, so it stays even though message text is
                // comparatively brittle: a ktor wording change degrades that one assertion
                // rather than breaking the test outright, since the type check below still holds.
                assertIs<TLSException>(
                    failure.cause,
                    "expected the TLSException to be caused by a nested certificate-verification " +
                        "TLSException, got: ${failure.cause}",
                )
                assertTrue(
                    failure.message.orEmpty().contains("not-the-server.example.com"),
                    "expected subject-name verification against the hook's serverName, got: ${failure.message}",
                )
            } finally {
                transport.close()
            }
        }
}
