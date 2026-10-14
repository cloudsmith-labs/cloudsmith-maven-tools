package io.cloudsmith.maven;

import io.cloudsmith.credentials.CloudsmithCredentials;
import io.cloudsmith.credentials.Credential;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.spi.connector.transport.Transporter;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.spi.connector.transport.TransporterProvider;
import org.eclipse.aether.transfer.NoTransporterException;
import org.eclipse.aether.util.repository.AuthenticationBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Authenticates Maven to Cloudsmith with the credential of the {@code cloudsmith} CLI.
 *
 * <p>Maven asks each transporter factory in priority order to open a repository. This factory goes
 * first. For an HTTPS repository on a host that the CLI lists, it attaches the CLI credential and
 * hands the repository to Maven's own transport. For every other repository it throws {@link
 * NoTransporterException}, and Maven moves on to the next factory as if this one were absent.
 */
@Named("cloudsmith")
@Singleton
public final class CloudsmithTransporterFactory implements TransporterFactory {
  private static final Logger LOGGER = LoggerFactory.getLogger(CloudsmithTransporterFactory.class);

  private final Provider<TransporterProvider> transporterProvider;
  private final CloudsmithCredentials credentials;

  @Inject
  public CloudsmithTransporterFactory(Provider<TransporterProvider> transporterProvider) {
    this(transporterProvider, CloudsmithCredentials.fromEnvironment());
  }

  CloudsmithTransporterFactory(
      Provider<TransporterProvider> transporterProvider, CloudsmithCredentials credentials) {
    this.transporterProvider = transporterProvider;
    this.credentials = credentials;
  }

  @Override
  public Transporter newInstance(RepositorySystemSession session, RemoteRepository repository)
      throws NoTransporterException {
    // A credential from settings.xml wins. This check also ends the delegation below: the provider
    // offers the authenticated copy to this factory again, and this factory then steps aside.
    if (repository.getAuthentication() != null) {
      throw new NoTransporterException(repository, "the repository already has credentials");
    }
    Credential credential = credentials.credentialFor(repository.getUrl());
    if (credential == null) {
      throw new NoTransporterException(repository, "no cloudsmith CLI credential for this URL");
    }

    LOGGER.debug("Using the cloudsmith CLI credential for {}", repository.getUrl());
    var authentication =
        new AuthenticationBuilder()
            .addUsername(credential.username())
            .addPassword(credential.password())
            .build();
    var authenticated =
        new RemoteRepository.Builder(repository).setAuthentication(authentication).build();
    return transporterProvider.get().newTransporter(session, authenticated);
  }

  /** Higher than the priorities of the transports that Maven ships, so this factory goes first. */
  @Override
  public float getPriority() {
    return 100.0f;
  }
}
