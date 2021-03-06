lazy val gpdvizVersion = getVersion
val scalaV        = "2.12.4"
val akkaHttpV     = "10.0.10"
val akkaHttpCorsV = "0.2.2"
val cfgV          = "0.0.7"
val scalatestV    = "3.0.3"
val esriV         = "1.2.1"
val pusherV       = "1.0.0"
val autowireV     = "0.2.6"
val upickleV      = "0.4.4"
val pprintV       = "0.5.2"
val swaggerAkkaV  = "0.11.0"
val postgresV     = "42.1.4"
val slickV        = "3.2.1"
val slickPgV      = "0.15.4"
val scalaLoggingV = "3.7.2"
val logbackV      = "1.2.3"

val scalaJsDomV      = "0.9.3"
val bindingV         = "10.0.2"
val macrosParadiseV  = "2.1.0"
val momentScalaJsV   = "0.9.0"


def commonSettings = Seq(
  name := "gpdviz",
  version := gpdvizVersion,
  scalaVersion := scalaV,
  scalaJSUseMainModuleInitializer := true,
  libraryDependencies ++= Seq(
    "com.lihaoyi"    %%%   "autowire"   % autowireV,
    "com.lihaoyi"    %%%   "upickle"    % upickleV,
    "com.lihaoyi"    %%%   "pprint"     % pprintV
  ),
  scalacOptions ++= Seq("-deprecation", "-feature", "-encoding", "utf8"
    //, "-Ywarn-dead-code"
    //, "-unchecked",
    //, "-Xlint"
    //, "-Ywarn-unused-import"
  )
)

lazy val root = project.in(file("."))
  .aggregate(gpdvizJS, gpdvizJVM)
  .settings(
    publish := {},
    publishLocal := {}
  )

//lazy val gpdviz = crossProject(JVMPlatform, JSPlatform) // Use this with ScalaJs 1.x
lazy val gpdviz = crossProject
  .in(file("."))
  .settings(commonSettings: _*)
  .jvmSettings(
    libraryDependencies ++= Seq(
      "org.scala-js"         %% "scalajs-stubs"        % scalaJSVersion % "provided",
      "com.typesafe.akka"    %% "akka-http"            % akkaHttpV,
      "com.typesafe.akka"    %% "akka-http-spray-json" % akkaHttpV,
      "com.typesafe.akka"    %% "akka-http-testkit"    % akkaHttpV,
      "ch.megard"            %% "akka-http-cors"       % akkaHttpCorsV,
      "org.scalatest"        %% "scalatest"            % scalatestV % "test",
      "com.github.carueda"   %% "cfg"                  % cfgV % "provided",
      "com.esri.geometry"     % "esri-geometry-api"    % esriV,
      "com.pusher"            % "pusher-http-java"     % pusherV

      ,"org.postgresql"       % "postgresql"           % postgresV

      ,"com.typesafe.slick"  %% "slick"                % slickV
      ,"com.typesafe.slick"  %% "slick-hikaricp"       % slickV
      ,"com.github.tminglei" %% "slick-pg"             % slickPgV
      ,"com.github.tminglei" %% "slick-pg_spray-json"  % slickPgV

      ,"com.github.swagger-akka-http" %% "swagger-akka-http" % swaggerAkkaV

      ,"com.typesafe.scala-logging" %% "scala-logging" % scalaLoggingV
      ,"ch.qos.logback"      % "logback-classic"       % logbackV
    ),
    addCompilerPlugin(
      ("org.scalameta" % "paradise" % "3.0.0-M10").cross(CrossVersion.full)
    ),
    mainClass in assembly := Some("gpdviz.Gpdviz"),
    assemblyJarName in assembly := s"gpdviz-$gpdvizVersion.jar"
  )
  .jsSettings(
    libraryDependencies ++= Seq(
      "org.scala-js"              %%%  "scalajs-dom"        %  scalaJsDomV,
      "com.thoughtworks.binding"  %%%  "dom"                %  bindingV,
      "ru.pavkin"                 %%%  "scala-js-momentjs"  %  momentScalaJsV
    ),
    addCompilerPlugin("org.scalamacros" % "paradise" % macrosParadiseV cross CrossVersion.full),
    mainClass in Compile := Some("gpdviz.webapp.Frontend"),
    jsDependencies ++= Seq(
      "org.webjars"       %  "momentjs"     %  "2.18.1"  / "moment.js"      minified "moment.min.js",
      "org.webjars"       %  "lodash"       %  "4.17.4"  / "lodash.js"      minified "lodash.min.js",
      "org.webjars"       %  "jquery"       %  "3.2.1"   / "jquery.js"      minified "jquery.min.js",
      "org.webjars"       %  "leaflet"      %  "1.0.0"   / "leaflet.js",
      "org.webjars"       %  "esri-leaflet" %  "2.0.7"   / "esri-leaflet.js" dependsOn "leaflet.js",
      "org.webjars"       %  "highstock"    %  "6.0.2"   / "6.0.2/highstock.js"
    )
  )

lazy val gpdvizJS  = gpdviz.js
lazy val gpdvizJVM = gpdviz.jvm.settings(
  (resources in Compile) += (fastOptJS in (gpdvizJS, Compile)).value.data
)

// Puts some js resources under jvm's classpath so they can be resolved.
// Execute 'package' to trigger this.
resourceGenerators in Compile += Def.task {
  val parentDir = (fastOptJS in Compile in gpdvizJS).value.data.getParentFile

  def copy(name: String): File = {
    val sourceFile = parentDir / name
    require (sourceFile.exists())
    val destFile = (classDirectory in Compile in gpdvizJVM).value / sourceFile.name
    println(s"::: copying $sourceFile --> $destFile")
    IO.copyFile(sourceFile, destFile)
    destFile
  }
  Seq(
    copy("gpdviz-fastopt.js.map"),
    copy("gpdviz-jsdeps.js")
  )
}.taskValue

def getVersion: String = {
  val version = {
    val refFile = file("jvm/src/main/resources/reference.conf")
    val versionRe = """gpdviz\.version\s*=\s*(.+)""".r
    IO.read(refFile).trim match {
      case versionRe(v) ⇒ v
      case _ ⇒ sys.error(s"could not parse gpdviz.version from $refFile")
    }
  }
  println(s"gpdviz.version = $version")
  val indexFile = file("jvm/src/main/resources/web/index.html")
  val contents = IO.readLines(indexFile).mkString("\n")
  val updated = contents.replaceAll("<!--v-->[^<]*<!--v-->", s"<!--v-->$version<!--v-->")
  IO.write(indexFile, updated)
  version
}
