ThisBuild / scalaVersion := "3.3.7"
ThisBuild / version      := "1.0.0"
ThisBuild / organization := "net.protoform"

val javafxVersion   = "25.0.5"
val atlantafxVersion = "2.1.0"
val richtextfxVersion = "0.11.7"
val grpcVersion     = "1.64.0"
val protobufVersion = "3.25.3"
val gsonVersion     = "2.10.1"
val logbackVersion  = "1.5.6"
val typesafeConfigVersion = "1.4.3"
val scalatestVersion = "3.2.18"

val osNameRaw = System.getProperty("os.name").toLowerCase
val osArchRaw = System.getProperty("os.arch").toLowerCase
val isArm64 = osArchRaw.contains("aarch64") || osArchRaw.contains("arm64")

val osClassifier = osNameRaw match {
  case n if n.contains("win") => "win"
  case n if n.contains("mac") => if (isArm64) "mac-aarch64" else "mac"
  case _                      => "linux"
}

val universalFatJar = sys.props.get("protoform.universal").contains("true")
val javafxClassifiers =
  if (universalFatJar) Seq("win", "mac", "mac-aarch64", "linux")
  else Seq(osClassifier)

// JavaFX modules
val javafxModules = Seq("base", "controls", "fxml", "graphics")

lazy val root = (project in file("."))
  .settings(
    name := "protoform",
    
    // Dependencies
    libraryDependencies ++= Seq(
      // Modern AtlantaFX Theme, Vector Icons & Syntax-Highlighted Code Editor
      "io.github.mkpaz" % "atlantafx-base" % atlantafxVersion,
      "org.kordamp.ikonli" % "ikonli-javafx" % "12.3.1",
      "org.kordamp.ikonli" % "ikonli-feather-pack" % "12.3.1",
      "org.fxmisc.richtext" % "richtextfx" % richtextfxVersion,

      // gRPC Core & Shaded Netty
      "io.grpc" % "grpc-netty-shaded" % grpcVersion,
      "io.grpc" % "grpc-protobuf"     % grpcVersion,
      "io.grpc" % "grpc-stub"         % grpcVersion,
      "io.grpc" % "grpc-services"     % grpcVersion,

      // Protobuf Core & Utilities (JsonFormat, DynamicMessage)
      "com.google.protobuf" % "protobuf-java"      % protobufVersion,
      "com.google.protobuf" % "protobuf-java-util" % protobufVersion,
      "com.google.code.gson" % "gson"              % gsonVersion,

      // Configuration & Logging
      "com.typesafe"   % "config"          % typesafeConfigVersion,
      "ch.qos.logback" % "logback-classic" % logbackVersion,
      "org.slf4j"      % "slf4j-api"       % "2.0.13",
      "com.google.auth" % "google-auth-library-oauth2-http" % "1.24.0",

      // Testing
      "org.scalatest" %% "scalatest" % scalatestVersion % Test
    ) ++ javafxModules.flatMap { module =>
      ("org.openjfx" % s"javafx-$module" % javafxVersion) +:
        javafxClassifiers.map(cls => "org.openjfx" % s"javafx-$module" % javafxVersion classifier cls)
    },

    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-no-indent",
      "-Xmax-inlines", "128",
      "-Wconf:msg=(EXTENDED in object StageStyle|HeaderBar in package javafx\\.scene\\.layout) is deprecated:s"
    ),

    // Packaging: Standalone Fat JAR configuration
    Compile / run / fork := true,
    Compile / run / javaOptions ++= Seq("-Djavafx.enablePreview=true", "-Djavafx.suppressPreviewWarning=true"),
    Compile / mainClass := Some("net.protoform.Launcher"),
    assembly / mainClass := Some("net.protoform.Launcher"),
    assembly / assemblyJarName := "protoform-1.0.0.jar",
    assembly / assemblyMergeStrategy := {
      case "module-info.class" => MergeStrategy.discard
      case PathList("META-INF", "versions", _*) => MergeStrategy.first
      case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
      case PathList("META-INF", xs @ _*) if xs.exists(_.endsWith(".SF")) || xs.exists(_.endsWith(".DSA")) || xs.exists(_.endsWith(".RSA")) =>
        MergeStrategy.discard
      case PathList("META-INF", "services", _*) => MergeStrategy.filterDistinctLines
      case PathList("META-INF", "native-image", _*) => MergeStrategy.first
      case "reference.conf" | "application.conf" => MergeStrategy.concat
      case PathList(ps @ _*) if ps.last.endsWith(".proto") => MergeStrategy.first
      case x => MergeStrategy.first
    }
  )

lazy val demoServer = (project in file("demo-server"))
  .settings(
    name := "protoform-demo-server",
    libraryDependencies ++= Seq(
      "io.grpc"              % "grpc-netty-shaded"  % grpcVersion,
      "io.grpc"              % "grpc-protobuf"      % grpcVersion,
      "io.grpc"              % "grpc-stub"          % grpcVersion,
      "io.grpc"              % "grpc-services"      % grpcVersion,
      "com.google.protobuf"  % "protobuf-java"      % protobufVersion,
      "com.google.protobuf"  % "protobuf-java-util" % protobufVersion,
      "ch.qos.logback"       % "logback-classic"    % logbackVersion,
      "org.slf4j"            % "slf4j-api"          % "2.0.13",
      "org.scalatest"       %% "scalatest"          % scalatestVersion % Test
    ),
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-no-indent",
      "-Xmax-inlines", "128"
    ),
    Compile / mainClass := Some("net.protoform.demo.DemoServerMain"),
    assembly / mainClass := Some("net.protoform.demo.DemoServerMain"),
    assembly / assemblyJarName := "protoform-demo-server-1.0.0.jar",
    assembly / assemblyMergeStrategy := {
      case "module-info.class" => MergeStrategy.discard
      case PathList("META-INF", "versions", _*) => MergeStrategy.first
      case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
      case PathList("META-INF", xs @ _*) if xs.exists(_.endsWith(".SF")) || xs.exists(_.endsWith(".DSA")) || xs.exists(_.endsWith(".RSA")) =>
        MergeStrategy.discard
      case PathList("META-INF", "services", _*) => MergeStrategy.filterDistinctLines
      case PathList("META-INF", "native-image", _*) => MergeStrategy.first
      case "reference.conf" | "application.conf" => MergeStrategy.concat
      case PathList(ps @ _*) if ps.last.endsWith(".proto") => MergeStrategy.first
      case x => MergeStrategy.first
    }
  )
