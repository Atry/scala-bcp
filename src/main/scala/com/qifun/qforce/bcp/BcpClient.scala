package com.qifun.qforce.bcp

import java.nio.channels.AsynchronousSocketChannel
import java.security.SecureRandom
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import scala.PartialFunction
import scala.concurrent.duration._
import scala.concurrent.stm.InTxn
import scala.concurrent.stm.Ref
import scala.concurrent.stm.Txn
import scala.concurrent.stm.atomic
import scala.reflect.classTag
import scala.util.control.Exception.Catcher
import com.dongxiguo.fastring.Fastring.Implicits._
import com.qifun.qforce.bcp.Bcp._
import com.qifun.qforce.bcp.BcpSession._
import com.qifun.statelessFuture.Future
import com.qifun.statelessFuture.util.Blocking

object BcpClient {

  private implicit val (logger, formatter, appender) = ZeroLoggerFactory.newLogger(this)

  private[BcpClient] final class Stream(bcpClient: BcpClient, id: Int, socket: AsynchronousSocketChannel, internalExecutor: ScheduledExecutorService) extends BcpSession.Stream(socket) {
    // 客户端专有的数据结构，比如Timer
    val connectionId: Int = id
    val executor: ScheduledExecutorService = internalExecutor
    val belongedClient = bcpClient
    val busyTimer = Ref.make[ScheduledFuture[_]]
    val idleTimer = Ref.make[ScheduledFuture[_]]
  }

  private[BcpClient] final class Connection extends BcpSession.Connection[Stream] {

    override private[bcp] final def busy()(implicit txn: InTxn): Unit = {
      logger.info("the connection is busy!")
      val oldBusyTimer = stream().busyTimer()
      if (oldBusyTimer == null || oldBusyTimer.isDone()) {
        val idleTimer = stream().idleTimer()
        Txn.afterCommit(_ => {
          if (idleTimer != null) {
            idleTimer.cancel(false)
          }
        })
        val newBusyTimer = stream().executor.schedule(new Runnable() {
          def run() {
            logger.info("client connect server again")
            stream().belongedClient.internalConnect()
          }
        }, BusyTimeout.length, BusyTimeout.unit)
        Txn.afterRollback(_ => newBusyTimer.cancel(false))
        stream().busyTimer() = newBusyTimer
      }
    }

    override private[bcp] final def idle()(implicit txn: InTxn): Unit = {
      logger.info("the connection is idle!")
      val busyTimer = stream().busyTimer()
      Txn.afterCommit(_ => busyTimer.cancel(false))
      val connectionStream = stream()
      final class IdleRunnable(connection: BcpClient.Connection) extends Runnable {
        def run() {
          connectionStream.belongedClient.closeConnection(connectionStream.connectionId, connection)
        }
      }
      val idleTimer = stream().executor.schedule(new IdleRunnable(this), IdleTimeout.length, IdleTimeout.unit)
      Txn.afterRollback(_ => idleTimer.cancel(false))
      stream().idleTimer() = idleTimer
    }
  }

}

abstract class BcpClient extends BcpSession[BcpClient.Stream, BcpClient.Connection] {

  import BcpClient.{ logger, formatter, appender }

  override private[bcp] final def newConnection = new BcpClient.Connection

  protected def connect(): Future[AsynchronousSocketChannel]

  protected def executor: ScheduledExecutorService

  override private[bcp] final def internalExecutor: ScheduledExecutorService = executor

  override private[bcp] final def release()(implicit txn: InTxn) {}

  private val sessionId: Array[Byte] = Array.ofDim[Byte](NumBytesSessionId)
  private val nextConnectionId = Ref(0)

  private[bcp] final def internalConnect() {
    atomic { implicit txn: InTxn =>
      if (connections.size <= MaxConnectionsPerSession) {
        implicit def catcher: Catcher[Unit] = PartialFunction.empty
        val socket = Blocking.blockingAwait(connect())
        logger.fine(fast"bcp client connect server success, socket: ${socket}")
        val connectionId = nextConnectionId()
        val stream = new BcpClient.Stream(this, connectionId, socket, internalExecutor)
        nextConnectionId() = connectionId + 1
        Txn.afterCommit(_ => {
          BcpIo.enqueueHead(stream, ConnectionHead(sessionId, connectionId))
          stream.flush()
          logger.fine(fast"bcp client send head to server success, sessionId: ${sessionId.toSeq} , connectionId: ${connectionId}")
        })
        addStream(connectionId, stream)
      }
    }
  }

  private final def closeConnection(connectionId: Int, connection: BcpClient.Connection) {
    atomic { implicit txn =>
      if (connections.size > 0) {
        finishConnection(connectionId, connection)
      }
    }
  }

  private final def start() {
    atomic { implicit txn: InTxn =>
      val secureRandom = new SecureRandom
      secureRandom.setSeed(secureRandom.generateSeed(20))
      secureRandom.nextBytes(sessionId)
      internalConnect()
    }
  }

  start()

}