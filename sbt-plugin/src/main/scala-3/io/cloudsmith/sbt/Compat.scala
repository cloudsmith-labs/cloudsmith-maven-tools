package io.cloudsmith.sbt

import java.io.{File, FileInputStream}
import java.util.Properties
import sbt.*
import sbt.Keys.*
import sbt.internal.librarymanagement.ivy.IvyCredentials
import sbt.librarymanagement.Credentials
import scala.util.Using

/** The settings of the plugin, in the APIs of sbt 2. */
private[sbt] object Compat {
  val settings: Seq[Def.Setting[?]] = Seq(
    // Coursier resolves with allCredentials. Uncached, so the credential stays off disk.
    allCredentials := Def.uncached {
      val existing = allCredentials.value
      existing ++ CloudsmithPlugin.cliCredentials(
        fullResolvers.value ++ publishTo.value,
        existing.flatMap(host).toSet,
        streams.value.log
      )
    },
    // Ivy publishes with the credentials that ivySbt registers.
    ivySbt := Def.uncached {
      val ivy = ivySbt.value
      val log = streams.value.log
      val hosts = credentials.value.flatMap(host).toSet
      IvyCredentials.register(CloudsmithPlugin.cliCredentials(publishTo.value.toSeq, hosts, log), log)
      ivy
    }
  )

  private def host(credentials: Credentials): Option[String] =
    credentials match {
      case direct: Credentials.DirectCredentials => Some(direct.host)
      case file: Credentials.FileCredentials => hostInFile(file.path)
    }

  /** sbt reads the host of a credentials file from its `host` property. */
  private def hostInFile(path: File): Option[String] =
    if (!path.isFile) None
    else
      Using(new FileInputStream(path)) { stream =>
        val properties = new Properties()
        properties.load(stream)
        Option(properties.getProperty("host"))
      }.toOption.flatten
}
