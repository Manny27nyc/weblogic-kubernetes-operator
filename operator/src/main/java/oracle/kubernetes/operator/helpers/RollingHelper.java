// Copyright (c) 2018, 2021, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.helpers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import io.kubernetes.client.openapi.models.V1Pod;
import oracle.kubernetes.operator.DomainStatusUpdater;
import oracle.kubernetes.operator.ProcessingConstants;
import oracle.kubernetes.operator.logging.LoggingFacade;
import oracle.kubernetes.operator.logging.LoggingFactory;
import oracle.kubernetes.operator.logging.MessageKeys;
import oracle.kubernetes.operator.wlsconfig.WlsClusterConfig;
import oracle.kubernetes.operator.wlsconfig.WlsDomainConfig;
import oracle.kubernetes.operator.wlsconfig.WlsServerConfig;
import oracle.kubernetes.operator.work.NextAction;
import oracle.kubernetes.operator.work.Packet;
import oracle.kubernetes.operator.work.Step;
import oracle.kubernetes.operator.work.Step.StepAndPacket;
import oracle.kubernetes.utils.OperatorUtils;
import oracle.kubernetes.weblogic.domain.model.Domain;

import static oracle.kubernetes.operator.ProcessingConstants.DOMAIN_ROLL_START_EVENT_GENERATED;

/**
 * After the {@link PodHelper} identifies servers that are presently running, but that are using an
 * out-of-date specification, it defers the processing of these servers to the RollingHelper. This
 * class will ensure that a minimum number of cluster members remain up, if possible, throughout the
 * rolling process.
 */
public class RollingHelper {
  private static final LoggingFacade LOGGER = LoggingFactory.getLogger("Operator", "Operator");
  private static final long DELAY_IN_SECONDS = 1;

  private RollingHelper() {
  }

  /**
   * Creates an asynchronous step that completes the rolling. The rolling parameter is a map from
   * server name to a {@link StepAndPacket} that includes the asynchronous step and packet necessary
   * to roll that individual server. This will include first stopping (deleting) the existing Pod,
   * recreating the Pod with the updated specification, waiting for that new Pod to become Ready
   * and, finally, completing the server presence with necessary Service and Ingress objects, etc.
   *
   * @param rolling Map from server name to {@link Step} and {@link Packet} combination for rolling
   *     one server
   * @param next Next asynchronous step
   * @return Asynchronous step to complete rolling
   */
  public static Step rollServers(Map<String, StepAndPacket> rolling, Step next) {
    return new RollingStep(rolling, next);
  }

  private static List<String> getReadyServers(DomainPresenceInfo info) {
    return info.getSelectedActiveServerNames(RollingHelper::hasReadyServer);
  }

  private static boolean hasReadyServer(V1Pod pod) {
    return !PodHelper.isDeleting(pod) && PodHelper.getReadyStatus(pod);
  }

  private static class RollingStep extends Step {
    private final Map<String, StepAndPacket> rolling;

    private RollingStep(Map<String, StepAndPacket> rolling, Step next) {
      super(next);
      // sort the rolling map so servers would be restarted in order based on server names
      this.rolling = OperatorUtils.createSortedMap(rolling);
    }

    @Override
    protected String getDetail() {
      return String.join(",", rolling.keySet());
    }

    @Override
    public NextAction apply(Packet packet) {
      DomainPresenceInfo info = packet.getSpi(DomainPresenceInfo.class);

      Domain dom = info.getDomain();
      // These are presently Ready servers
      List<String> availableServers = getReadyServers(info);

      Collection<StepAndPacket> serversThatCanRestartNow = new ArrayList<>();
      Map<String, Queue<StepAndPacket>> clusteredRestarts = new HashMap<>();

      List<String> servers = new ArrayList<>();
      for (Map.Entry<String, StepAndPacket> entry : rolling.entrySet()) {
        // If this server isn't currently Ready, then it can be safely restarted now
        // regardless of the state of its cluster (if any)
        if (!availableServers.contains(entry.getKey())) {
          servers.add(entry.getKey());
          serversThatCanRestartNow.add(entry.getValue());
          continue;
        }

        // If this server isn't part of a cluster, then it can also be safely restarted now
        Packet p = entry.getValue().packet;
        String clusterName = (String) p.get(ProcessingConstants.CLUSTER_NAME);
        if (clusterName == null) {
          servers.add(entry.getKey());
          serversThatCanRestartNow.add(entry.getValue());
          continue;
        }

        // clustered server
        Queue<StepAndPacket> cr = clusteredRestarts.get(clusterName);
        if (cr == null) {
          cr = new ConcurrentLinkedQueue<>();
          clusteredRestarts.put(clusterName, cr);
        }
        cr.add(entry.getValue());
      }

      if (!servers.isEmpty()) {
        LOGGER.info(MessageKeys.CYCLING_SERVERS, dom.getDomainUid(), servers);
      }

      Collection<StepAndPacket> work = new ArrayList<>();
      if (!serversThatCanRestartNow.isEmpty()) {
        work.add(
            new StepAndPacket(
                new ServersThatCanRestartNowStep(serversThatCanRestartNow, null), packet));
      }

      if (!clusteredRestarts.isEmpty()) {
        for (Map.Entry<String, Queue<StepAndPacket>> entry : clusteredRestarts.entrySet()) {
          work.add(
              new StepAndPacket(
                  new RollSpecificClusterStep(entry.getKey(), entry.getValue(), null), packet));
        }
      }

      if (!work.isEmpty()) {
        return doForkJoin(createAfterRollStep(getNext()), packet, work);
      }

      return doNext(createAfterRollStep(getNext()), packet);
    }

    private Step createAfterRollStep(Step next) {
      return new AfterRollStep(next);
    }
  }

  private static class AfterRollStep extends Step {
    public AfterRollStep(Step next) {
      super(next);
    }

    @Override
    public NextAction apply(Packet packet) {
      return doNext(createDomainRollCompletedEventStepIfNeeded(
                        DomainStatusUpdater.createStatusUpdateStep(getNext()), packet),
            packet);
    }

  }

  /**
   * Create DOMAIN_ROLL_COMPLETED event if a roll started earlier.
   *
   * @param next next step
   * @param packet packet to use
   * @return step chain
   */
  public static Step createDomainRollCompletedEventStepIfNeeded(Step next, Packet packet) {
    if ("true".equals(packet.remove(DOMAIN_ROLL_START_EVENT_GENERATED))) {
      LOGGER.info(MessageKeys.DOMAIN_ROLL_COMPLETED, getDomainUid(packet));
      return Step.chain(
          EventHelper.createEventStep(new EventHelper.EventData(EventHelper.EventItem.DOMAIN_ROLL_COMPLETED)),
          next);
    }
    return next;
  }

  private static String getDomainUid(Packet packet) {
    return packet.getSpi(DomainPresenceInfo.class).getDomainUid();
  }

  private static class ServersThatCanRestartNowStep extends Step {
    private final Collection<StepAndPacket> serversThatCanRestartNow;

    public ServersThatCanRestartNowStep(
        Collection<StepAndPacket> serversThatCanRestartNow, Step next) {
      super(next);
      this.serversThatCanRestartNow = serversThatCanRestartNow;
    }

    @Override
    public NextAction apply(Packet packet) {
      return doForkJoin(getNext(), packet, serversThatCanRestartNow);
    }
  }

  private static class RollSpecificClusterStep extends Step {
    private final String clusterName;
    private final Queue<StepAndPacket> servers;

    public RollSpecificClusterStep(
        String clusterName, Queue<StepAndPacket> clusteredServerRestarts, Step next) {
      super(next);
      this.clusterName = clusterName;
      servers = clusteredServerRestarts;
    }

    @Override
    public String getDetail() {
      return clusterName;
    }

    @Override
    public NextAction apply(Packet packet) {
      DomainPresenceInfo info = packet.getSpi(DomainPresenceInfo.class);
      WlsDomainConfig config = (WlsDomainConfig) packet.get(ProcessingConstants.DOMAIN_TOPOLOGY);

      // Refresh as this is constantly changing
      Domain dom = info.getDomain();
      // These are presently Ready servers
      List<String> availableServers = getReadyServers(info);

      List<String> readyServers = new ArrayList<>();

      int countReady = 0;
      WlsClusterConfig cluster = config != null ? config.getClusterConfig(clusterName) : null;
      if (cluster != null) {
        List<WlsServerConfig> serversConfigs = cluster.getServerConfigs();
        if (serversConfigs != null) {
          for (WlsServerConfig s : serversConfigs) {
            // figure out how many servers are currently ready
            String name = s.getName();
            if (availableServers.contains(name)) {
              readyServers.add(s.getName());
              countReady++;
            }
          }
        }
      }

      LOGGER.info(MessageKeys.ROLLING_SERVERS, dom.getDomainUid(), servers, readyServers);

      int countToRestartNow = countReady - dom.getMinAvailable(clusterName);
      Collection<StepAndPacket> restarts = new ArrayList<>();
      for (int i = 0; i < countToRestartNow; i++) {
        Optional.ofNullable(servers.poll())
            .ifPresent(restarts::add);
      }

      if (!restarts.isEmpty()) {
        return doForkJoin(this, packet, restarts);
      } else if (!servers.isEmpty()) {
        return doDelay(this, packet, DELAY_IN_SECONDS, TimeUnit.SECONDS);
      } else {
        return doNext(packet);
      }
    }
  }
}
