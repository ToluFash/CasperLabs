package io.casperlabs.comm.rp

import cats.Id
import com.google.protobuf.ByteString
import io.casperlabs.catscontrib._
import io.casperlabs.catscontrib.effect.implicits._
import io.casperlabs.catscontrib.ski._
import io.casperlabs.comm.CommError._
import io.casperlabs.comm._
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.protocol.routing._
import io.casperlabs.comm.rp.Connect._
import io.casperlabs.comm.rp.ProtocolHelper._
import io.casperlabs.metrics.Metrics
import io.casperlabs.p2p.EffectsTestInstances._
import io.casperlabs.p2p.effects._
import io.casperlabs.shared._
import org.scalatest._

import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}

class ConnectSpec extends FunSpec with Matchers with BeforeAndAfterEach with AppendedClues {

  val defaultTimeout: FiniteDuration = FiniteDuration(1, MILLISECONDS)
  val src: Node                      = peerNode("src", 40400)
  val remote: Node                   = peerNode("remote", 40401)

  type Effect[A] = CommErrT[Id, A]

  implicit val logEff            = new Log.NOPLog[Effect]
  implicit val timeEff           = new LogicalTime[Effect]
  implicit val metricEff         = new Metrics.MetricsNOP[Effect]
  implicit val nodeDiscoveryEff  = new NodeDiscoveryStub[Effect]()
  implicit val transportLayerEff = new TransportLayerStub[Effect]
  implicit val packetHandler     = new PacketHandler.NOPPacketHandler[Effect]
  implicit val connectionsCell   = Cell.unsafe[Effect, Connections](Connect.Connections.empty)
  implicit val rpConfAsk         = createRPConfAsk[Effect](peerNode("src", 40400))

  override def beforeEach(): Unit = {
    nodeDiscoveryEff.reset()
    transportLayerEff.reset()
  }

  describe("Node") {
    describe("when connecting to other remote node") {
      it("should send ProtocolHandshake") {
        // given
        transportLayerEff.setResponses(kp(alwaysSuccess))
        // when
        Connect.connect[Effect](remote, defaultTimeout)
        // then
        transportLayerEff.requests.size should be(1)
        val Protocol(_, Protocol.Message.ProtocolHandshake(_)) = transportLayerEff.requests(0).msg
      }
    }

    describe("when reciving encrypted ProtocolHandshake") {
      it("should send protocol handshake response back to the remote")(pending)
      it("should add node once protocol handshake response is sent")(pending)
      it("should not respond if message can not be decrypted")(pending)
      it("should not respond if it does not contain remotes public key")(pending)
    }

  }

  def alwaysSuccess: Protocol => CommErr[Protocol] =
    kp(Right(protocolHandshake(src)))

  private def peerNode(name: String, port: Int): Node =
    Node(ByteString.copyFrom(name.getBytes), "host", port, port)

}
