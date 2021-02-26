// Copyright (c) 2018, 2021, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.weblogic.domain.model;

import oracle.kubernetes.operator.logging.LoggingFacade;
import oracle.kubernetes.operator.logging.LoggingFactory;

public class ManagedServerSpecCommonImpl extends ServerSpecCommonImpl {
  private static final LoggingFacade LOGGER = LoggingFactory.getLogger("Operator", "Operator");

  /**
   * Constructs an object to return the effective configuration for a managed server.
   *
   * @param spec the domain specification
   * @param server the server whose configuration is to be returned
   * @param cluster the cluster that this managed server belongs to
   * @param clusterLimit the number of servers desired for the cluster, or null if not a clustered
   *     server
   */
  public ManagedServerSpecCommonImpl(
      DomainSpec spec, Server server, Cluster cluster, Integer clusterLimit) {
    super(spec, server, cluster, clusterLimit);
  }

  @Override
  public boolean shouldStart(int currentReplicas) {
    LOGGER.info("XXX shouldStart?");
    if (isStartAdminServerOnly()) {
      return false;
    }
    LOGGER.info("XXX shouldStart? is not admin");
    return super.shouldStart(currentReplicas);
  }

  @Override
  public boolean alwaysStart() {
    if (isStartAdminServerOnly()) {
      return false;
    }
    return super.alwaysStart();
  }
}
