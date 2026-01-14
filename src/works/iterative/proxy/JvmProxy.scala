// PURPOSE: Local HTTP proxy that forwards requests with authentication to upstream proxy.
// PURPOSE: Solves JVM tools (sbt, coursier, scala-cli) failing to authenticate with HTTPS proxies.

package works.iterative.proxy

import java.net.{ServerSocket, Socket, InetAddress}
import java.io.{InputStream, OutputStream, IOException}
import java.util.Base64
import java.nio.file.{Files, Path, Paths}
import scala.util.{Try, Using}
import scala.util.control.NonFatal
import scala.scalanative.posix.unistd.getpid
import scala.scalanative.posix.signal.{kill, SIGTERM}

/** Upstream proxy configuration parsed from environment. */
case class ProxyConfig(
    host: String,
    port: Int,
    user: String,
    pass: String
):
  def authHeader: String =
    val credentials = s"$user:$pass"
    val encoded = Base64.getEncoder.encodeToString(credentials.getBytes("UTF-8"))
    s"Basic $encoded"

object ProxyConfig:
  /** Parse proxy URL from environment variables. */
  def fromEnv(): Option[ProxyConfig] =
    val proxyUrl = sys.env.get("HTTPS_PROXY").orElse(sys.env.get("HTTP_PROXY"))
    proxyUrl.flatMap(parse)

  /** Parse proxy URL like http://user:pass@host:port */
  def parse(url: String): Option[ProxyConfig] =
    if !url.contains("@") then None
    else Try {
      val afterProtocol = url.split("://", 2)(1)
      val Array(credsAndHost, portStr) = afterProtocol.split(":(?=[^:]*$)", 2)
      val port = portStr.toInt

      val atIndex = credsAndHost.lastIndexOf('@')
      val creds = credsAndHost.substring(0, atIndex)
      val host = credsAndHost.substring(atIndex + 1)

      val colonIndex = creds.indexOf(':')
      val user = creds.substring(0, colonIndex)
      val pass = creds.substring(colonIndex + 1)

      ProxyConfig(host, port, user, pass)
    }.toOption

/** Bidirectional tunnel between two sockets. */
object Tunnel:
  private val BufferSize = 65536

  /** Forward data in one direction until EOF or error. */
  private def forward(from: InputStream, to: OutputStream, onClose: () => Unit): Unit =
    val buffer = new Array[Byte](BufferSize)
    try
      var continue = true
      while continue do
        val bytesRead = from.read(buffer)
        if bytesRead == -1 then
          continue = false
        else
          to.write(buffer, 0, bytesRead)
          to.flush()
    catch
      case _: IOException => // Connection closed
    finally
      onClose()

  /** Run bidirectional tunnel between client and upstream. */
  def run(client: Socket, upstream: Socket): Unit =
    val clientIn = client.getInputStream
    val clientOut = client.getOutputStream
    val upstreamIn = upstream.getInputStream
    val upstreamOut = upstream.getOutputStream

    @volatile var closed = false
    def closeAll(): Unit =
      if !closed then
        closed = true
        Try(client.close())
        Try(upstream.close())

    // Two threads for bidirectional forwarding
    val clientToUpstream = new Thread(() => forward(clientIn, upstreamOut, closeAll))
    val upstreamToClient = new Thread(() => forward(upstreamIn, clientOut, closeAll))

    clientToUpstream.setDaemon(true)
    upstreamToClient.setDaemon(true)

    clientToUpstream.start()
    upstreamToClient.start()

    // Wait for both directions to complete
    clientToUpstream.join()
    upstreamToClient.join()

/** HTTP CONNECT request handler. */
object ConnectHandler:
  /** Handle CONNECT tunneling to target via upstream proxy. */
  def handle(client: Socket, targetHost: String, targetPort: Int, config: ProxyConfig): Unit =
    var upstream: Socket = null
    try
      upstream = new Socket(config.host, config.port)
      upstream.setSoTimeout(30000)

      val request = StringBuilder()
      request.append(s"CONNECT $targetHost:$targetPort HTTP/1.1\r\n")
      request.append(s"Host: $targetHost:$targetPort\r\n")
      request.append(s"Proxy-Authorization: ${config.authHeader}\r\n")
      request.append("Proxy-Connection: keep-alive\r\n")
      request.append("\r\n")

      val out = upstream.getOutputStream
      out.write(request.toString.getBytes("UTF-8"))
      out.flush()

      // Read response until we get \r\n\r\n
      val in = upstream.getInputStream
      val response = new StringBuilder
      val buffer = new Array[Byte](4096)
      var foundEnd = false
      while !foundEnd do
        val bytesRead = in.read(buffer)
        if bytesRead == -1 then
          foundEnd = true
        else
          response.append(new String(buffer, 0, bytesRead, "UTF-8"))
          if response.toString.contains("\r\n\r\n") then
            foundEnd = true

      val statusLine = response.toString.split("\r\n")(0)
      if !statusLine.contains("200") then
        client.getOutputStream.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".getBytes("UTF-8"))
        return

      // Send success to client
      client.getOutputStream.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes("UTF-8"))
      client.getOutputStream.flush()

      // Clear timeout for tunnel
      upstream.setSoTimeout(0)

      // Run bidirectional tunnel
      Tunnel.run(client, upstream)

    catch
      case NonFatal(e) =>
        Try(client.getOutputStream.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".getBytes("UTF-8")))
    finally
      if upstream != null then Try(upstream.close())

/** Client connection handler. */
object ClientHandler:
  /** Handle incoming client connection. */
  def handle(client: Socket, config: ProxyConfig): Unit =
    try
      val in = client.getInputStream
      val buffer = new Array[Byte](4096)
      val request = new StringBuilder

      // Read until we get \r\n\r\n (end of headers)
      var foundEnd = false
      while !foundEnd do
        val bytesRead = in.read(buffer)
        if bytesRead == -1 then
          return
        request.append(new String(buffer, 0, bytesRead, "UTF-8"))
        if request.toString.contains("\r\n\r\n") then
          foundEnd = true

      val firstLine = request.toString.split("\r\n")(0)
      if firstLine.startsWith("CONNECT") then
        val parts = firstLine.split(" ")
        val target = parts(1)
        val Array(host, portStr) = target.split(":")
        ConnectHandler.handle(client, host, portStr.toInt, config)
      else
        client.getOutputStream.write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes("UTF-8"))
    finally
      Try(client.close())

/** POSIX process utilities for Scala Native. */
object Process:
  /** Get current process ID using POSIX getpid(). */
  def currentPid: Int = getpid()

  /** Check if a process is alive using kill with signal 0. */
  def isAlive(pid: Int): Boolean =
    kill(pid, 0) == 0

  /** Terminate a process using SIGTERM. */
  def terminate(pid: Int): Boolean =
    kill(pid, SIGTERM) == 0

/** Proxy server managing lifecycle and connections. */
class ProxyServer(port: Int, config: ProxyConfig):
  private var server: ServerSocket = null
  private val pidFile: Path = Paths.get(s"/tmp/jvm-proxy-$port.pid")

  def start(): Unit =
    server = new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))

    // Write PID file
    Files.writeString(pidFile, Process.currentPid.toString)

    System.err.println(s"JVM Proxy started on localhost:$port")
    System.err.println(s"Forwarding to ${config.host}:${config.port}")

    // Handle shutdown signals
    Runtime.getRuntime.addShutdownHook(new Thread(() => cleanup()))

    // Accept loop
    while true do
      try
        val client = server.accept()
        val handler = new Thread(() => ClientHandler.handle(client, config))
        handler.setDaemon(true)
        handler.start()
      catch
        case _: IOException => return

  private def cleanup(): Unit =
    Try(Files.deleteIfExists(pidFile))
    if server != null then Try(server.close())

/** Command-line interface. */
object JvmProxy:
  val DefaultPort = 13130

  def main(args: Array[String]): Unit =
    val (command, port) = parseArgs(args)

    command match
      case "start"  => cmdStart(port)
      case "stop"   => cmdStop(port)
      case "status" => cmdStatus(port)
      case "env"    => cmdEnv(port)
      case _        => printUsage()

  private def parseArgs(args: Array[String]): (String, Int) =
    var command = ""
    var port = DefaultPort
    var i = 0
    while i < args.length do
      args(i) match
        case "-p" | "--port" if i + 1 < args.length =>
          port = args(i + 1).toInt
          i += 2
        case arg if !arg.startsWith("-") && command.isEmpty =>
          command = arg
          i += 1
        case _ =>
          i += 1
    (command, port)

  private def getPid(port: Int): Option[Int] =
    val pidFile = Paths.get(s"/tmp/jvm-proxy-$port.pid")
    Try {
      val pid = Files.readString(pidFile).trim.toInt
      if Process.isAlive(pid) then Some(pid)
      else None
    }.toOption.flatten

  private def cmdStart(port: Int): Unit =
    getPid(port) match
      case Some(_) =>
        System.err.println(s"Proxy already running on port $port")
        sys.exit(1)
      case None =>
        ProxyConfig.fromEnv() match
          case None =>
            System.err.println("Error: No HTTP_PROXY/HTTPS_PROXY configured in environment")
            sys.exit(1)
          case Some(config) =>
            val server = new ProxyServer(port, config)
            server.start()

  private def cmdStop(port: Int): Unit =
    getPid(port) match
      case Some(pid) =>
        Process.terminate(pid)
        println(s"Stopped proxy (PID $pid)")
      case None =>
        println("Proxy not running")

  private def cmdStatus(port: Int): Unit =
    getPid(port) match
      case Some(pid) =>
        println(s"Proxy running on port $port (PID $pid)")
      case None =>
        println(s"Proxy not running on port $port")

  private def cmdEnv(port: Int): Unit =
    println(s"""export JAVA_TOOL_OPTIONS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=$port -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=$port"""")

  private def printUsage(): Unit =
    System.err.println("""Usage: jvm-proxy <command> [--port PORT]
      |
      |Commands:
      |  start   Start the proxy (default port: 13130)
      |  stop    Stop the proxy
      |  status  Check if proxy is running
      |  env     Print environment variables to set
      |
      |Example:
      |  eval $(jvm-proxy env)
      |  jvm-proxy start &
      |  sbt compile
      |""".stripMargin)
    sys.exit(1)
