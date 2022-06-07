package com.example.pod_android.droneOnUsb

import java.nio.ByteBuffer
import java.nio.ByteOrder

open class CrtpPacket {
    private var mPacketHeader: Header? = null
    private var mPacketPayload: ByteArray? = null
    private var mSerializedPacket: ByteArray? = null
    private var mExpectedReply: ByteArray? = null

    /*! Header class */
    inner class Header {
        private var mChannel: Int = 0
        private var mPort: CrtpPort? = null
        private var isNull: Boolean = false

        fun getChannel() = mChannel
        fun getPort() = mPort
        constructor(header: Byte) {
            if (header.toInt() != -1) {
                mChannel = header.toInt() and 0x03
                mPort = CrtpPort.getPortByNumber((header.toInt() shr 4))
            } else {
                mChannel = 0
                mPort = CrtpPort.UNKNOWN
            }
        }
        constructor(channel: Int, port: CrtpPort?) {
            mChannel = channel
            mPort = port
        }

        fun getByte(): Byte {
            if (isNull)
                return 0xff.toByte()
            return (((mPort?.getNumber()!! and 0x0f) shl 4) or (mChannel and 0x03)).toByte()
        }

        override fun toString(): String {
            return "Header - Ch: $mChannel, Pt: $mPort"
        }

        override fun hashCode(): Int {
            val prime: Int = 31
            var result: Int = 1
            result = prime * result + if (isNull) 1231 else 1237
            result = prime * result + mChannel
            result = prime * result + if (mPort == null) 0 else mPort.hashCode()
            return result
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null) return false
            if (other !is Header) return false
            if (isNull != other.isNull) return false
            if (mChannel != other.mChannel) return false
            if (mPort != other.mPort) return false
            return true
        }
    }

    /*! constructors */
    constructor() {
        mPacketHeader = null
        mPacketPayload = null
    }
    constructor(channel: Int, port: CrtpPort) {
        mPacketHeader = Header(channel, port)
        mPacketPayload = ByteArray(0)
    }
    constructor(header: Byte, packetPayload: ByteArray) {
        mPacketHeader = Header(header)
        mPacketPayload = packetPayload
    }
    constructor(packet: ByteArray) {
        mPacketHeader = Header(packet[0])
        mPacketPayload = packet.copyOfRange(1, packet.size)
    }

    companion object {
        val BYTE_ORDER: ByteOrder = ByteOrder.LITTLE_ENDIAN
        val NULL_PACKET: CrtpPacket = CrtpPacket(0xff.toByte(), ByteArray(0))
    }

    protected open fun serializeData(b: ByteBuffer) {
        b.put(mPacketPayload!!)
    }
    protected open fun getDataByteCount() = mPacketPayload?.size

    fun getHeaderByte() = mPacketHeader?.getByte()
    fun getHeader() = mPacketHeader
    fun getPayload() = mPacketPayload
    fun toByteArray(): ByteArray {
        if (mSerializedPacket == null) {
            var b: ByteBuffer = ByteBuffer.allocate(getDataByteCount()!! + 1).order(BYTE_ORDER)
            b.put(getHeaderByte()!!)
            serializeData(b)
            mSerializedPacket = b.array()
        }
        return mSerializedPacket!!
    }

    fun getExpectedReply(): ByteArray? = mExpectedReply
    fun setExpectedReply(pExpectedReply: ByteArray) {
        mExpectedReply = pExpectedReply
    }

    override fun toString(): String {
        return "CrtpPacket - Ch: ${mPacketHeader?.getChannel()}, Pt: ${mPacketHeader?.getPort()}"
    }

    override fun hashCode(): Int {
        val prime = 31
        var result = 1
        result = prime * result + mExpectedReply.hashCode()
        result = prime * result + if (mPacketHeader == null) 0 else mPacketHeader.hashCode()
        result = prime * result + mPacketPayload.hashCode()
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null)  return false
        if (other !is CrtpPacket) return false
        if (mExpectedReply?.equals(other.mExpectedReply) == false) return false
        if (mPacketHeader == null) {
            if (other.mPacketHeader != null)
                return false
        } else if (mPacketHeader != other.mPacketHeader)
            return false
        return mPacketPayload?.equals(other.mPacketPayload)!!
    }
}