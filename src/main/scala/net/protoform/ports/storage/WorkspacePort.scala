package net.protoform.ports.storage

import net.protoform.core.error.DomainError
import net.protoform.core.workspace.WorkspaceData

trait WorkspacePort {
  def loadWorkspace(): Either[DomainError, WorkspaceData]
  def saveWorkspace(data: WorkspaceData): Either[DomainError, Unit]
}
