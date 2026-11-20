/*
 * SPDX-FileCopyrightText: 2026 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.backends.lan

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.PackageManager
import android.net.Network
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import org.kde.kdeconnect.DeviceInfo
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.backends.BaseLink
import org.kde.kdeconnect.backends.BaseLinkProvider
import org.kde.kdeconnect.extensions.closeSafe
import org.kde.kdeconnect.helpers.DeviceHelper
import org.kde.kdeconnect.helpers.ThreadHelper
import org.kde.kdeconnect.helpers.TrustedDevices
import org.kde.kdeconnect.helpers.TrustedNetworkHelper
import org.kde.kdeconnect.helpers.isPrivateAddress
import org.kde.kdeconnect.helpers.readLineBounded
import org.kde.kdeconnect.helpers.security.SslHelper
import org.kde.kdeconnect.ui.CustomDevicesActivity
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.cert.CertificateException
import java.util.concurrent.ConcurrentHashMap
import javax.net.SocketFactory
import javax.net.ssl.HandshakeCompletedEvent
import javax.net.ssl.SSLSocket

/**
 * This LanLinkProvider creates [LanLink]s to other devices on the same network.
 */
class LanLinkProvider(private val context: Context) : BaseLinkProvider() {

    private val visibleDevices = ConcurrentHashMap<String, LanLink>() // Links by device id

    private val mdnsDiscovery = MdnsDiscovery(context, this)

    private val ipRateLimiter = RateLimiter<InetAddress>(MILLIS_DELAY_BETWEEN_CONNECTIONS_TO_SAME_DEVICE, MAX_RATE_LIMIT_ENTRIES)
    // Keyed by address too: a device announcing from several addresses must not have its working one suppressed
    private val deviceRateLimiter = RateLimiter<Pair<String, InetAddress>>(MILLIS_DELAY_BETWEEN_CONNECTIONS_TO_SAME_DEVICE, MAX_RATE_LIMIT_ENTRIES)

    private val customDevicePreferences: SharedPreferences =
        context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)

    @Volatile
    private var customDeviceAddresses = emptyList<InetAddress>()

    private val customDeviceListener = OnSharedPreferenceChangeListener { _, key ->
        if (key == CustomDevicesActivity.KEY_CUSTOM_DEVICE_LIST_PREFERENCE) {
            updateCustomDevicesList()
        }
    }

    fun updateCustomDevicesList() {
        customDeviceAddresses = CustomDevicesActivity.getCustomDeviceList(context).flatMap {
            runCatching {
                InetAddress.getAllByName(it.host).toList()
            }.getOrDefault(emptyList())
        }
    }

    @Volatile
    private var tcpServer: ServerSocket? = null
    @Volatile
    private var udpServer: DatagramSocket? = null

    private val networkChangeScope = CoroutineScope(Dispatchers.IO)
    private var networkChangeJob: Job? = null
    private var directConnectRetryJob: Job? = null
    private var directConnectJob: Job? = null
    private val directConnectsInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile
    private var isStopped = true

    fun hasDevice(id: String): Boolean {
        return visibleDevices.containsKey(id)
    }

    override fun onConnectionLost(link: BaseLink) {
        val deviceId = link.deviceId
        // Do not let an old connection remove its replacement.
        if (visibleDevices.remove(deviceId, link)) {
            super.onConnectionLost(link)
        }
    }

    fun unserializeReceivedIdentityPacket(message: String, address: InetAddress): Pair<NetworkPacket, Boolean>? {
        val identityPacket: NetworkPacket?
        try {
            identityPacket = NetworkPacket.unserialize(message)
        } catch (e: SerializationException) {
            Log.w("KDE/LanLinkProvider", "Invalid identity packet received: ${e.message}")
            return null
        }

        if (!DeviceInfo.isValidIdentityPacket(identityPacket)) {
            Log.w("KDE/LanLinkProvider", "Invalid identity packet received.")
            return null
        }

        val deviceId = identityPacket.getString("deviceId")
        val myId = DeviceHelper.getDeviceId(context)
        if (deviceId == myId) {
            //Ignore my own broadcast
            return null
        }

        if (deviceRateLimiter.shouldDiscard(deviceId to address)) {
            Log.i("LanLinkProvider", "Discarding second packet from the same device $deviceId at $address received too quickly")
            return null
        }

        val deviceTrusted = TrustedDevices.isTrustedDevice(context, deviceId)
        if (!deviceTrusted && !TrustedNetworkHelper.isTrustedNetwork(context)) {
            Log.i("KDE/LanLinkProvider", "Ignoring identity packet because the device is not trusted and I'm not on a trusted network.")
            return null
        }

        return Pair(identityPacket, deviceTrusted)
    }

    // They received my UDP broadcast and are connecting to me. The first thing they send should be their identity packet.
    @WorkerThread
    @Throws(IOException::class, CertificateException::class)
    private fun tcpPacketReceived(socket: Socket) {
        val address = socket.getInetAddress()

        if (ipRateLimiter.shouldDiscard(address)) {
            Log.i("LanLinkProvider", "Discarding second TCP packet from the same ip $address received too quickly" )
            socket.closeSafe()
            return
        }

        if (!isAllowedAddress(address)) {
            Log.i("LanLinkProvider", "Discarding TCP packet from a non-local IP: $address")
            socket.closeSafe()
            return
        }

        val message = try {
            // We don't use a BufferedInputStream on purpose, since BufferedReader reads ahead and would require
            // us to keep a single BufferedInputStream instance and pass it around to make sure we don't lose data.
            // This means we are readying byte by byte directly from the OS, which is slow, but only for the handshake.
            readLineBounded(socket.getInputStream(), MAX_IDENTITY_PACKET_SIZE)
            //Log.e("TcpListener", "Received TCP packet: $message");
        } catch (e: Exception) {
            Log.e("KDE/LanLinkProvider", "Exception while receiving TCP packet", e)
            socket.closeSafe()
            return
        }

        val (identityPacket, deviceTrusted)  = unserializeReceivedIdentityPacket(message, address) ?: run {
            socket.closeSafe()
            return
        }

        Log.i("KDE/LanLinkProvider", "identity packet received from a TCP connection from ${identityPacket.getString("deviceName")}")

        val targetDeviceId = identityPacket.getStringOrNull("targetDeviceId")
        val targetProtocolVersion = identityPacket.getIntOrNull("targetProtocolVersion")
        if (targetDeviceId != null && targetDeviceId != DeviceHelper.getDeviceId(context)) {
            Log.e("KDE/LanLinkProvider", "Received a connection request for a device that isn't me: $targetDeviceId")
            socket.closeSafe()
            return
        }
        if (targetProtocolVersion != null && targetProtocolVersion != DeviceHelper.PROTOCOL_VERSION) {
            Log.e("KDE/LanLinkProvider", "Received a connection request for a protocol version that isn't mine: $targetProtocolVersion")
            socket.closeSafe()
            return
        }

        identityPacketReceived(identityPacket, socket, ConnectionStarted.Locally, deviceTrusted)
    }

    private fun isAllowedAddress(address: InetAddress): Boolean {
        return isPrivateAddress(address) || customDeviceAddresses.contains(address)
    }

    //I've received their broadcast and should connect to their TCP socket and send my identity.
    @WorkerThread
    private fun udpPacketReceived(packet: DatagramPacket) {
        val address = packet.address

        if (ipRateLimiter.shouldDiscard(address)) {
            Log.i("LanLinkProvider", "Discarding second UDP packet from the same ip $address received too quickly")
            return
        }

        if (!isAllowedAddress(address)) {
            Log.i("LanLinkProvider", "Discarding UDP packet from a non-local IP: $address")
            return
        }

        val message = String(packet.data, 0, packet.length, Charsets.UTF_8)

        val (identityPacket, deviceTrusted) = unserializeReceivedIdentityPacket(message, address)
            ?: return

        Log.i("KDE/LanLinkProvider", "Broadcast identity packet received from ${identityPacket.getString("deviceName")}")

        val tcpPort = identityPacket.getInt("tcpPort", MIN_PORT)
        if (tcpPort !in MIN_PORT..MAX_PORT) {
            Log.e("LanLinkProvider", "TCP port outside of kdeconnect's range")
            return
        }

        connectAndIdentify(address, tcpPort, identityPacket, deviceTrusted)
    }

    /** Connects to a device's TCP port, sends my identity and starts the TLS handshake. */
    @WorkerThread
    private fun connectAndIdentify(address: InetAddress, tcpPort: Int, identityPacket: NetworkPacket, deviceTrusted: Boolean) {
        var socket: Socket? = null
        try {
            socket = openSocket(InetSocketAddress(address, tcpPort))
            configureSocket(socket)

            val myDeviceInfo = DeviceHelper.getDeviceInfo(context)
            val myIdentity = myDeviceInfo.toIdentityPacket()
            myIdentity["targetDeviceId"] = identityPacket.getString("deviceId")
            myIdentity["targetProtocolVersion"] = identityPacket.getString("protocolVersion")

            val out = socket.getOutputStream()
            out.write(myIdentity.serialize().toByteArray())
            out.flush()

            identityPacketReceived(identityPacket, socket, ConnectionStarted.Remotely, deviceTrusted)
            socket = null // The SSL socket now owns the underlying socket, or it was rejected and closed.
        } catch (e: IOException) {
            Log.e("LanLinkProvider", "Exception connecting to $address:$tcpPort", e)
        } catch (e: CertificateException) {
            Log.e("LanLinkProvider", "Exception connecting to $address:$tcpPort", e)
        } catch (e: SerializationException) {
            Log.e("LanLinkProvider", "Exception connecting to $address:$tcpPort", e)
        } finally {
            socket?.closeSafe()
        }
    }

    private fun configureSocket(socket: Socket) {
        try {
            TcpKeepAlive.apply(socket)
            socket.setSoTimeout(10 * 1000)
        } catch (e: SocketException) {
            Log.e("LanLink", "Exception", e)
        }
    }

    /**
     * Called when a new 'identity' packet is received. Those are passed here by
     * [.tcpPacketReceived] and [.udpPacketReceived].
     * Should be called on a new thread since it blocks until the handshake is completed.
     *
     * @param identityPacket    identity of a remote device
     * @param socket            a new Socket, which should be used to receive packets from the remote device
     * @param connectionStarted which side started this connection
     * @param deviceTrusted     whether the packet comes from a trusted device
     */
    @WorkerThread
    @Throws(IOException::class, CertificateException::class)
    private fun identityPacketReceived(
        identityPacket: NetworkPacket,
        socket: Socket,
        connectionStarted: ConnectionStarted,
        deviceTrusted: Boolean
    ) {
        val deviceId = identityPacket.getString("deviceId")
        val protocolVersion = identityPacket.getInt("protocolVersion")

        if (deviceTrusted && isProtocolDowngrade(deviceId, protocolVersion)) {
            Log.w("KDE/LanLinkProvider", "Refusing to connect to a device using an older protocol version:$protocolVersion")
            socket.closeSafe()
            return
        }

        if (deviceTrusted && !TrustedDevices.isCertificateStored(context, deviceId)) {
            Log.e("KDE/LanLinkProvider", "Device trusted but no cert stored. This should not happen.")
            socket.closeSafe()
            return
        }

        if (!deviceTrusted && visibleDevices.size >= MAX_UNPAIRED_CONNECTIONS) {
            Log.w("KDE/LanLinkProvider", "Too many devices on the network. Ignoring $deviceId")
            socket.closeSafe()
            return
        }

        Log.i("KDE/LanLinkProvider", "Starting SSL handshake with $deviceId trusted:$deviceTrusted")

        // If I'm the TCP server I will be the SSL client and vice-versa.
        val clientMode = (connectionStarted == ConnectionStarted.Locally)
        val sslSocket = SslHelper.convertToSslSocket(context, socket, deviceId, deviceTrusted, clientMode)
        sslSocket.addHandshakeCompletedListener { event: HandshakeCompletedEvent ->
            // Start a new thread because some Android versions don't allow calling sslSocket.getOutputStream() from the callback
            ThreadHelper.runOnNewThread {
                val mode = if (clientMode) "client" else "server"
                try {
                    val secureIdentityPacket: NetworkPacket?
                    if (protocolVersion >= 8) {
                        val myDeviceInfo = DeviceHelper.getDeviceInfo(context)
                        val myIdentity = myDeviceInfo.toIdentityPacket()

                        val writer = sslSocket.getOutputStream()
                        writer.write(myIdentity.serialize().toByteArray(Charsets.UTF_8))
                        writer.flush()
                        val line = readLineBounded(sslSocket.getInputStream(), MAX_IDENTITY_PACKET_SIZE)
                        // Do not trust the identity packet we received unencrypted
                        secureIdentityPacket = NetworkPacket.unserialize(line)
                        if (!DeviceInfo.isValidIdentityPacket(secureIdentityPacket)) {
                            Log.e("KDE/LanLinkProvider", "Identity packet isn't valid")
                            sslSocket.closeSafe()
                            return@runOnNewThread
                        }
                        val newProtocolVersion = secureIdentityPacket.getInt("protocolVersion")
                        if (newProtocolVersion != protocolVersion) {
                            Log.e("KDE/LanLinkProvider", "Protocol version changed half-way through the handshake: $protocolVersion -> $newProtocolVersion")
                            sslSocket.closeSafe()
                            return@runOnNewThread
                        }
                        val newDeviceId = secureIdentityPacket.getString("deviceId")
                        if (newDeviceId != deviceId) {
                            Log.e("KDE/LanLinkProvider", "Device ID changed half-way through the handshake: $deviceId -> $newDeviceId")
                            sslSocket.closeSafe()
                            return@runOnNewThread
                        }
                    } else {
                        secureIdentityPacket = identityPacket
                    }
                    val certificate = event.peerCertificates[0]
                    val deviceInfo = DeviceInfo.fromIdentityPacketAndCert(secureIdentityPacket, certificate)
                    Log.i("KDE/LanLinkProvider", "Handshake as $mode successful with ${deviceInfo.name} secured with ${event.cipherSuite}")
                    if (deviceTrusted) {
                        val outgoing = connectionStarted == ConnectionStarted.Remotely // we opened the TCP connection
                        val port = LastKnownAddress.rememberedPort(outgoing, socket.port, identityPacket.getIntOrNull("tcpPort"))
                        LastKnownAddress.save(TrustedDevices.getDeviceSettings(context, deviceId), socket.inetAddress, port)
                    }
                    addOrUpdateLink(sslSocket, deviceInfo)
                } catch (e: SerializationException) {
                    Log.e("KDE/LanLinkProvider", "Remote device doesn't correctly implement protocol version 8", e)
                    sslSocket.closeSafe()
                } catch (e: IOException) {
                    Log.e("KDE/LanLinkProvider", "Handshake as $mode failed with $deviceId", e)
                    sslSocket.closeSafe()
                }
            }
        }

        //Handshake is blocking, so do it on another thread and free this thread to keep receiving new connection
        Log.d("LanLinkProvider", "Starting handshake")
        sslSocket.startHandshake()
        Log.d("LanLinkProvider", "Handshake done")
    }

    private fun isProtocolDowngrade(deviceId: String, protocolVersion: Int): Boolean {
        val lastKnownProtocolVersion = DeviceInfo.loadProtocolVersionFromSettings(context, deviceId)
        return lastKnownProtocolVersion > protocolVersion
    }

    /**
     * Add or update a link in the [.visibleDevices] map.
     *
     * @param socket           a new Socket, which should be used to send and receive packets from the remote device
     * @param deviceInfo       remote device info
     * @throws IOException if an exception is thrown by [LanLink.reset]
     */
    @WorkerThread
    @Synchronized
    private fun addOrUpdateLink(socket: SSLSocket, deviceInfo: DeviceInfo) {
        if (isStopped) {
            // A handshake that finished after we were switched off must not reconnect us
            Log.i("KDE/LanLinkProvider", "Stopped, not adding a link for ${deviceInfo.id}")
            socket.closeSafe()
            return
        }
        var linkCreated = false
        val link = visibleDevices.computeIfAbsent(deviceInfo.id) {
            linkCreated = true
            LanLink(context, this, deviceInfo, socket)
        }

        if (linkCreated) {
            Log.d("KDE/LanLinkProvider", "Creating a new link for device ${deviceInfo.id}")
            onConnectionReceived(link)
            link.startListening()
        } else {
            if (link.deviceInfo.certificate != deviceInfo.certificate) {
                Log.e("LanLinkProvider", "LanLink was asked to replace a socket but the certificate doesn't match, aborting")
                socket.closeSafe()
                return
            }
            Log.d("KDE/LanLinkProvider", "Reusing same link for device ${deviceInfo.id}")
            link.reset(socket, deviceInfo)
            onDeviceInfoUpdated(deviceInfo)
        }
    }

    @Synchronized
    private fun setupUdpListener() {
        if (udpServer != null) {
            return
        }
        var newUdpServer: DatagramSocket? = null
        try {
            newUdpServer = DatagramSocket(null)
            newUdpServer.reuseAddress = true
            newUdpServer.broadcast = true
            newUdpServer.bind(InetSocketAddress(UDP_PORT))
            udpServer = newUdpServer
        } catch (e: SocketException) {
            // We ignore this exception and continue without being able to receive broadcasts instead of crashing the app.
            Log.e("LanLinkProvider", "Error binding udp server. We can send udp broadcasts but not receive them", e)
            newUdpServer?.closeSafe()
            return
        }
        ThreadHelper.runOnNewThread {
            Log.i("UdpListener", "Starting UDP listener")
            while (!isStopped) {
                try {
                    val packet = DatagramPacket(ByteArray(MAX_UDP_PACKET_SIZE), MAX_UDP_PACKET_SIZE)
                    newUdpServer.receive(packet)
                    ThreadHelper.runOnNewThread {
                        try {
                            udpPacketReceived(packet)
                        } catch (e: Exception) {
                            Log.e(
                                "LanLinkProvider",
                                "Unhandled exception receiving incoming UDP connection",
                                e
                            )
                        }
                    }
                } catch (e: IOException) {
                    Log.e("LanLinkProvider", "UdpReceive exception", e)
                }
            }
            Log.w("UdpListener", "Stopping UDP listener")
        }
    }

    @Synchronized
    private fun setupTcpListener() {
        if (tcpServer != null) {
            return
        }
        val newTcpServer: ServerSocket
        try {
            newTcpServer = openServerSocketOnFreePort(MIN_PORT)
            tcpServer = newTcpServer
        } catch (e: IOException) {
            Log.e("LanLinkProvider", "Error opening tcp server", e)
            return
        }
        ThreadHelper.runOnNewThread {
            while (!isStopped) {
                try {
                    val socket = newTcpServer.accept()
                    configureSocket(socket)
                    ThreadHelper.runOnNewThread {
                        try {
                            tcpPacketReceived(socket)
                        } catch (e: IOException) {
                            socket.closeSafe()
                            Log.e("LanLinkProvider", "Exception receiving incoming TCP connection", e)
                        } catch (e: CertificateException) {
                            socket.closeSafe()
                            Log.e("LanLinkProvider", "Exception receiving incoming TCP connection", e)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("LanLinkProvider", "TcpReceive exception", e)
                }
            }
            Log.w("TcpListener", "Stopping TCP listener")
        }
    }

    private fun broadcastUdpIdentityPacket(network: Network?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
            Log.w("LanLinkProvider", "Will not UDP broadcast, missing ACCESS_LOCAL_NETWORK permission")
            return
        }
        ThreadHelper.runOnNewThread {
            val ipList = ArrayList<InetAddress>()
            if (TrustedNetworkHelper.isTrustedNetwork(context)) {
                ipList.add(InetAddress.getByName("255.255.255.255")) // Default: broadcast.
            } else {
                Log.i("LanLinkProvider", "Current network isn't trusted, not broadcasting")
            }
            updateCustomDevicesList()
            ipList.addAll(customDeviceAddresses)
            if (ipList.isNotEmpty()) {
                sendUdpIdentityPacket(ipList, network)
            }
        }
    }

    @WorkerThread
    fun sendUdpIdentityPacket(ipList: List<InetAddress>, network: Network?) {
        val tcpServer = tcpServer
        if (tcpServer?.isBound != true) {
            Log.i("LanLinkProvider", "Won't broadcast UDP packet if TCP socket is not ready yet")
            return
        }

        // TODO: In protocol version 8 this packet doesn't need to contain identity info
        //       since it will be exchanged after the socket is encrypted.
        val myDeviceInfo = DeviceHelper.getDeviceInfo(context)
        val identity = myDeviceInfo.toIdentityPacket()
        identity["tcpPort"] = tcpServer.getLocalPort()

        val bytes: ByteArray
        try {
            bytes = identity.serialize().toByteArray(Charsets.UTF_8)
        } catch (e: SerializationException) {
            Log.e("KDE/LanLinkProvider", "Failed to serialize identity packet", e)
            return
        }

        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            if (network != null) {
                try {
                    network.bindSocket(socket)
                } catch (e: IOException) {
                    Log.w("LanLinkProvider", "Couldn't bind socket to the network")
                    e.printStackTrace()
                }
            }
            socket.setReuseAddress(true)
            socket.setBroadcast(true)
        } catch (e: SocketException) {
            Log.e("KDE/LanLinkProvider", "Failed to create DatagramSocket", e)
            socket?.closeSafe()
            return
        }

        for (ip in ipList) {
            try {
                socket.send(DatagramPacket(bytes, bytes.size, ip, MIN_PORT))
                //Log.i("KDE/LanLinkProvider","Udp identity packet sent to address "+client);
            } catch (e: IOException) {
                Log.e("KDE/LanLinkProvider", "Sending udp identity packet failed. Invalid address? ($ip)", e)
            }
        }

        socket.closeSafe()
    }

    override fun onStart() {
        //Log.i("KDE/LanLinkProvider", "onStart");
        isStopped = false

        updateCustomDevicesList()
        customDevicePreferences.registerOnSharedPreferenceChangeListener(customDeviceListener)

        setupUdpListener()
        setupTcpListener()

        synchronized(mdnsDiscovery) {
            mdnsDiscovery.startDiscovering()
            if (TrustedNetworkHelper.isTrustedNetwork(context)) {
                mdnsDiscovery.startAnnouncing()
            }
        }

        broadcastUdpIdentityPacket(null)
        connectToKnownDevicesSoon()

        directConnectRetryJob?.cancel()
        directConnectRetryJob = networkChangeScope.launch {
            // The delay doesn't advance while the phone sleeps, and no attempt is made with the screen off
            while (isActive) {
                delay(DIRECT_CONNECT_RETRY_MS)
                if (!isStopped && context.getSystemService<PowerManager>()?.isInteractive == true) {
                    connectToKnownDevices()
                }
            }
        }
    }

    /**
     * Connects directly to paired devices that aren't connected, at the address they were last connected on.
     * This doesn't rely on them announcing themselves, which a sleeping or long-running computer may not do.
     */
    fun connectToKnownDevices() {
        if (isStopped || !TrustedNetworkHelper.isTrustedNetwork(context)) {
            return
        }
        val targets = directConnectTargets(
            TrustedDevices.getAllTrustedDevices(context),
            visibleDevices.keys,
            directConnectsInFlight,
        ) { LastKnownAddress.load(TrustedDevices.getDeviceSettings(context, it)) }
        for ((deviceId, endpoint) in targets) {
            val protocolVersion = DeviceInfo.loadProtocolVersionFromSettings(context, deviceId)
            if (protocolVersion < MIN_DIRECT_CONNECT_PROTOCOL_VERSION || !directConnectsInFlight.add(deviceId)) {
                continue
            }
            ThreadHelper.runOnNewThread {
                try {
                    Log.i("KDE/LanLinkProvider", "Connecting directly to $deviceId at $endpoint")
                    connectAndIdentify(endpoint.address, endpoint.port, directConnectIdentity(deviceId, protocolVersion), deviceTrusted = true)
                } finally {
                    directConnectsInFlight.remove(deviceId)
                }
            }
        }
    }

    @Synchronized
    override fun onNetworkChange(network: Network?) {
        // Stop announcing right away, or we could announce ourselves on an untrusted network
        if (!TrustedNetworkHelper.isTrustedNetwork(context)) {
            synchronized(mdnsDiscovery) {
                mdnsDiscovery.stopAnnouncing() // noop if already stopped
            }
        }
        if (network != null) {
            handleNetworkChange(network)
        } else {
            // Debounce refreshes without a target network
            networkChangeJob?.cancel()
            networkChangeJob = networkChangeScope.launch {
                delay(timeMillis = NETWORK_CHANGE_DEBOUNCE_MS)
                handleNetworkChange(null)
            }
        }
    }

    private fun handleNetworkChange(network: Network?) {
        if (isStopped) return
        setupUdpListener()
        setupTcpListener()

        broadcastUdpIdentityPacket(network)
        synchronized(mdnsDiscovery) {
            if (TrustedNetworkHelper.isTrustedNetwork(context)) {
                mdnsDiscovery.startAnnouncing() // noop if already announcing
            } else {
                mdnsDiscovery.stopAnnouncing() // noop if already stopped
            }
            mdnsDiscovery.stopDiscovering()
            mdnsDiscovery.startDiscovering()
        }
        connectToKnownDevicesSoon()
    }

    /**
     * Gives devices that answer our announcement time to connect first: a direct connection made at the same
     * moment can cross theirs, and each side then closes the socket the other is using.
     */
    private fun connectToKnownDevicesSoon() {
        directConnectJob?.cancel()
        directConnectJob = networkChangeScope.launch {
            delay(DIRECT_CONNECT_DELAY_MS)
            connectToKnownDevices()
        }
    }

    override fun onStop() {
        //Log.i("KDE/LanLinkProvider", "onStop");
        directConnectRetryJob?.cancel()
        directConnectRetryJob = null
        directConnectJob?.cancel()
        directConnectJob = null
        isStopped = true
        customDevicePreferences.unregisterOnSharedPreferenceChangeListener(customDeviceListener)
        synchronized(mdnsDiscovery) {
            mdnsDiscovery.stopAnnouncing()
            mdnsDiscovery.stopDiscovering()
        }
        tcpServer?.closeSafe()
        udpServer?.closeSafe()
        tcpServer = null
        udpServer = null
        // Close the links too, so the other devices see us go right away
        visibleDevices.values.forEach { it.disconnect() }
    }

    override val name: String = "LanLinkProvider"

    override val priority: Int = 20

    val tcpPort: Int?
        get() = tcpServer?.getLocalPort()

    private enum class ConnectionStarted {
        Locally, Remotely
    }

    companion object {
        private const val UDP_PORT: Int = 1716
        private const val MIN_PORT: Int = 1716
        private const val MAX_PORT: Int = 1764
        const val PAYLOAD_TRANSFER_MIN_PORT: Int = 1739

        private const val MAX_IDENTITY_PACKET_SIZE: Int = 1024 * 512
        private const val MAX_UDP_PACKET_SIZE: Int = 1024 * 512

        const val MILLIS_DELAY_BETWEEN_CONNECTIONS_TO_SAME_DEVICE: Long = 1000L

        private const val MAX_RATE_LIMIT_ENTRIES: Int = 255
        private const val MAX_UNPAIRED_CONNECTIONS: Int = 42
        private const val NETWORK_CHANGE_DEBOUNCE_MS: Long = 200
        const val CONNECT_TIMEOUT_MS: Int = 5_000
        const val DIRECT_CONNECT_RETRY_MS: Long = 30_000L
        private const val DIRECT_CONNECT_DELAY_MS: Long = 2_500L
        // Older protocols take the identity from before encryption, which a direct connection doesn't have
        private const val MIN_DIRECT_CONNECT_PROTOCOL_VERSION = 8

        /**
         * The identity used as the remote device's before a direct connection. From protocol version 8 on, the
         * real identity is exchanged once the connection is encrypted, so only the id and version are needed.
         */
        fun directConnectIdentity(deviceId: String, protocolVersion: Int): NetworkPacket =
            NetworkPacket(NetworkPacket.PACKET_TYPE_IDENTITY).also { np ->
                np["deviceId"] = deviceId
                np["protocolVersion"] = protocolVersion
            }

        /** Paired devices worth a direct connection: not connected, no attempt running, and a known address. */
        fun directConnectTargets(
            trustedDeviceIds: List<String>,
            connectedDeviceIds: Set<String>,
            inFlight: Set<String>,
            knownAddress: (String) -> InetSocketAddress?,
        ): Map<String, InetSocketAddress> =
            trustedDeviceIds
                .filter { it !in connectedDeviceIds && it !in inFlight }
                .mapNotNull { id -> knownAddress(id)?.let { id to it } }
                .toMap()

        /** Opens a TCP connection, giving up after [timeoutMs] instead of the OS default of a minute or more. */
        @Throws(IOException::class)
        fun openSocket(endpoint: InetSocketAddress, timeoutMs: Int = CONNECT_TIMEOUT_MS): Socket {
            val socket = SocketFactory.getDefault().createSocket()
            try {
                socket.connect(endpoint, timeoutMs)
            } catch (e: IOException) {
                socket.closeSafe()
                throw e
            }
            return socket
        }

        @Throws(IOException::class)
        fun openServerSocketOnFreePort(minPort: Int): ServerSocket {
            var tcpPort = minPort
            while (tcpPort <= MAX_PORT) {
                try {
                    val candidateServer = ServerSocket(tcpPort)
                    Log.i("KDE/LanLink", "Using port $tcpPort")
                    return candidateServer
                } catch (e: IOException) {
                    tcpPort++
                    if (tcpPort == MAX_PORT) {
                        Log.e("KDE/LanLink", "No ports available")
                        throw e //Propagate exception
                    }
                }
            }
            throw RuntimeException("This should not be reachable")
        }
    }
}
