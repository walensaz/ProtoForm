package net.protoform.infrastructure.schema

import com.google.protobuf.{Descriptors, TypeRegistry}
import net.protoform.core.schema.SchemaHandle

import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable
import scala.jdk.CollectionConverters._

object DescriptorRegistry {
  val shared: DescriptorRegistry = new DescriptorRegistry()
}

class DescriptorRegistry {

  private final case class Entry(
    messages: Map[String, Descriptors.Descriptor],
    typeRegistry: Option[TypeRegistry]
  )

  private val entries = new ConcurrentHashMap[String, Entry]()

  def registerFiles(
    fileDescriptors: Iterable[Descriptors.FileDescriptor],
    handle: SchemaHandle = SchemaHandle.random()
  ): SchemaHandle = {
    val msgMap = mutable.Map.empty[String, Descriptors.Descriptor]
    val visitedFiles = mutable.HashSet.empty[String]
    val typeBuilder = TypeRegistry.newBuilder()

    def collectMessage(desc: Descriptors.Descriptor): Unit = {
      if (desc != null && !msgMap.contains(desc.getFullName)) {
        msgMap.put(desc.getFullName, desc)
        typeBuilder.add(desc)
        desc.getNestedTypes.asScala.foreach(collectMessage)
      }
    }

    def collectFile(fd: Descriptors.FileDescriptor): Unit = {
      if (fd != null && visitedFiles.add(fd.getFullName)) {
        fd.getMessageTypes.asScala.foreach(collectMessage)
        fd.getDependencies.asScala.foreach(collectFile)
      }
    }

    fileDescriptors.foreach(collectFile)
    val typeReg = if (msgMap.isEmpty) None else Some(typeBuilder.build())
    entries.put(handle.value, Entry(msgMap.toMap, typeReg))
    handle
  }

  def registerDescriptors(
    descriptors: Iterable[Descriptors.Descriptor],
    handle: SchemaHandle = SchemaHandle.random()
  ): SchemaHandle = {
    val files = descriptors.flatMap(d => Option(d).flatMap(x => Option(x.getFile)))
    if (files.nonEmpty) {
      registerFiles(files, handle)
    } else {
      val msgMap = descriptors.flatMap(d => Option(d).map(x => x.getFullName -> x)).toMap
      entries.put(handle.value, Entry(msgMap, None))
      handle
    }
  }

  def resolveMessage(handle: SchemaHandle, fullName: String): Option[Descriptors.Descriptor] = {
    if (handle.value.isEmpty) None
    else Option(entries.get(handle.value)).flatMap(_.messages.get(fullName))
  }

  def typeRegistry(handle: SchemaHandle): Option[TypeRegistry] = {
    if (handle.value.isEmpty) None
    else Option(entries.get(handle.value)).flatMap(_.typeRegistry)
  }

  def release(handle: SchemaHandle): Unit = {
    if (handle.value.nonEmpty) {
      entries.remove(handle.value)
    }
  }
}
