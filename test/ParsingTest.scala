// PURPOSE: Tests for argument and URL parsing
// PURPOSE: Ensures CLI args and proxy URLs are parsed correctly

//> using scala 3.3.5
//> using dep org.scalameta::munit::1.0.0

import scala.util.Try

// Copy of Args for testing
case class Args(
    command: String = "",
    port: Int = 13130,
    upstream: Option[String] = None
)

object Args:
  def parse(args: Array[String]): Args =
    var result = Args()
    var i = 0
    while i < args.length do
      args(i) match
        case "-p" | "--port" if i + 1 < args.length =>
          result = result.copy(port = args(i + 1).toInt)
          i += 2
        case "-u" | "--upstream" if i + 1 < args.length =>
          result = result.copy(upstream = Some(args(i + 1)))
          i += 2
        case arg if !arg.startsWith("-") && result.command.isEmpty =>
          result = result.copy(command = arg)
          i += 1
        case _ =>
          i += 1
    result

// Copy of ProxyConfig for testing
case class ProxyConfig(
    host: String,
    port: Int,
    user: String,
    pass: String
)

object ProxyConfig:
  def parse(url: String): Option[ProxyConfig] =
    if !url.contains("@") then None
    else Try {
      // Format: http://user:pass@host:port
      val afterProtocol = url.split("://", 2)(1)

      // Split on last colon to get port
      val lastColon = afterProtocol.lastIndexOf(':')
      val beforePort = afterProtocol.substring(0, lastColon)
      val port = afterProtocol.substring(lastColon + 1).toInt

      // Split on last @ to get host vs credentials
      val atIndex = beforePort.lastIndexOf('@')
      val creds = beforePort.substring(0, atIndex)
      val host = beforePort.substring(atIndex + 1)

      // Split credentials on first colon
      val colonIndex = creds.indexOf(':')
      val user = creds.substring(0, colonIndex)
      val pass = creds.substring(colonIndex + 1)

      ProxyConfig(host, port, user, pass)
    }.toOption

class ArgsParseTest extends munit.FunSuite:

  test("parse command only"):
    val args = Args.parse(Array("start"))
    assertEquals(args.command, "start")
    assertEquals(args.port, 13130)
    assertEquals(args.upstream, None)

  test("parse command with --upstream"):
    val args = Args.parse(Array("start", "--upstream", "http://user:pass@host:8080"))
    assertEquals(args.command, "start")
    assertEquals(args.upstream, Some("http://user:pass@host:8080"))

  test("parse command with -u"):
    val args = Args.parse(Array("start", "-u", "http://user:pass@host:8080"))
    assertEquals(args.command, "start")
    assertEquals(args.upstream, Some("http://user:pass@host:8080"))

  test("parse --upstream before command"):
    val args = Args.parse(Array("--upstream", "http://user:pass@host:8080", "start"))
    assertEquals(args.command, "start")
    assertEquals(args.upstream, Some("http://user:pass@host:8080"))

  test("parse with port"):
    val args = Args.parse(Array("start", "-p", "9999"))
    assertEquals(args.command, "start")
    assertEquals(args.port, 9999)

class ProxyConfigParseTest extends munit.FunSuite:

  test("parse simple proxy URL"):
    val result = ProxyConfig.parse("http://user:pass@proxy:8080")
    assert(result.isDefined, s"Failed to parse, got None")
    val config = result.get
    assertEquals(config.host, "proxy")
    assertEquals(config.port, 8080)
    assertEquals(config.user, "user")
    assertEquals(config.pass, "pass")

  test("parse proxy URL with complex password"):
    val result = ProxyConfig.parse("http://user:p@ss:word@proxy:8080")
    assert(result.isDefined, s"Failed to parse, got None")
    val config = result.get
    assertEquals(config.host, "proxy")
    assertEquals(config.port, 8080)
    assertEquals(config.user, "user")
    assertEquals(config.pass, "p@ss:word")

  test("parse https proxy URL"):
    val result = ProxyConfig.parse("https://user:pass@proxy.example.com:3128")
    assert(result.isDefined, s"Failed to parse, got None")
    val config = result.get
    assertEquals(config.host, "proxy.example.com")
    assertEquals(config.port, 3128)

  test("reject URL without credentials"):
    val result = ProxyConfig.parse("http://proxy:8080")
    assertEquals(result, None)
