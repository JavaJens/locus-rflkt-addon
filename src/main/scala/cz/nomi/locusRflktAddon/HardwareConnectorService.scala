package cz.nomi.locusRflktAddon

import scala.collection.JavaConversions._

import org.scaloid.common._

import android.content.Intent

import java.util.UUID

import com.wahoofitness.connector
import connector.HardwareConnector
import connector.HardwareConnectorTypes.{NetworkType, SensorType}
import connector.HardwareConnectorEnums.HardwareConnectorState
import connector.conn.connections
import connections.SensorConnection
import connections.params.ConnectionParams
import connector.listeners.discovery.DiscoveryListener
import connector.capabilities
import capabilities.Capability.CapabilityType
import capabilities.ConfirmConnection
import capabilities.Rflkt
import connector.HardwareConnectorEnums.{SensorConnectionError, SensorConnectionState}
import com.wahoofitness.common.display
import display.{DisplayConfiguration, DisplayButtonPosition}

class HardwareConnectorService extends LocalService with Log {
  import HardwareConnectorService._

  private var hwCon: HardwareConnector = null

  onCreate {
    info(s"HardwareConnectorService: onCreate")
    hwCon = new HardwareConnector(ctx, Callback)
  }

  onDestroy {
    info(s"HardwareConnectorService: onDestroy")
    hwCon.stopDiscovery(networkType)
    hwCon.shutdown()
  }

  override def onTaskRemoved(rootIntent: Intent) {
    if (curSensor.isEmpty)
      stopSelf()
  }

  private object Callback extends HardwareConnector.Callback {
    def connectedSensor(s: SensorConnection) {
      info(s"connectedSensor: $s")
      curSensor = Some(s)
      lastSensor() = s.getConnectionParams.serialize

      getCapConfirm().get.addListener(Confirmation)
      getCapRflkt().get.addListener(RFLKT)

      requestConfirmation()
    }

    def disconnectedSensor(s: SensorConnection) {
      info(s"disconnectedSensor: $s")
      curSensor = None
    }

    def connectorStateChanged(nt: NetworkType, state: HardwareConnectorState) {
      info(s"connectorStateChanged: $nt, $state")
    }

    def hasData() {}

    def onFirmwareUpdateRequired(s: SensorConnection, current: String, recommended: String) {
      info(s"onFirmwareUpdateRequired: $s, $current, $recommended")
    }
  }

  private object Discovery extends DiscoveryListener {
    def onDeviceDiscovered(params: ConnectionParams) {
      info(s"onDeviceDiscovered: $params")
      toast(s"discovered: ${params.getName}")
    }

    def onDiscoveredDeviceLost(params: ConnectionParams) {
      info(s"onDiscoveredDeviceLost: $params")
      toast(s"lost: ${params.getName}")
    }

    def onDiscoveredDeviceRssiChanged(params: ConnectionParams, rssi: Int) {
      info(s"onDiscoveredDeviceRssiChanged: $params, $rssi")
    }
  }

  private object Connection extends SensorConnection.Listener {
    def onNewCapabilityDetected(s: SensorConnection, typ: CapabilityType) {
      info(s"onNewCapabilityDetected: $s, $typ")
    }

    def onSensorConnectionError(s: SensorConnection, e: SensorConnectionError) {
      info(s"onSensorConnectionError: $s, $e")
      toast(s"${s.getDeviceName}: $e")
    }

    def onSensorConnectionStateChanged(s: SensorConnection, state: SensorConnectionState) {
      info(s"onSensorConnectionStateChanged: $s, $state")
      toast(s"${s.getDeviceName}: $state")
    }
  }

  private object Confirmation extends ConfirmConnection.Listener {
    def onConfirmationProcedureStateChange(state: ConfirmConnection.State, error: ConfirmConnection.Error) {
      info(s"onConfirmationProcedureStateChange: $state, $error")
      if (state == ConfirmConnection.State.FAILED) {
        requestConfirmation()
      }
    }

    def onUserAccept() {
      info(s"onUserAccept")
      loadConfig()
    }
  }

  private object RFLKT extends Rflkt.Listener {
    import Rflkt.{ButtonPressType, LoadConfigResult}
    import connector.packets.dcp.response.DCPR_DateDisplayOptionsPacket._

    def onAutoPageScrollRecieved() {}
    def onBacklightPercentReceived(p: Int) {}
    def onButtonPressed(pos: DisplayButtonPosition, typ: ButtonPressType) {}
    def onButtonStateChanged(pos: DisplayButtonPosition, pressed: Boolean) {}
    def onColorInvertedReceived(inverted: Boolean) {}
    def onConfigVersionsReceived(ver: Array[Int]) {}
    def onDateReceived(date: java.util.Calendar) {}
    def onDisplayOptionsReceived(x1: DisplayDateFormat, x2: DisplayTimeFormat, x3: DisplayDayOfWeek, x4: DisplayWatchFaceStyle) {}
    def onLoadComplete() {
      info(s"onLoadComplete")
    }
    def onLoadFailed(result: LoadConfigResult) {
      info(s"onLoadFailed: $result")
    }
    def onLoadProgressChanged(progress: Int) {
      info(s"onLoadProgressChanged: $progress")
    }
    def onPageIndexReceived(index: Int) {}
    def onSleepOnDisconnectReceived(state: Boolean) {}
  }

  def enableDiscovery(enable: Boolean): Unit = enable match {
    case true =>
      hwCon.startDiscovery(sensorType, networkType, Discovery)
    case false =>
      hwCon.stopDiscovery(networkType)
  }

  def connectFirst() {
    val params = hwCon.getDiscoveredConnectionParams(networkType, sensorType).headOption orElse lastSensorOption
    params match {
      case Some(p) =>
        hwCon.requestSensorConnection(p, Connection)
        hwCon.stopDiscovery(networkType)
      case None => toast("no sensor to connect to")
    }
  }

  private def getCap[T](typ: CapabilityType): Option[T] =
    curSensor map { s => s.getCurrentCapability(typ).asInstanceOf[T] }

  private def getCapConfirm(): Option[ConfirmConnection] =
    getCap(CapabilityType.ConfirmConnection)

  private def getCapRflkt(): Option[Rflkt] =
    getCap(CapabilityType.Rflkt)

  private def requestConfirmation() {
    getCapConfirm() foreach {
      _.requestConfirmation(ConfirmConnection.Role.MASTER, "Locus", getUuid, "LocusRflktAddon")
    }
  }

  private def loadConfig() {
    val config = DisplayConfiguration.fromRawResource(getResources, R.raw.display_cfg_rflkt_default)
    getCapRflkt() foreach {
      _.loadConfig(config)
    }
  }

  def setRflkt(vars: Map[String, String]) {
    info(s"setRflkt: $vars")
  }

  private val uuid = preferenceVar("")
  private def getUuid: UUID = uuid() match {
    case s if s.nonEmpty =>
      UUID.fromString(s)
    case "" =>
      val u = UUID.randomUUID()
      uuid() = u.toString
      u
  }

  private val lastSensor = preferenceVar("")
  private def lastSensorOption: Option[ConnectionParams] =
    Option(lastSensor()) filter (_.nonEmpty) map (ConnectionParams.fromString)

  private var curSensor: Option[SensorConnection] = None
}

object HardwareConnectorService {
  private val sensorType = SensorType.DISPLAY
  private val networkType = NetworkType.BTLE
}