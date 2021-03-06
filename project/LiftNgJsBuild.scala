import dispatch.Future
import sbt._
import sbt.Keys._
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

object LiftNgJsBuild extends Build {
  val pluginVersion = SettingKey[String]("pluginVersion", "Version of this plugin")
  val snapshot = SettingKey[Boolean]("snapshot", "True if this is a snapshot build, false otherwise")
  val ngVersion   = SettingKey[String]("ngVersion", "Full version number of the angular library")
  val liftVersion = SettingKey[String]("liftVersion", "Full version number of the Lift Web Framework")
  val liftEdition = SettingKey[String]("liftEdition", "Lift Edition (short version number to append to artifact name)")
  val baseUrl = SettingKey[String]("baseUrl", "The base URL for fetching angular code")
  val zipUrl  = SettingKey[String]("zipUrl", "The URL for the zip file containing the angular code")

  val fetch = TaskKey[Seq[File]]("fetch", "Fetch the angular modules for the configured version from https://code.angularjs.org")
  val releaseDocs = TaskKey[Seq[File]]("releaseDocs", "Updates the docs for release")

  val defaultNgVersion = ngVersion <<= version { v => if(v.endsWith("-SNAPSHOT")) v.substring(0, v.length-9) else v }

  val defaultVersion = version <<= (pluginVersion, snapshot, ngVersion) ( (p, s, ng) =>
    p + "_" + ng + (if(s) "-SNAPSHOT" else "")
  )

  val defaultZipUrl = zipUrl <<= (ngVersion, baseUrl) { (v, url) =>
    val base = if(url.endsWith("/")) url else url + "/"
    s"$base$v/angular-$v.zip"
  }

  val defaultFetch = fetch <<= (ngVersion, zipUrl, resourceManaged in Compile, streams) map { (ver, zip, rsrc, s) =>
    val log = s.log

    def fetchZip(zipUrl:String): Future[Array[Byte]] = {
      import dispatch._
      Http(url(zipUrl) OK as.Bytes)
    }

    def unpackZip(bytes:Future[Array[Byte]], rsrc:File):Future[Seq[File]] = {
      import java.io._, java.util.zip._

      def pipeStream(in:InputStream, out:OutputStream) = {
        val b = new Array[Byte](1024)
        Stream.continually(in.read(b, 0, 1024)).takeWhile(_ > 0).foreach( c => out.write(b, 0, c) )
      }

      val zipRoot = s"angular-$ver/"
      def dstFileName(e:ZipEntry) = {
        val withoutVersion = e.getName.substring(zipRoot.length)
        val split = withoutVersion.split('.')
        split.head + s"-$ver." + split.tail.mkString(".")
      }

      bytes.map { b =>
        val s = new ZipInputStream(new ByteArrayInputStream(b))
      
        Stream.continually(s.getNextEntry).takeWhile(_ != null).
          filter(!_.isDirectory).filter(_.getName.split('/').length == 2).
          map { e =>
            val fileName = dstFileName(e)
            val f = new File(rsrc, fileName)
            val out = new PrintStream(new FileOutputStream(f), false, "utf-8")
            val bytes = new ByteArrayOutputStream()

            log.debug("Creating file "+f.getAbsolutePath)
            f.createNewFile()
            pipeStream(s, bytes)

            val regex = """\Q//# sourceMappingURL=\E(?:\S*)\Q.min.js.map\E"""

            val srcMappingUpdated = bytes.toString("utf-8").replaceAll(regex, s"//# sourceMappingURL=$fileName.map")

            out.print(srcMappingUpdated)
            out.flush
            out.close
            f
          }

      }
    }

    log.info(s"Fetching $zip")
    val root = rsrc / "toserve" / "net" / "liftmodules" / "ng" / "js"
    root.mkdirs()
    val f = unpackZip(fetchZip(zip), root)
    Await.result(f, 60.seconds)

  }

  val defaultReleaseDocs = releaseDocs <<= (ngVersion, version, baseDirectory).map { (ngVer, ver, dir) =>
    val readmeFile = dir / "README.md"
    val heraldFile = dir / "notes" / s"$ver.markdown"
    val lsFile     = dir / "src" / "main" / "ls" / s"$ver.json"

    val readmeContents = IO.readLines(readmeFile).map {
      line =>
        if (line startsWith "  val ngVersion = ") s"""  val ngVersion = "$ngVer""""
        else if (line startsWith "## Published Angular Versions") s"## Published Angular Versions\n* $ngVer"
        else line
    }.mkString("\n")
    IO.write(readmeFile, readmeContents)

    val heraldContents = s"Packaging of @angularjs $ngVer for @liftweb #scala"
    IO.write(heraldFile, heraldContents)

    val lsContents = IO.readLines(lsFile).map { line =>
      if (line startsWith """  "name" : """) """  "name" : "lift-ng-js","""
      else line
    }.mkString("\n")
    IO.write(lsFile, lsContents)

    Seq(readmeFile, heraldFile, lsFile)
  }.dependsOn(ls.Plugin.LsKeys.writeVersion)

  val requireFetch = resourceGenerators in Compile <+= fetch

  lazy val project = Project(
    id = "lift-ng-js", 
    base = file("."),
    settings = Project.defaultSettings ++ Seq(
      defaultFetch,
      requireFetch,
      baseUrl := "https://code.angularjs.org/",
      defaultZipUrl,
      defaultReleaseDocs,
      defaultVersion
    )
  )
}
