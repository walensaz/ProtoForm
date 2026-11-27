package net.protoform.ui.state

import net.protoform.core.auth.{AuthPreset, MetadataHeader}
import net.protoform.core.call.{RpcCall, StreamChunk}
import net.protoform.core.model.CallId
import net.protoform.core.workspace.{AuthSerialization, SavedWorkbenchTab}
import net.protoform.ports.rpc.ActiveClientStream

import java.util.UUID

final class WorkbenchTabSession(
  val id: String = UUID.randomUUID().toString,
  var title: String = "New Request",
  var methodFullName: Option[String] = None,
  var requestJson: String = "{}",
  var authPreset: AuthPreset = AuthPreset.None,
  var customHeaders: List[MetadataHeader] = Nil,
  var selectedAuthProfileId: Option[String] = None,
  var activeTemplateId: Option[String] = None,
  var lastCall: Option[RpcCall] = None,
  var streamChunks: List[StreamChunk] = Nil,
  var userEdited: Boolean = false,
  var lastEditedIsForm: Boolean = true,
  var activeStreamingCallId: Option[CallId] = None,
  var activeClientStream: Option[ActiveClientStream] = None,
  var sentChunkCounter: Long = 0L
) {
  def isStreaming: Boolean = activeStreamingCallId.isDefined || activeClientStream.isDefined

  def toSavedTab: SavedWorkbenchTab = {
    val (pType, pData) = AuthSerialization.encodePreset(authPreset)
    SavedWorkbenchTab(
      id = id,
      title = title,
      methodFullName = methodFullName,
      requestJson = requestJson,
      authPresetType = pType,
      authPresetData = pData,
      customHeaders = customHeaders,
      selectedAuthProfileId = selectedAuthProfileId,
      activeTemplateId = activeTemplateId,
      lastEditedIsForm = lastEditedIsForm
    )
  }
}

object WorkbenchTabSession {
  def fromSavedTab(saved: SavedWorkbenchTab): WorkbenchTabSession = {
    new WorkbenchTabSession(
      id = saved.id,
      title = saved.title,
      methodFullName = saved.methodFullName,
      requestJson = saved.requestJson,
      authPreset = AuthSerialization.decodePreset(saved.authPresetType, saved.authPresetData),
      customHeaders = saved.customHeaders,
      selectedAuthProfileId = saved.selectedAuthProfileId,
      activeTemplateId = saved.activeTemplateId,
      lastEditedIsForm = saved.lastEditedIsForm
    )
  }
}
