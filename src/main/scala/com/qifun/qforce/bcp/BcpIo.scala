package com.qifun.qforce.bcp

import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import scala.annotation.tailrec
import com.qifun.qforce.bcp.Bcp._
import com.qifun.statelessFuture.Future
import com.qifun.statelessFuture.io.SocketInputStream
import com.qifun.statelessFuture.io.SocketWritingQueue
import java.io.EOFException
import java.io.IOException
import scala.collection.mutable.ArrayBuffer

private[bcp] object BcpIo {

  final def receiveUnsignedVarint(socket: SocketInputStream): Future[Int] = {
    def receiveRestBytes(result: Int, i: Int): Future[Int] = Future[Int] {
      (socket.available_=(1)).await
      socket.read() match {
        case -1 => throw new EOFException
        case b => {
          if (i < 32) {
            if (b >= 0x80) {
              receiveRestBytes(
                result | ((b & 0x7f) << i),
                i + 7).await
            } else {
              result | (b << i)
            }
          } else {
            throw new BcpException.VarintTooBig
          }

        }
      }
    }
    receiveRestBytes(0, 0)
  }

  @tailrec
  final def writeUnsignedVarint(buffer: ByteBuffer, value: Int) {
    if ((value & 0xFFFFFF80) == 0) {
      buffer.put(value.toByte)
      return ;
    } else {
      buffer.put(((value & 0x7F) | 0x80).toByte)
      writeUnsignedVarint(buffer, value >>> 7)
    }
  }

  final def enqueue(stream: SocketWritingQueue, pack: Acknowledge.type) {
    stream.enqueue(ByteBuffer.wrap(Array[Byte](Acknowledge.HeadByte)))
  }

  final def enqueue(stream: SocketWritingQueue, pack: Renew.type) {
    stream.enqueue(ByteBuffer.wrap(Array[Byte](Acknowledge.HeadByte)))
  }

  final def enqueue(stream: SocketWritingQueue, pack: Finish.type) {
    stream.enqueue(ByteBuffer.wrap(Array[Byte](Finish.HeadByte)))
  }

  final def enqueue(stream: SocketWritingQueue, pack: RetransmissionFinish) {
    val headBuffer = ByteBuffer.allocate(20)
    headBuffer.put(RetransmissionFinish.HeadByte)
    writeUnsignedVarint(headBuffer, pack.connectionId)
    writeUnsignedVarint(headBuffer, pack.packId)
    headBuffer.flip()
    stream.enqueue(headBuffer)
  }

  final def enqueue(stream: SocketWritingQueue, pack: RetransmissionData) {
    val headBuffer = ByteBuffer.allocate(20)
    headBuffer.put(RetransmissionData.HeadByte)
    writeUnsignedVarint(headBuffer, pack.connectionId)
    writeUnsignedVarint(headBuffer, pack.packId)
    writeUnsignedVarint(headBuffer, pack.buffer.length)
    headBuffer.flip()
    stream.enqueue((headBuffer +: pack.buffer.view): _*)
  }

  final def enqueue(stream: SocketWritingQueue, pack: Data) {
    val headBuffer = ByteBuffer.allocate(20)
    headBuffer.put(Data.HeadByte)
    writeUnsignedVarint(headBuffer, pack.buffer.length)
    headBuffer.flip()
    stream.enqueue((headBuffer +: pack.buffer.view): _*)
  }

  final def enqueue(stream: SocketWritingQueue, pack: ShutDown.type) {
    stream.enqueue(ByteBuffer.wrap(Array[Byte](ShutDown.HeadByte)))
  }

  final def enqueue(stream: SocketWritingQueue, pack: HeartBeat.type) {
    stream.enqueue(ByteBuffer.wrap(Array[Byte](HeartBeat.HeadByte)))
  }

  final def enqueue(stream: SocketWritingQueue, pack: Packet) {
    pack match {
      case pack @ Acknowledge => {
        enqueue(stream, pack)
      }
      case pack: Data => {
        enqueue(stream, pack)
      }
      case pack @ Finish => {
        enqueue(stream, pack)
      }
      case pack: RetransmissionData => {
        enqueue(stream, pack)
      }
      case pack: RetransmissionFinish => {
        enqueue(stream, pack)
      }
      case pack @ ShutDown => {
        enqueue(stream, pack)
      }
      case pack @ HeartBeat => {
        enqueue(stream, pack)
      }
      case pack @ Renew => {
        enqueue(stream, pack)
      }
    }
  }

  @throws(classOf[IOException])
  final def receive(stream: SocketInputStream) = Future[ClientToServer] {
    stream.available_=(1).await
    stream.read() match {
      case Data.HeadByte => {
        val length = receiveUnsignedVarint(stream).await
        if (length > MaxDataByteNum) {
          throw new BcpException.DataTooBig
        }
        stream.available_=(length).await
        val buffer = new ArrayBuffer[ByteBuffer]
        stream.move(buffer, length)
        Data(buffer)
      }
      case RetransmissionData.HeadByte => {
        val connectionId = receiveUnsignedVarint(stream).await
        val packId = receiveUnsignedVarint(stream).await
        val length = receiveUnsignedVarint(stream).await
        stream.available_=(length).await
        val buffer = new ArrayBuffer[ByteBuffer]
        stream.move(buffer, length)
        RetransmissionData(connectionId, packId, buffer)
      }
      case RetransmissionFinish.HeadByte => {
        val connectionId = receiveUnsignedVarint(stream).await
        val packId = receiveUnsignedVarint(stream).await
        RetransmissionFinish(connectionId, packId)
      }
      case Acknowledge.HeadByte => Acknowledge
      case Renew.HeadByte => {
        Renew
      }
      case Finish.HeadByte => {
        Finish
      }
      case ShutDown.HeadByte => {
        ShutDown
      }
      case HeartBeat.HeadByte => {
        HeartBeat
      }
      case _ => throw new BcpException.UnknownHeadByte
    }

  }

  final def receiveHead(stream: SocketInputStream) = Future {
    val sessionId = receiveSessionId(stream).await
    val connectionId = receiveUnsignedVarint(stream).await
    ConnectionHead(sessionId, connectionId)
  }

  private def receiveSessionId(stream: SocketInputStream) = Future[Array[Byte]] {
    stream.available_=(NumBytesSessionId).await
    var sessionId = Array.ofDim[Byte](NumBytesSessionId)
    stream.read(sessionId)
    sessionId
  }

  final def enqueueHead(stream: SocketWritingQueue, sessionId: Array[Byte], connectionId: Int) = {
    val headBuffer = ByteBuffer.allocate(NumBytesSessionId + 5)
    headBuffer.put(sessionId)
    writeUnsignedVarint(headBuffer, connectionId)
    headBuffer.flip()
    stream.enqueue(headBuffer)
  }
}