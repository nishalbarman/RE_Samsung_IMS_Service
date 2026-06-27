//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.net.IpSecManager
import android.net.IpSecTransform
import android.net.Network
import android.system.Os
import android.system.OsConstants
import android.telephony.Rlog
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.StandardProtocolFamily
import java.nio.ByteBuffer
import java.nio.channels.Channel
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectableChannel

import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.spi.SelectorProvider

/* wrapper around sockets + establish ipsec tunnel given ipsec helpers */
interface SipConnection {
    fun close()
    fun enableIpsec(
        ipSecBuilder: IpSecTransform.Builder,
        ipSecManager: IpSecManager,
        clientSpiC: IpSecManager.SecurityParameterIndex,
        serverSpiS: IpSecManager.SecurityParameterIndex
    )
    fun gLocalAddr(): InetAddress
    fun connect(remotePort: Int)
    fun gWriter(): OutputStream
    fun gReader(): SipReader
    fun gLocalPort(): Int
    fun getChannel(): SelectableChannel
}

class SipConnectionTcp(
    val network: Network,
    val remoteAddr: InetAddress,
    val _localAddr: InetAddress? = null,
    val _localPort: Int = 0
) : SipConnection {
    val socket: Socket
    /* redefine public localAddr/port for when not specified in argument */
    var localAddr: InetAddress
    var localPort: Int
    var remotePort: Int = 0
    lateinit var writer: OutputStream
    lateinit var reader: SipReader
    // we need to keep the transform around or the ipsec transform
    // gets destroyed while still in use
    lateinit var inTransform: IpSecTransform
    lateinit var outTransform: IpSecTransform
    var connected = false

    init {
        socket = network.socketFactory.createSocket()
        if (_localAddr != null) {
            socket.bind(InetSocketAddress(_localAddr, _localPort))
        }
        localAddr = socket.localAddress
        localPort = socket.localPort
    }

    override fun connect(_remotePort: Int) {
        remotePort = _remotePort
        socket.connect(InetSocketAddress(remoteAddr, remotePort))
        if (_localAddr == null) {
            // localAddr/Port only valid after connect if no explicit bind
            localAddr = socket.localAddress
            localPort = socket.localPort
        }
        writer = socket.getOutputStream()
        reader = socket.getInputStream().sipReader()
        connected = true
    }

    override fun gWriter(): OutputStream {
        return writer
    }

    override fun gReader(): SipReader {
        return reader
    }

    override fun gLocalPort(): Int {
        return localPort
    }

    override fun getChannel(): SelectableChannel {
        return socket.channel
    }

    override fun close() {
        socket.close()
    }

    override fun enableIpsec(
        ipSecBuilder: IpSecTransform.Builder,
        ipSecManager: IpSecManager,
        clientSpiC: IpSecManager.SecurityParameterIndex,
        serverSpiS: IpSecManager.SecurityParameterIndex
    ) {
        // Can only do this before connecting?
        check(!connected)
        inTransform = ipSecBuilder.buildTransportModeTransform(remoteAddr, clientSpiC)
        ipSecManager.applyTransportModeTransform(socket, IpSecManager.DIRECTION_IN, inTransform)
        outTransform = ipSecBuilder.buildTransportModeTransform(localAddr, serverSpiS)
        ipSecManager.applyTransportModeTransform(socket, IpSecManager.DIRECTION_OUT, outTransform)
    }

    override fun gLocalAddr(): InetAddress {
        return localAddr
    }
}

class SipConnectionTcpServer(
    val network: Network,
    val remoteAddr: InetAddress,
    val localAddr: InetAddress,
    val localPort: Int
) {
    val serverSocketFd: FileDescriptor
    lateinit var inTransform: IpSecTransform
    lateinit var outTransform: IpSecTransform

    init {
        serverSocketFd = Os.socket(
            if (localAddr is Inet6Address) OsConstants.AF_INET6 else OsConstants.AF_INET,
            OsConstants.SOCK_STREAM,
            OsConstants.IPPROTO_TCP
        )
        network.bindSocket(serverSocketFd)
        Os.setsockoptInt(serverSocketFd, OsConstants.SOL_SOCKET, OsConstants.SO_REUSEADDR, 1)
        Os.bind(serverSocketFd, localAddr, localPort)
        Os.listen(serverSocketFd, 1)
    }

    fun accept(): Pair<SipReader, OutputStream> {
        val clientFd = Os.accept(serverSocketFd, null)
        return Pair(FileInputStream(clientFd).sipReader(), FileOutputStream(clientFd))
    }

    fun enableIpsec(
        ipSecManager: IpSecManager,
        inTransform: IpSecTransform,
        outTransform: IpSecTransform
    ) {
        this.inTransform = inTransform
        ipSecManager.applyTransportModeTransform(
            serverSocketFd,
            IpSecManager.DIRECTION_IN,
            inTransform
        )
        this.outTransform = outTransform
        ipSecManager.applyTransportModeTransform(
            serverSocketFd,
            IpSecManager.DIRECTION_OUT,
            outTransform
        )
    }
}

class SipConnectionUdp(
    val network: Network,
    val remoteAddr: InetAddress,
    val _localAddr: InetAddress? = null,
    val _localPort: Int = 0,
) : SipConnection {
    val socket: DatagramSocket
    /* redefine public localAddr/port for when not specified in argument */
    var localAddr: InetAddress
    var localPort: Int
    var remotePort: Int = 0
    lateinit var writer: OutputStream
    lateinit var reader: SipReader
    // we need to keep the transform around or the ipsec transform
    // gets destroyed while still in use
    lateinit var inTransform: IpSecTransform
    lateinit var outTransform: IpSecTransform
    var connected = false

    init {
        val channel = DatagramChannel.open(if(remoteAddr is Inet6Address) StandardProtocolFamily.INET6 else StandardProtocolFamily.INET)
        if (_localAddr != null) {
            channel.bind(InetSocketAddress(_localAddr, _localPort))
        }
        socket = channel.socket()
        network.bindSocket(socket)

        localAddr = socket.localAddress
        localPort = socket.localPort
    }

    override fun connect(_remotePort: Int) {
        remotePort = _remotePort
        // Note: DO NOT connect, because the answers might come back from a different IP than where we sent to
        //socket.connect(InetSocketAddress(remoteAddr, remotePort))
        if (_localAddr == null) {
            // localAddr/Port only valid after connect if no explicit bind
            localAddr = socket.localAddress
            localPort = socket.localPort
        }
        writer = object: OutputStream() {
            override fun write(p0: Int) {
                write(byteArrayOf(p0.toByte()))
            }
            override fun write(p0: ByteArray) {
                // Send using the datagram channel
                socket.channel.send(ByteBuffer.wrap(p0), InetSocketAddress(remoteAddr, remotePort))
            }
        }
        reader = object: InputStream() {
            val currentDgram = DatagramPacket(ByteArray(128*1024), 128*1024)
            var currentPosition = 0
            var currentSize = 0

            fun recvPacket() {
                // select()
                select(listOf(getChannel()))
                socket.receive(currentDgram)
                currentPosition = 0
                currentSize = currentDgram.length
            }

            override fun read(): Int {
                if (currentPosition >= currentSize) {
                    recvPacket()
                }
                val ret =  currentDgram.data[currentPosition++].toInt()
                return ret
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (currentPosition >= currentSize) {
                    recvPacket()
                }
                val toRead = minOf(len, currentSize - currentPosition)
                currentDgram.data.copyInto(b, off, currentPosition, currentPosition + toRead)
                currentPosition += toRead
                return toRead
            }
        }.sipReader()
        connected = true
    }

    override fun gWriter(): OutputStream {
        return writer
    }

    override fun gReader(): SipReader {
        return reader
    }

    override fun gLocalPort(): Int {
        return localPort
    }

    override fun getChannel(): SelectableChannel {
        return socket.channel
    }

    override fun close() {
        socket.close()
    }

    override fun enableIpsec(
        ipSecBuilder: IpSecTransform.Builder,
        ipSecManager: IpSecManager,
        clientSpiC: IpSecManager.SecurityParameterIndex,
        serverSpiS: IpSecManager.SecurityParameterIndex
    ) {
        // Can only do this before connecting?
        check(!connected)
        inTransform = ipSecBuilder.buildTransportModeTransform(remoteAddr, clientSpiC)
        ipSecManager.applyTransportModeTransform(socket, IpSecManager.DIRECTION_IN, inTransform)
        outTransform = ipSecBuilder.buildTransportModeTransform(localAddr, serverSpiS)
        ipSecManager.applyTransportModeTransform(socket, IpSecManager.DIRECTION_OUT, outTransform)
    }

    override fun gLocalAddr(): InetAddress {
        return localAddr
    }
}

class SipConnectionUdpServer(
    val network: Network,
    val remoteAddr: InetAddress,
    val localAddr: InetAddress,
    val localPort: Int) {

    val socketFd : FileDescriptor
    lateinit var inTransform: IpSecTransform
    lateinit var outTransform: IpSecTransform
    init {
        socketFd = Os.socket(
            if (localAddr is Inet6Address) OsConstants.AF_INET6 else OsConstants.AF_INET,
            OsConstants.SOCK_DGRAM,
            OsConstants.IPPROTO_UDP
        )
        network.bindSocket(socketFd)
        Os.setsockoptInt(socketFd, OsConstants.SOL_SOCKET, OsConstants.SO_REUSEADDR, 1)
        Os.bind(socketFd, localAddr, localPort)
    }

    fun gReader(): SipReader {
        return object: InputStream() {
            val currentDgram = DatagramPacket(ByteArray(128*1024), 128*1024)
            var currentPosition = 0
            var currentSize = 0

            fun recvPacket() {
                // select()
                receive(currentDgram)
                currentPosition = 0
                currentSize = currentDgram.length
            }

            override fun read(): Int {
                if (currentPosition >= currentSize) {
                    recvPacket()
                }
                val ret = currentDgram.data[currentPosition++].toInt()
                return ret
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (currentPosition >= currentSize) {
                    recvPacket()
                }
                val toRead = minOf(len, currentSize - currentPosition)
                currentDgram.data.copyInto(b, off, currentPosition, currentPosition + toRead)
                currentPosition += toRead
                return toRead
            }
        }.sipReader()
    }

    fun receive(packet: DatagramPacket) {
        val source = InetSocketAddress(0)
        val received = Os.recvfrom(
            socketFd,
            packet.data,
            packet.offset,
            packet.length,
            0,
            source
        )
        packet.length = received
        packet.address = source.address
        packet.port = source.port
    }

    fun send(packet: DatagramPacket) {
        Os.sendto(
            socketFd,
            packet.data,
            packet.offset,
            packet.length,
            0,
            packet.address,
            packet.port
        )
    }

    fun enableIpsec(
        ipSecManager: IpSecManager,
        inTransform: IpSecTransform,
        outTransform: IpSecTransform
    ) {
        this.inTransform = inTransform
        ipSecManager.applyTransportModeTransform(
            socketFd,
            IpSecManager.DIRECTION_IN,
            inTransform
        )
        this.outTransform = outTransform
        ipSecManager.applyTransportModeTransform(
            socketFd,
            IpSecManager.DIRECTION_OUT,
            outTransform
        )
    }

    fun getChannel(): SelectableChannel {
        throw UnsupportedOperationException("UDP server uses a raw FileDescriptor")
    }
}

private var didRequestSocketHiddenApiAccess = false

private fun requestSocketHiddenApiAccess() {
    if (didRequestSocketHiddenApiAccess) return
    didRequestSocketHiddenApiAccess = true

    try {
        val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
        val getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime")
        val setHiddenApiExemptions =
            vmRuntimeClass.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
        val runtime = getRuntime.invoke(null)
        setHiddenApiExemptions.invoke(
            runtime,
            arrayOf(
                "Ljava/net/DatagramSocket;",
                "Ljava/net/ServerSocket;",
                "Ljava/net/Socket;",
                "Ljava/net/SocketImpl;",
                "Lsun/nio/ch/",
                "Ljava/io/FileDescriptor;"
            )
        )
    } catch (_: Throwable) {}
}

fun getSocketFileDescriptor(socket: Any): FileDescriptor {
    requestSocketHiddenApiAccess()

    // Try 1: Walk socket class hierarchy for "impl" field (declared in ServerSocket/DatagramSocket)
    try {
        var kls = socket.javaClass
        while (kls != Any::class.java) {
            try {
                val implField = kls.getDeclaredField("impl")
                implField.isAccessible = true
                val socketImpl = implField.get(socket)
                var implKls = socketImpl.javaClass
                while (implKls != Any::class.java) {
                    try {
                        val fdField = implKls.getDeclaredField("fd")
                        fdField.isAccessible = true
                        return fdField.get(socketImpl) as FileDescriptor
                    } catch (_: NoSuchFieldException) {
                        implKls = implKls.superclass ?: break
                    }
                }
            } catch (_: NoSuchFieldException) {
                kls = kls.superclass ?: break
            }
        }
    } catch (_: Throwable) {}

    // Try 2: Get channel, then try FileDescriptor and int fd fields
    try {
        val m = socket.javaClass.getMethod("getChannel")
        val ch = m.invoke(socket) ?: throw Exception("no channel")
        // Try FileDescriptor field (fd)
        var kls = ch.javaClass
        while (kls != Any::class.java) {
            try {
                val f = kls.getDeclaredField("fd")
                f.isAccessible = true
                return f.get(ch) as FileDescriptor
            } catch (_: NoSuchFieldException) {
                kls = kls.superclass ?: break
            }
        }
        // Try integer fd fields (fdVal, fd, descriptor, etc.)
        kls = ch.javaClass
        while (kls != Any::class.java) {
            for (f in kls.declaredFields) {
                if (f.type == Integer.TYPE || f.type == Integer::class.java) {
                    f.isAccessible = true
                    val intFd = f.getInt(ch)
                    if (intFd >= 0) {
                        val fd = FileDescriptor()
                        try {
                            val fdIntField = fd.javaClass.getDeclaredField("descriptor")
                            fdIntField.isAccessible = true
                            fdIntField.setInt(fd, intFd)
                            return fd
                        } catch (_: Throwable) {
                            try {
                                val fdIntField = fd.javaClass.getDeclaredField("fd")
                                fdIntField.isAccessible = true
                                fdIntField.setInt(fd, intFd)
                                return fd
                            } catch (_: Throwable) {}
                        }
                    }
                }
            }
            kls = kls.superclass ?: break
        }
    } catch (_: Throwable) {}

    throw RuntimeException("Cannot get FileDescriptor from $socket")
}

fun select(channels: List<SelectableChannel>): Int {
    var returnValue = -1
    Selector.open().use { selector ->
        for (channel in channels) {
            channel.configureBlocking(false)
            channel.register(selector, SelectionKey.OP_READ)
        }

        val nSelectedKeys = selector.select()
        for (key in selector.selectedKeys()) {
            if (key.isReadable) {
                val index = channels.indexOf(key.channel())
                if (index != -1) {
                    Rlog.e("PHH", "When selecting got result $index")
                    returnValue = index
                    break
                }
            }
        }
    }
    for (channel in channels) {
        channel.configureBlocking(true)
    }

    return returnValue
}
