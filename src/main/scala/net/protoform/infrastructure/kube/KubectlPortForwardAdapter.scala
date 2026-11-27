package net.protoform.infrastructure.kube

import com.google.gson.JsonParser
import net.protoform.core.error.DomainError
import net.protoform.core.model.{ActivePortForward, KubeResourceType, KubeTargetCandidate, KubeTargetSpec}
import net.protoform.ports.infra.KubePortForwardPort

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.{ConcurrentHashMap, CountDownLatch, TimeUnit}
import java.util.regex.Pattern
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

object KubectlPortForwardAdapter {
  private val ForwardLinePattern = Pattern.compile("""Forwarding from (?:\[[^\]]+\]|[^:\s]+):(\d+)\s*->""")

  def parseForwardedLocalPort(line: String): Option[Int] = {
    if (line == null) None
    else {
      val m = ForwardLinePattern.matcher(line)
      if (m.find()) scala.util.Try(m.group(1).toInt).toOption else None
    }
  }

  def parseTargetsJson(json: String, namespace: String, resourceType: KubeResourceType): List[KubeTargetCandidate] = {
    try {
      val root = JsonParser.parseString(json).getAsJsonObject
      val items = root.getAsJsonArray("items")
      if (items == null) Nil
      else items.asScala.toList.flatMap { el =>
        val obj = el.getAsJsonObject
        val name = obj.getAsJsonObject("metadata").get("name").getAsString
        val ports = resourceType match {
          case KubeResourceType.Service =>
            val spec = obj.getAsJsonObject("spec")
            if (spec != null && spec.has("ports") && spec.get("ports").isJsonArray) {
              spec.getAsJsonArray("ports").asScala.flatMap { pEl =>
                val p = pEl.getAsJsonObject
                if (p.has("port") && p.get("port").isJsonPrimitive) Some(p.get("port").getAsInt) else None
              }.toList.distinct
            } else Nil

          case KubeResourceType.Pod =>
            val spec = obj.getAsJsonObject("spec")
            if (spec != null && spec.has("containers") && spec.get("containers").isJsonArray) {
              spec.getAsJsonArray("containers").asScala.flatMap { cEl =>
                val c = cEl.getAsJsonObject
                if (c.has("ports") && c.get("ports").isJsonArray) {
                  c.getAsJsonArray("ports").asScala.flatMap { pEl =>
                    val p = pEl.getAsJsonObject
                    if (p.has("containerPort") && p.get("containerPort").isJsonPrimitive) Some(p.get("containerPort").getAsInt)
                    else None
                  }
                } else Nil
              }.toList.distinct
            } else Nil
        }
        Some(KubeTargetCandidate(namespace, resourceType, name, ports))
      }
    } catch {
      case NonFatal(_) => Nil
    }
  }

  private def defaultRunCommand(args: List[String]): Either[String, String] = {
    try {
      val pb = new ProcessBuilder(args.asJava)
      pb.redirectErrorStream(true)
      val proc = pb.start()
      val out = new String(proc.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      val finished = proc.waitFor(10, TimeUnit.SECONDS)
      if (!finished) {
        proc.destroyForcibly()
        Left("kubectl command timed out after 10s")
      } else if (proc.exitValue() != 0) {
        Left(Option(out).map(_.trim).filter(_.nonEmpty).getOrElse(s"kubectl exited with code ${proc.exitValue()}"))
      } else {
        Right(out)
      }
    } catch {
      case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
    }
  }

  private def defaultStartProcess(args: List[String]): Process = {
    val pb = new ProcessBuilder(args.asJava)
    pb.redirectErrorStream(true)
    pb.start()
  }
}

class KubectlPortForwardAdapter(
  kubectlExecutable: String = "kubectl",
  runCommand: List[String] => Either[String, String] = KubectlPortForwardAdapter.defaultRunCommand,
  startProcess: List[String] => Process = KubectlPortForwardAdapter.defaultStartProcess
) extends KubePortForwardPort {
  import KubectlPortForwardAdapter.{parseForwardedLocalPort, parseTargetsJson}

  private case class RunningForward(info: ActivePortForward, process: Process)
  private val activeMap = new ConcurrentHashMap[String, RunningForward]()

  private def ctxArgs(context: Option[String]): List[String] = {
    context.map(_.trim).filter(_.nonEmpty).map(c => List("--context", c)).getOrElse(Nil)
  }

  override def listContexts(): Either[DomainError, List[String]] = {
    runCommand(List(kubectlExecutable, "config", "get-contexts", "-o", "name"))
      .left.map(err => DomainError.RpcExecutionFailed(err, -1, Some("kubectl")))
      .map(_.linesIterator.map(_.trim).filter(_.nonEmpty).toList)
  }

  override def listNamespaces(context: Option[String]): Either[DomainError, List[String]] = {
    val cmd = List(kubectlExecutable) ++ ctxArgs(context) ++ List("get", "namespaces", "-o", "jsonpath={.items[*].metadata.name}")
    runCommand(cmd)
      .left.map(err => DomainError.RpcExecutionFailed(err, -1, Some("kubectl")))
      .map(_.trim.split("\\s+").map(_.trim).filter(_.nonEmpty).toList)
  }

  override def listTargets(context: Option[String], namespace: String): Either[DomainError, List[KubeTargetCandidate]] = {
    val ns = if (namespace.trim.isEmpty) "default" else namespace.trim
    val svcCmd = List(kubectlExecutable) ++ ctxArgs(context) ++ List("get", "services", "-n", ns, "-o", "json")
    val podCmd = List(kubectlExecutable) ++ ctxArgs(context) ++ List("get", "pods", "-n", ns, "-o", "json")
    val svcs = runCommand(svcCmd).map(parseTargetsJson(_, ns, KubeResourceType.Service)).getOrElse(Nil)
    val pods = runCommand(podCmd).map(parseTargetsJson(_, ns, KubeResourceType.Pod)).getOrElse(Nil)
    val combined = (svcs ++ pods).filter(_.ports.nonEmpty)
    if (combined.nonEmpty || svcs.nonEmpty || pods.nonEmpty) Right(combined)
    else runCommand(svcCmd).left.map(e => DomainError.RpcExecutionFailed(e, -1, Some("kubectl"))).map(_ => Nil)
  }

  override def startForward(spec: KubeTargetSpec): Either[DomainError, ActivePortForward] = {
    val portMapping = if (spec.localPort > 0) s"${spec.localPort}:${spec.remotePort}" else s":${spec.remotePort}"
    val cmd = List(kubectlExecutable) ++ ctxArgs(spec.context) ++ List(
      "port-forward",
      "-n", spec.namespace,
      s"${spec.resourceType.cliPrefix}/${spec.resourceName}",
      portMapping
    )
    try {
      val proc = startProcess(cmd)
      val latch = new CountDownLatch(1)
      @volatile var resolvedPort: Option[Int] = None
      val outputLines = ListBuffer.empty[String]

      val readerThread = new Thread(() => {
        val reader = new BufferedReader(new InputStreamReader(proc.getInputStream, StandardCharsets.UTF_8))
        try {
          var line = reader.readLine()
          while (line != null) {
            outputLines.synchronized { outputLines += line }
            if (resolvedPort.isEmpty) {
              parseForwardedLocalPort(line).foreach { port =>
                resolvedPort = Some(port)
                latch.countDown()
              }
            }
            line = reader.readLine()
          }
        } catch {
          case NonFatal(_) => ()
        } finally {
          latch.countDown()
        }
      }, "protoform-kubectl-pf")
      readerThread.setDaemon(true)
      readerThread.start()

      latch.await(8, TimeUnit.SECONDS)
      resolvedPort match {
        case Some(localPort) if proc.isAlive =>
          val id = UUID.randomUUID().toString
          val info = ActivePortForward(id = id, spec = spec, localPort = localPort)
          activeMap.put(id, RunningForward(info, proc))
          Right(info)
        case _ =>
          val stderr = outputLines.synchronized(outputLines.mkString(" | "))
          proc.destroyForcibly()
          val detail = if (stderr.nonEmpty) stderr else "Timed out waiting for kubectl port-forward ready line."
          Left(DomainError.RpcExecutionFailed(detail, -1, Some("kubectl")))
      }
    } catch {
      case NonFatal(e) =>
        Left(DomainError.RpcExecutionFailed(Option(e.getMessage).getOrElse("Failed to start kubectl"), -1, Some("kubectl")))
    }
  }

  override def stopForward(id: String): Unit = {
    Option(activeMap.remove(id)).foreach { rf =>
      try {
        rf.process.destroy()
        if (!rf.process.waitFor(1, TimeUnit.SECONDS)) rf.process.destroyForcibly()
      } catch {
        case NonFatal(_) => ()
      }
    }
  }

  override def activeForwards: List[ActivePortForward] = {
    activeMap.asScala.collect {
      case (id, rf) if rf.process.isAlive => rf.info
      case (id, _) =>
        activeMap.remove(id)
        null
    }.filter(_ != null).toList.sortBy(_.startedAt)
  }

  override def stopAll(): Unit = {
    activeMap.keys().asScala.toList.foreach(stopForward)
  }
}
