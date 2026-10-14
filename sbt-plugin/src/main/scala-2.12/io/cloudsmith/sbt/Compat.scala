package io.cloudsmith.sbt

import sbt._
import sbt.Keys._

/** The settings of the plugin, in the APIs of sbt 1. */
private[sbt] object Compat {
  val settings: Seq[Def.Setting[_]] = Seq(
    // Coursier resolves with allCredentials.
    allCredentials := {
      val existing = allCredentials.value
      val hosts = Credentials.allDirect(existing).map(_.host).toSet
      existing ++ CloudsmithPlugin.cliCredentials(fullResolvers.value ++ publishTo.value, hosts, streams.value.log)
    },
    // Ivy publishes with the credentials that ivySbt registers.
    ivySbt := {
      val ivy = ivySbt.value
      val log = streams.value.log
      val hosts = Credentials.allDirect(credentials.value).map(_.host).toSet
      Credentials.register(CloudsmithPlugin.cliCredentials(publishTo.value.toSeq, hosts, log), log)
      ivy
    }
  )
}
