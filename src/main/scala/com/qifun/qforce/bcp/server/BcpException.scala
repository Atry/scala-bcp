package com.qifun.qforce.bcp.server

import java.io.IOException

sealed abstract class BcpException(message: String = null, cause: Throwable = null)
  extends IOException(message, cause)

object BcpException {

  class UnknownHeadByte(message: String = null, cause: Throwable = null)
    extends BcpException(message, cause)

  class VarintTooBig(message: String = "The varint is too big to read!", cause: Throwable = null)
    extends BcpException(message, cause)

}