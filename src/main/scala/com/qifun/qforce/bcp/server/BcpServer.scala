package com.qifun.qforce.bcp.server

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
import com.qifun.statelessFuture.io.SocketInputStream
import com.qifun.statelessFuture.io.SocketWritingQueue
import com.qifun.statelessFuture.Future
import scala.collection.immutable.Queue
import com.qifun.qforce.bcp.Bcp._
import BcpServer._
import com.qifun.qforce.bcp.BcpIo
import com.qifun.qforce.bcp.BcpException
import com.qifun.qforce.bcp.BcpSession
import com.qifun.qforce.bcp.BcpSession._

object BcpServer {

  private implicit val (logger, formater, appender) = ZeroLoggerFactory.newLogger(this)

  trait Session extends BcpSession {
    private[BcpServer] final def internalOpen() {
      open()
    }

    private[BcpServer] final var sessionId: Array[Byte] = null

    private[BcpServer] final var bcpServer: BcpServer[_ >: this.type] = null

    override private[bcp] final def internalExecutor = bcpServer.executor

    override private[bcp] final def release()(implicit txn: InTxn) {
      val removedSessionOption = bcpServer.sessions.remove(sessionId)
      assert(removedSessionOption == Some(Session.this))
    }
  }
}

/**
 * 处理BCP协议的服务器。
 */
abstract class BcpServer[Session <: BcpServer.Session: ClassTag] {
  import BcpServer.appender
  import BcpServer.formater

  protected def executor: ScheduledExecutorService

  private val sessions = TMap.empty[BoxedSessionId, Session]

  protected final def addIncomingSocket(socket: AsynchronousSocketChannel) {
    val stream = new Stream(socket)
    implicit def catcher: Catcher[Unit] = PartialFunction.empty
    for (ConnectionHead(sessionId, connectionId) <- BcpIo.receiveHead(stream)) {
      atomic { implicit txn =>
        val session = sessions.get(sessionId) match {
          case None => {
            val session = classTag[Session].runtimeClass.newInstance().asInstanceOf[Session]
            session.sessionId = sessionId
            session.bcpServer = this
            sessions(sessionId) = session
            Txn.afterCommit(_ => session.internalOpen())
            session
          }
          case Some(session) => {
            session
          }
        }
        session.addIncomingStream(connectionId, stream)
      }
    }

  }

}
