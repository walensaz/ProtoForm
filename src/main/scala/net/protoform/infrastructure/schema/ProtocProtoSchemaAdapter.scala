package net.protoform.infrastructure.schema

import net.protoform.core.error.DomainError
import net.protoform.core.schema.ServiceSchema
import net.protoform.ports.schema.{ProtoSourceParserPort, SchemaParserPort}

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

object ProtocProtoSchemaAdapter {
  type CommandRunner = Seq[String] => Either[String, (Int, String)]

  def defaultCommandRunner(command: Seq[String]): Either[String, (Int, String)] = {
    try {
      val pb = new ProcessBuilder(command.asJava)
      pb.redirectErrorStream(true)
      val process = pb.start()
      val output = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
      val finished = process.waitFor(30, TimeUnit.SECONDS)
      if (!finished) {
        process.destroyForcibly()
        Left("protoc timed out after 30 seconds.")
      } else {
        Right((process.exitValue(), output))
      }
    } catch {
      case _: IOException =>
        Left(s"Could not execute '${command.headOption.getOrElse("protoc")}'. Ensure protoc is installed on PATH or configured in Settings.")
      case NonFatal(e) =>
        Left(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
    }
  }
}

class ProtocProtoSchemaAdapter(
  descriptorSetAdapter: SchemaParserPort = new DescriptorSetSchemaAdapter(),
  commandRunner: ProtocProtoSchemaAdapter.CommandRunner = ProtocProtoSchemaAdapter.defaultCommandRunner
) extends ProtoSourceParserPort {

  override def loadProtoFiles(
    protoFiles: List[Path],
    includeDirs: List[Path],
    protocExecutable: Option[String] = None
  ): Either[DomainError, List[ServiceSchema]] = {
    val cleanFiles = protoFiles.distinct
    if (cleanFiles.isEmpty) {
      return Left(DomainError.SchemaParseError("protoc", "No .proto files were selected."))
    }

    val missingFile = cleanFiles.find(p => !Files.exists(p) || !Files.isRegularFile(p))
    if (missingFile.isDefined) {
      return Left(DomainError.SchemaParseError(missingFile.get.toString, "Selected .proto file does not exist."))
    }

    val missingInclude = includeDirs.find(d => !Files.exists(d) || !Files.isDirectory(d))
    if (missingInclude.isDefined) {
      return Left(DomainError.SchemaParseError(missingInclude.get.toString, "Include directory does not exist."))
    }

    val executable = protocExecutable.map(_.trim).filter(_.nonEmpty).getOrElse("protoc")
    val normalizedExplicitIncludes = includeDirs.map(_.toAbsolutePath.normalize()).distinct
    val parentIncludes = cleanFiles.flatMap(p => Option(p.toAbsolutePath.normalize().getParent)).distinct
    // Append parent directories only if not already nested under an explicit -I root so package-relative imports resolve cleanly.
    val extraParents = parentIncludes.filterNot { parent =>
      normalizedExplicitIncludes.exists(root => parent.startsWith(root))
    }
    val allIncludes = (normalizedExplicitIncludes ++ extraParents).distinct

    var tempDesc: Path = null
    try {
      tempDesc = Files.createTempFile("protoform-protoc-", ".desc")
      val cmd = Vector(
        executable,
        s"--descriptor_set_out=${tempDesc.toAbsolutePath}",
        "--include_imports"
      ) ++ allIncludes.map(d => s"-I${d.toString}") ++ cleanFiles.map(_.toAbsolutePath.normalize().toString)

      commandRunner(cmd) match {
        case Left(err) =>
          Left(DomainError.SchemaParseError(cleanFiles.head.toString, err))
        case Right((exitCode, output)) if exitCode != 0 =>
          val detail = if (output.nonEmpty) output else s"protoc exited with status $exitCode"
          Left(DomainError.SchemaParseError(cleanFiles.head.toString, detail))
        case Right(_) =>
          descriptorSetAdapter.loadDescriptorSet(tempDesc)
      }
    } catch {
      case NonFatal(e) =>
        Left(DomainError.SchemaParseError(
          cleanFiles.head.toString,
          Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
        ))
    } finally {
      if (tempDesc != null) {
        try {
          Files.deleteIfExists(tempDesc)
        } catch {
          case NonFatal(_) => ()
        }
      }
    }
  }
}
