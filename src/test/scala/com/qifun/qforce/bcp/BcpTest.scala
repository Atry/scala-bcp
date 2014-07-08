package com.qifun.qforce.bcp

import org.junit._
import Assert._
import java.nio.channels.AsynchronousSocketChannel
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.nio.ByteBuffer
import com.qifun.statelessFuture.Future
import com.qifun.statelessFuture.util.io.Nio2Future
import java.net.InetSocketAddress
import org.junit.Assert._
import java.nio.channels.AsynchronousChannelGroup
import java.nio.channels.AsynchronousServerSocketChannel
import com.qifun.statelessFuture.util.Blocking
import java.util.concurrent.Executors
import scala.reflect.classTag
import com.qifun.statelessFuture.util.Zip
import com.qifun.statelessFuture.util.Promise
import java.nio.channels.CompletionHandler
import scala.util.Try
import scala.reflect.ClassTag
import scala.util.Success
import scala.util.Failure
import java.nio.channels.ShutdownChannelGroupException
import java.io.IOException
import scala.concurrent.stm.Ref
import scala.concurrent.stm._
import java.util.concurrent.TimeUnit

object BcpTest {
  private implicit val (logger, formatter, appender) = ZeroLoggerFactory.newLogger(this)
}

class BcpTest {
  import BcpTest.{ logger, formatter, appender }

  abstract class TestServer extends BcpServer {

    val executor = Executors.newScheduledThreadPool(1)

    val channelGroup = AsynchronousChannelGroup.withThreadPool(executor)

    val serverSocket = AsynchronousServerSocketChannel.open(channelGroup)

    protected def acceptFailed(throwable: Throwable): Unit

    private def startAccept(serverSocket: AsynchronousServerSocketChannel) {
      try {
        serverSocket.accept(this, new CompletionHandler[AsynchronousSocketChannel, TestServer] {
          def completed(newSocket: java.nio.channels.AsynchronousSocketChannel, server: TestServer): Unit = {
            addIncomingSocket(newSocket)
            startAccept(serverSocket)
          }
          def failed(throwable: Throwable, server: TestServer): Unit = {
            throwable match {
              case e: IOException =>
                logger.fine(e)
              case _ =>
                acceptFailed(throwable)
            }
          }
        })
      } catch {
        case e: ShutdownChannelGroupException =>
          logger.fine(e)
        case e: Exception =>
          acceptFailed(e)
      }
    }

    final def clear() {
      serverSocket.close()
      channelGroup.shutdown()
      if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
        executor.shutdownNow()
      }
    }
    
    serverSocket.bind(null)
    startAccept(serverSocket)
  }

  @Test
  def pingPong(): Unit = {
    val lock = new AnyRef
    @volatile var serverResult: Option[Try[String]] = None
    @volatile var clientResult: Option[Try[String]] = None
    var serverSession: Ref[Option[ServerSession]] = Ref(None)

    abstract class ServerSession { _: BcpServer#Session =>

      override final def available(): Unit = {}

      override final def accepted(): Unit = {
        atomic { implicit txn =>
          serverSession() match {
            case None =>
              serverSession() = Some(this)
            case _ =>
              Txn.afterCommit { _ =>
                lock.synchronized {
                  serverResult = Some(Failure(new Exception("Server session already exist")))
                  lock.notify()
                }
              }
          }
        }
      }

      override final def received(pack: ByteBuffer*): Unit = {
        lock.synchronized {
          val bytes: Array[Byte] = new Array[Byte](pack.head.remaining())
          pack.head.get(bytes)
          serverResult = Some(Success(new String(bytes, "UTF-8")))
          send(ByteBuffer.wrap("pong".getBytes("UTF-8")))
          lock.notify()
        }
      }

      override final def shutedDown(): Unit = {}

      override final def unavailable(): Unit = {}

      final def shutDownSession() = {
        shutDown()
      }

    }

    val server = new TestServer {
      override protected final def newSession(id: Array[Byte]) = new ServerSession with Session {
        override protected final val sessionId = id
      }

      override protected final def acceptFailed(throwable: Throwable): Unit = {
        lock.synchronized {
          serverResult = Some(Failure(throwable))
          lock.notify()
        }
      }
    }

    val client = new BcpClient {

      override final def available(): Unit = {}

      override final def connect(): Future[AsynchronousSocketChannel] = Future[AsynchronousSocketChannel] {
        val socket = AsynchronousSocketChannel.open(server.channelGroup)
        Nio2Future.connect(socket, new InetSocketAddress("localhost", server.serverSocket.getLocalAddress.asInstanceOf[InetSocketAddress].getPort)).await
        socket
      }

      override final def executor = new ScheduledThreadPoolExecutor(2)

      override final def received(pack: ByteBuffer*): Unit = {
        lock.synchronized {
          val bytes: Array[Byte] = new Array[Byte](pack.head.remaining())
          pack.head.get(bytes)
          clientResult = Some(Success(new String(bytes, "UTF-8")))
          lock.notify()
        }
      }

      override final def shutedDown(): Unit = {}

      override final def unavailable(): Unit = {}

    }

    client.send(ByteBuffer.wrap("ping".getBytes("UTF-8")))

    lock.synchronized {
      while (serverResult == None || clientResult == None) {
        lock.wait()
      }
    }

    val Some(serverSome) = serverResult
    val Some(clientSome) = clientResult

    serverSome match {
      case Success(u) => assertEquals(u, "ping")
      case Failure(e) => throw e
    }

    clientSome match {
      case Success(u) => assertEquals(u, "pong")
      case Failure(e) => throw e
    }

    atomic { implicit txn =>
      serverSession() match {
        case Some(session) =>
          Txn.afterCommit(_ => session.shutDownSession())
        case _ =>
      }
    }
    client.shutDown()
    server.clear()
  }

  @Test
  def shutDownTest {
    val lock = new AnyRef
    @volatile var shutedDownResult: Option[Try[Boolean]] = None
    val serverSession: Ref[Option[ServerSession]] = Ref(None)

    abstract class ServerSession { _: BcpServer#Session =>

      override final def available(): Unit = {}

      override final def accepted(): Unit = {
        atomic { implicit txn =>
          serverSession() match {
            case None =>
              serverSession() = Some(this)
            case _ =>
              Txn.afterCommit { _ =>
                lock.synchronized {
                  shutedDownResult = Some(Failure(new Exception("Server session already exist")))
                  lock.notify()
                }
              }
          }
        }
      }

      override final def received(pack: ByteBuffer*): Unit = {}

      override final def shutedDown(): Unit = {
      }

      override final def unavailable(): Unit = {}

      final def shutDownSession() = {
        shutDown()
      }

    }

    val server = new TestServer {
      override protected final def newSession(id: Array[Byte]) = new ServerSession with Session {
        override protected final val sessionId = id
      }

      override protected final def acceptFailed(throwable: Throwable): Unit = {
        lock.synchronized {
          shutedDownResult = Some(Failure(throwable))
          lock.notify()
        }
      }
    }

    val client = new BcpClient {

      override final def available(): Unit = {
      }

      override final def connect(): Future[AsynchronousSocketChannel] = Future[AsynchronousSocketChannel] {
        val socket = AsynchronousSocketChannel.open()
        Nio2Future.connect(socket, new InetSocketAddress("localhost", server.serverSocket.getLocalAddress.asInstanceOf[InetSocketAddress].getPort)).await
        socket
      }

      override final def executor = new ScheduledThreadPoolExecutor(2)

      override final def received(pack: ByteBuffer*): Unit = {}

      override final def shutedDown(): Unit = {
        lock.synchronized {
          shutedDownResult = Some(Success(true))
          lock.notify()
        }
      }

      override final def unavailable(): Unit = {}

    }

    client.shutDown()

    lock.synchronized {
      while (shutedDownResult == None) {
        lock.wait()
      }
    }

    val Some(clientShutedDownSome) = shutedDownResult

    clientShutedDownSome match {
      case Success(u) => assertEquals(u, true)
      case Failure(e) => throw e
    }

    atomic { implicit txn =>
      serverSession() match {
        case Some(session) =>
          Txn.afterCommit(_ => session.shutDownSession())
        case _ =>
      }
    }
    server.clear()
  }
  
  @Test
  def closeConnectionTest {
    
  }
  
}