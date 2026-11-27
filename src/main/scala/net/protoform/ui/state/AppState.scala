package net.protoform.ui.state

import javafx.beans.property._
import javafx.collections.{FXCollections, ObservableList}
import net.protoform.core.auth.{AuthPreset, AuthProfile, MetadataHeader}
import net.protoform.core.call.RpcCall
import net.protoform.core.model.EndpointTarget
import net.protoform.core.schema.{MethodSchema, ServiceSchema}
import net.protoform.core.workspace.{CallHistoryEntry, EnvironmentConfig, ServiceTemplate, VariableInterpolator, WorkspaceProject}

enum ConnectionStatus {
  case Disconnected
  case Connecting(endpoint: String)
  case Connected(endpoint: String, serviceCount: Int)
  case Failed(endpoint: String, message: String)

  def isConnected: Boolean = this match {
    case Connected(_, _) => true
    case _               => false
  }
}

final case class PinnedResponse(
  label: String,
  methodFullName: String,
  bodyJson: String,
  latencyMs: Option[Long],
  statusText: String
)

class AppState {
  val connectionStatus: ObjectProperty[ConnectionStatus] = new SimpleObjectProperty(ConnectionStatus.Disconnected)
  val activeTarget: ObjectProperty[Option[EndpointTarget]] = new SimpleObjectProperty(None)
  val discoveredServices: ObservableList[ServiceSchema] = FXCollections.observableArrayList()
  val selectedMethod: ObjectProperty[Option[MethodSchema]] = new SimpleObjectProperty(None)
  val activeCall: ObjectProperty[Option[RpcCall]] = new SimpleObjectProperty(None)
  val pinnedBaseline: ObjectProperty[Option[PinnedResponse]] = new SimpleObjectProperty(None)

  val isBusy: BooleanProperty = new SimpleBooleanProperty(false)
  val statusText: StringProperty = new SimpleStringProperty("Ready")
  val schemaSourceLabel: StringProperty = new SimpleStringProperty("Server Reflection")

  val authPreset: ObjectProperty[AuthPreset] = new SimpleObjectProperty(AuthPreset.None)
  val customHeaders: ObservableList[MetadataHeader] = FXCollections.observableArrayList()

  val showDefaultValues: BooleanProperty = new SimpleBooleanProperty(true)
  val preserveProtoFieldNames: BooleanProperty = new SimpleBooleanProperty(true)
  val autoFormatJson: BooleanProperty = new SimpleBooleanProperty(true)
  val useGzipCompression: BooleanProperty = new SimpleBooleanProperty(false)
  val showAdvancedActions: BooleanProperty = new SimpleBooleanProperty(false)
  val showResponsePane: BooleanProperty = new SimpleBooleanProperty(true)
  val currentTheme: StringProperty = new SimpleStringProperty("ProtoForm Carbon")

  val templates: ObservableList[ServiceTemplate] = FXCollections.observableArrayList()
  val templateToLoad: ObjectProperty[Option[ServiceTemplate]] = new SimpleObjectProperty(None)
  val activeTemplate: ObjectProperty[Option[ServiceTemplate]] = new SimpleObjectProperty(None)
  val runTemplateAfterLoad: BooleanProperty = new SimpleBooleanProperty(false)

  val callHistory: ObservableList[CallHistoryEntry] = FXCollections.observableArrayList()
  val historyEntryToLoad: ObjectProperty[Option[CallHistoryEntry]] = new SimpleObjectProperty(None)

  val authProfiles: ObservableList[AuthProfile] = FXCollections.observableArrayList()
  val selectedAuthProfile: ObjectProperty[Option[AuthProfile]] = new SimpleObjectProperty(None)

  val environments: ObservableList[EnvironmentConfig] = FXCollections.observableArrayList()
  val activeEnvironment: ObjectProperty[Option[EnvironmentConfig]] = new SimpleObjectProperty(None)

  val projects: ObservableList[WorkspaceProject] = FXCollections.observableArrayList()
  val activeProject: ObjectProperty[Option[WorkspaceProject]] = new SimpleObjectProperty(None)

  def activeEnvVariables: Map[String, String] = {
    Option(activeEnvironment.get).flatten.map(_.variables).getOrElse(Map.empty)
  }

  def applyAuthProfile(profile: AuthProfile): Unit = {
    selectedAuthProfile.set(Some(profile))
    authPreset.set(profile.preset)
    customHeaders.setAll(profile.customHeaders: _*)
  }

  def getAllHeaders: List[MetadataHeader] = {
    import scala.jdk.CollectionConverters._
    val presetHeaders = Option(authPreset.get).map(_.toHeaders).getOrElse(Nil)
    val custom = customHeaders.asScala.toList.filter(_.enabled)
    presetHeaders ++ custom
  }

  def getResolvedHeaders: List[MetadataHeader] = {
    import scala.jdk.CollectionConverters._
    val vars = activeEnvVariables
    val resolvedPreset = VariableInterpolator.interpolatePreset(Option(authPreset.get).getOrElse(AuthPreset.None), vars)
    val resolvedCustom = VariableInterpolator.interpolateHeaders(customHeaders.asScala.toList.filter(_.enabled), vars)
    resolvedPreset.toHeaders ++ resolvedCustom
  }
}
