package io.cloudsmith.sbt

import io.cloudsmith.credentials.{CloudsmithCredentials, Credential, Warnings}
import java.net.URI
import java.util.Locale
import sbt._

/**
 * Authenticates sbt to Cloudsmith with the credential of the `cloudsmith` CLI.
 *
 * For each Maven repository in `fullResolvers` and `publishTo` on a host that the CLI lists, it
 * adds the CLI credential. It adds nothing for a host that already has credentials.
 *
 * It extends the tasks that read the final credentials, not the `credentials` setting, because
 * the settings of a plugin apply before those of the build.
 */
object CloudsmithPlugin extends AutoPlugin {
  override def trigger = allRequirements
  override def requires = plugins.JvmPlugin
  override def projectSettings = Compat.settings

  private lazy val cli = CloudsmithCredentials.fromEnvironment()

  /** Returns the CLI credential for each repository whose host is not in `hostsWithCredentials`. */
  private[sbt] def cliCredentials(
      repositories: Seq[Resolver],
      hostsWithCredentials: Set[String],
      log: Logger
  ): Seq[Credentials] =
    for {
      url <- repositories.collect { case maven: MavenRepository => maven.root }.distinct
      credential <- Option(Warnings.to[Credential](log.warn(_), () => cli.credentialFor(url))).toSeq
      host = new URI(url).getHost.toLowerCase(Locale.ROOT)
      if !hostsWithCredentials(host)
    } yield {
      log.debug(s"Using the cloudsmith CLI credential for $url")
      // Cloudsmith names a different realm for each repository, and for uploads. A credential
      // without a realm matches all of them.
      Credentials(null, host, credential.username, credential.password)
    }
}
