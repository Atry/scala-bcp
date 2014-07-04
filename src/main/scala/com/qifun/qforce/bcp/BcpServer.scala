package com.qifun.qforce.bcp

import com.dongxiguo.fastring.Fastring.Implicits._
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import scala.annotation.tailrec
import scala.collection.mutable.WrappedArray
import scala.concurrent.duration.DurationInt
import scala.concurrent.stm.InTxn
import scala.concurrent.stm.Ref
import scala.concurrent.stm.TMap
import scala.concurrent.stm.TSet
import scala.concurrent.stm.Txn
import scala.concurrent.stm.atomic
import scala.reflect.ClassTag
import scala.reflect.classTag
import scala.util.control.NoStackTrace
import scala.util.control.Exception.Catcher
import com.qifun.statelessFuture.util.io.SocketInputStream
import com.qifun.statelessFuture.util.io.SocketWritingQueue
import com.qifun.statelessFuture.Future
import scala.collection.immutable.Queue
import com.qifun.qforce.bcp.Bcp._
import BcpServer._
import com.qifun.qforce.bcp.BcpSession._
import com.dongxiguo.fastring.Fastring.Implicits._

object BcpServer {

  private implicit val (logger, formatter, appender) = ZeroLoggerFactory.newLogger(this)

  private[BcpServer] final class Stream(socket: AsynchronousSocketChannel) extends BcpSession.Stream(socket) {
    // 服务端专有的数据结构
  }

  private[BcpServer] final class Connection extends BcpSession.Connection[Stream] {
    // 服务端专有的数据结构
  }

}

/**
 * 处理BCP协议的服务器。
 */
abstract class BcpServer {
  import BcpServer.logger
  import BcpServer.appender
  import BcpServer.formatter

  trait Session extends BcpSession[BcpServer.Stream, BcpServer.Connection] {

    override private[bcp] final def newConnection = new BcpServer.Connection

    private[BcpServer] final def internalOpen() {
      open()
    }

    protected val sessionId: Array[Byte]

    override private[bcp] final def internalExecutor = executor

    override private[bcp] final def release()(implicit txn: InTxn) {
      val removedSessionOption = sessions.remove(sessionId)
      assert(removedSessionOption == Some(Session.this))
    }

    override private[bcp] final def busy(connectionId: Int, connection: BcpServer.Connection)(implicit txn: InTxn): Unit = {
    }

    override private[bcp] final def idle(connectionId: Int, connection: BcpServer.Connection)(implicit txn: InTxn): Unit = {
    }

    override private[bcp] def close(connectionId: Int, connection: BcpServer.Connection)(implicit txn: InTxn): Unit = {
    }

  }

  protected def executor: ScheduledExecutorService

  private val sessions = TMap.empty[BoxedSessionId, Session]

  protected def newSession(sessionId: Array[Byte]): Session

  protected final def addIncomingSocket(socket: AsynchronousSocketChannel) {
    logger.fine(fast"bcp server add incoming socket: ${socket}")
    val stream = new BcpServer.Stream(socket)
    implicit def catcher: Catcher[Unit] = {
      case e: Exception => {
        logger.warning(e.getMessage())
      }
    }
    val connectFuture = Future {
      val head = BcpIo.receiveHead(stream).await
      val ConnectionHead(sessionId, connectionId) = head
      logger.fine(fast"server received sessionId: ${sessionId.toSeq} , connectionId: ${connectionId}")
      atomic { implicit txn =>
        val session = sessions.get(sessionId) match {
          case None => {
            val session = newSession(sessionId)
            sessions(sessionId) = session
            Txn.afterCommit(_ => session.internalOpen())
            session
          }
          case Some(session) => {
            session
          }
        }
        session.addStream(connectionId, stream)
      }
    }
    for (_ <- connectFuture) {}
  }

}
