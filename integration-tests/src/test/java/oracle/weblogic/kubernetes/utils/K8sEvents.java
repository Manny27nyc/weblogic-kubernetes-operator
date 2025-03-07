// Copyright (c) 2020, 2021, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes.utils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.CoreV1Event;
import io.kubernetes.client.util.Yaml;
import oracle.weblogic.kubernetes.TestConstants;
import oracle.weblogic.kubernetes.actions.TestActions;
import oracle.weblogic.kubernetes.actions.impl.primitive.Kubernetes;
import oracle.weblogic.kubernetes.logging.LoggingFacade;
import org.awaitility.core.ConditionFactory;

import static oracle.weblogic.kubernetes.TestConstants.OPERATOR_RELEASE_NAME;
import static oracle.weblogic.kubernetes.actions.TestActions.getOperatorPodName;
import static oracle.weblogic.kubernetes.actions.TestActions.getPodLog;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.testUntil;
import static oracle.weblogic.kubernetes.utils.ThreadSafeLogger.getLogger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Helper class for Kubernetes Events checking.
 */
public class K8sEvents {

  private static final LoggingFacade logger = getLogger();

  /**
   * Utility method to check event.
   *
   * @param opNamespace Operator namespace
   * @param domainNamespace Domain namespace
   * @param domainUid domainUid
   * @param reason EventName
   * @param type Type of the event
   * @param timestamp event timestamp
   * @param withStandardRetryPolicy conditionfactory object
   */
  public static void checkEvent(
      String opNamespace, String domainNamespace, String domainUid,
      String reason, String type, OffsetDateTime timestamp, ConditionFactory withStandardRetryPolicy) {
    testUntil(
        withStandardRetryPolicy,
        checkDomainEvent(opNamespace, domainNamespace, domainUid, reason, type, timestamp),
        logger,
        "domain event {0} to be logged in namespace {1}",
        reason,
        domainNamespace);
  }

  /**
   * Wait until a given event is logged by the operator.
   *
   * @param opNamespace namespace in which the operator is running
   * @param domainNamespace namespace in which the domain exists
   * @param domainUid UID of the domain
   * @param reason event to check for Created, Changed, deleted, processing etc
   * @param type type of event, Normal of Warning
   * @param timestamp the timestamp after which to see events
   */
  public static void checkEvent(
      String opNamespace, String domainNamespace, String domainUid,
      String reason, String type, OffsetDateTime timestamp) {
    testUntil(
        checkDomainEvent(opNamespace, domainNamespace, domainUid, reason, type, timestamp),
        logger,
        "domain event {0} to be logged in namespace {1}",
        reason,
        domainNamespace);
  }

  /**
   * Check if a given event is logged by the operator.
   *
   * @param opNamespace namespace in which the operator is running
   * @param domainNamespace namespace in which the domain exists
   * @param domainUid UID of the domain
   * @param reason event to check for Created, Changed, deleted, processing etc
   * @param type type of event, Normal of Warning
   * @param timestamp the timestamp after which to see events
   */
  public static Callable<Boolean> checkDomainEvent(
      String opNamespace, String domainNamespace, String domainUid, String reason,
      String type, OffsetDateTime timestamp) {
    return () -> {
      return domainEventExists(opNamespace, domainNamespace, domainUid, reason, type, timestamp);
    };
  }

  /**
   * Check if NamespaceWatchingStopped event is logged by the operator.
   *
   * @param opNamespace namespace in which the operator is running
   * @param domainNamespace namespace in which the domain exists
   * @param domainUid UID of the domain
   * @param type type of event, Normal of Warning
   * @param timestamp the timestamp after which to see events
   * @param enableClusterRoleBinding whether the enableClusterRoleBinding is set to true of false
   */
  public static Callable<Boolean> checkDomainEventWatchingStopped(
      String opNamespace, String domainNamespace, String domainUid,
      String type, OffsetDateTime timestamp, boolean enableClusterRoleBinding) {
    return () -> {
      if (enableClusterRoleBinding) {
        if (domainEventExists(opNamespace, domainNamespace, domainUid, NAMESPACE_WATCHING_STOPPED, type, timestamp)
            && domainEventExists(opNamespace, opNamespace, null, STOP_MANAGING_NAMESPACE, type, timestamp)) {
          logger.info("Got event {0} in namespace {1} and event {2} in namespace {3}",
              NAMESPACE_WATCHING_STOPPED, domainNamespace, STOP_MANAGING_NAMESPACE, opNamespace);
          return true;
        } else {
          logger.info("Did not get the {0} event in namespace {1} or {2} event in namespace {3}",
              NAMESPACE_WATCHING_STOPPED, domainNamespace, STOP_MANAGING_NAMESPACE, opNamespace);
        }
      } else {
        if (domainEventExists(opNamespace, domainNamespace, domainUid, NAMESPACE_WATCHING_STOPPED, type, timestamp)
            && domainEventExists(opNamespace, opNamespace, null, STOP_MANAGING_NAMESPACE, type, timestamp)) {
          logger.info("Got event {0} in namespace {1} and event {2} in namespace {3}",
              NAMESPACE_WATCHING_STOPPED, domainNamespace, STOP_MANAGING_NAMESPACE, opNamespace);
          return true;
        } else {
          logger.info("Did not get the {0} event in namespace {1} or {2} event in namespace {3}",
              NAMESPACE_WATCHING_STOPPED, domainNamespace, STOP_MANAGING_NAMESPACE, opNamespace);

          // check if there is a warning message in operator's log
          String operatorPodName = getOperatorPodName(OPERATOR_RELEASE_NAME, opNamespace);
          String expectedErrorMsg = String.format(
              "Cannot create or replace NamespaceWatchingStopped event in namespace %s due to an authorization error",
              domainNamespace);
          String operatorLog = getPodLog(operatorPodName, opNamespace);
          if (operatorLog.contains(expectedErrorMsg)
              && domainEventExists(opNamespace, opNamespace, null, STOP_MANAGING_NAMESPACE, type, timestamp)) {
            logger.info("Got expected error msg \"{0}\" in operator log and event {1} is logged in namespace {2}",
                expectedErrorMsg, STOP_MANAGING_NAMESPACE, opNamespace);
            return true;
          } else {
            logger.info("Did not get the expected error msg {0} in operator log", expectedErrorMsg);
            logger.info("Operator log: {0}", operatorLog);
          }
        }
      }
      return false;
    };
  }

  /**
   * Check if a given event is logged by the operator in the given namespace.
   *
   * @param opNamespace namespace in which the operator is running
   * @param domainNamespace namespace in which the event is logged
   * @param domainUid UID of the domain
   * @param reason event to check for Created, Changed, deleted, processing etc
   * @param type type of event, Normal or Warning
   * @param timestamp the timestamp after which to see events
   */
  public static boolean domainEventExists(
      String opNamespace, String domainNamespace, String domainUid, String reason,
      String type, OffsetDateTime timestamp) {

    try {
      List<CoreV1Event> events = Kubernetes.listNamespacedEvents(domainNamespace);
      for (CoreV1Event event : events) {
        if (reason.equals(event.getReason()) && (isEqualOrAfter(timestamp, event))) {
          logger.info(Yaml.dump(event));
          verifyOperatorDetails(event, opNamespace, domainUid);
          //verify type
          logger.info("Verifying domain event type {0}", type);
          assertEquals(event.getType(), type);
          return true;
        }
      }
    } catch (ApiException ex) {
      logger.log(Level.SEVERE, null, ex);
    }
    return false;
  }

  /**
   * Get matching event object.
   * @param opNamespace namespace in which the operator is running
   * @param domainNamespace namespace in which the event is logged
   * @param domainUid UID of the domain
   * @param reason event to check for Created, Changed, deleted, processing etc
   * @param type type of event, Normal or Warning
   * @param timestamp the timestamp after which to see events
   * @return CoreV1Event matching event object
   */
  public static CoreV1Event getEvent(String opNamespace, String domainNamespace, String domainUid, String reason,
      String type, OffsetDateTime timestamp) {

    try {
      List<CoreV1Event> events = Kubernetes.listNamespacedEvents(domainNamespace);
      for (CoreV1Event event : events) {
        if (event.getReason().equals(reason) && (isEqualOrAfter(timestamp, event))) {
          logger.info(Yaml.dump(event));
          if (event.getType().equals(type)) {
            return event;
          }
        }
      }
    } catch (ApiException ex) {
      Logger.getLogger(K8sEvents.class.getName()).log(Level.SEVERE, null, ex);
    }
    return null;
  }

  /**
   * Check if a given event is logged by the operator.
   *
   * @param opNamespace namespace in which the operator is running
   * @param domainNamespace namespace in which the domain exists
   * @param domainUid UID of the domain
   * @param reason event to check for Created, Changed, deleted, processing etc
   * @param type type of event, Normal of Warning
   * @param timestamp the timestamp after which to see events
   * @param countBefore the count to check against
   */
  public static Callable<Boolean> checkDomainEventWithCount(
      String opNamespace, String domainNamespace, String domainUid, String reason,
      String type, OffsetDateTime timestamp, int countBefore) {
    return () -> {
      logger.info("Verifying {0} event is logged by the operator in domain namespace {1}", reason, domainNamespace);
      try {
        List<CoreV1Event> events = Kubernetes.listNamespacedEvents(domainNamespace);
        for (CoreV1Event event : events) {
          if (event.getReason().equals(reason) && (isEqualOrAfter(timestamp, event))) {
            logger.info(Yaml.dump(event));
            verifyOperatorDetails(event, opNamespace, domainUid);
            //verify type
            logger.info("Verifying domain event type {0}", type);
            assertTrue(event.getType().equals(type));
            int countAfter = getDomainEventCount(domainNamespace, domainUid, reason, "Normal");
            return (countAfter == countBefore + 1);
          }
        }
      } catch (ApiException ex) {
        Logger.getLogger(K8sEvents.class.getName()).log(Level.SEVERE, null, ex);
      }
      return false;
    };
  }

  /**
   * Get the count for a particular event with specified reason, type and domainUid in a given namespace.
   *
   * @param domainNamespace namespace in which the domain exists
   * @param domainUid       UID of the domain
   * @param reason          event reason to get the count for
   * @param type            type of event, Normal of Warning
   * @return count          Event count
   */
  public static int getDomainEventCount(
          String domainNamespace, String domainUid, String reason, String type) {
    try {
      List<CoreV1Event> events = Kubernetes.listNamespacedEvents(domainNamespace);
      for (CoreV1Event event : events) {
        Map<String, String> labels = event.getMetadata().getLabels();
        if (event.getReason().equals(reason)
                && event.getType().equals(type)
                && labels.containsKey("weblogic.createdByOperator")
                && labels.get("weblogic.domainUID").equals(domainUid)) {
          return event.getCount();
        }
      }
    } catch (ApiException ex) {
      Logger.getLogger(K8sEvents.class.getName()).log(Level.SEVERE, null, ex);
    }
    return 0;
  }

  /**
   * Get the event count between a specific timestamp.
   *
   * @param domainNamespace namespace in which the domain exists
   * @param domainUid UID of the domain
   * @param reason event to check for Created, Changed, deleted, processing etc
   * @param timestamp the timestamp after which to see events
   * @return count number of events count
   */
  public static int getEventCount(
      String domainNamespace, String domainUid, String reason, OffsetDateTime timestamp) {
    int count = 0;
    try {
      List<CoreV1Event> events = Kubernetes.listNamespacedEvents(domainNamespace);
      for (CoreV1Event event : events) {
        if (event.getReason().contains(reason)
            && (isEqualOrAfter(timestamp, event))) {
          logger.info(Yaml.dump(event));
          count++;
        }
      }
    } catch (ApiException ex) {
      Logger.getLogger(K8sEvents.class.getName()).log(Level.SEVERE, null, ex);
      return -1;
    }
    return count;
  }

  /**
   * Check if a given event is logged only once for the given pod.
   *
   * @param domainNamespace namespace in which the domain exists
   * @param serverName server pod name for which event is checked
   * @param reason event to check for Started, Killing etc
   * @param timestamp the timestamp after which to see events
   */
  public static Callable<Boolean> checkPodEventLoggedOnce(
          String domainNamespace, String serverName, String reason, OffsetDateTime timestamp) {
    return () -> {
      logger.info("Verifying {0} event is logged for {1} pod in the domain namespace {2}",
              reason, serverName, domainNamespace);
      try {
        return isEventLoggedOnce(serverName, Kubernetes.listNamespacedEvents(domainNamespace).stream()
                .filter(e -> e.getInvolvedObject().getName().equals(serverName))
                .filter(e -> e.getReason().contains(reason))
                .filter(e -> isEqualOrAfter(timestamp, e)).collect(Collectors.toList()).size());
      } catch (ApiException ex) {
        Logger.getLogger(K8sEvents.class.getName()).log(Level.SEVERE, null, ex);
      }
      return false;
    };
  }

  /**
   * Check the domain event contains the expected error msg.
   *
   * @param opNamespace namespace in which the operator is running
   * @param domainNamespace namespace in which the domain exists
   * @param domainUid UID of the domain
   * @param reason event to check for Created, Changed, deleted, processing etc
   * @param type type of event, Normal of Warning
   * @param timestamp the timestamp after which to see events
   * @param expectedMsg the expected message in the domain event message
   */
  public static void checkDomainEventContainsExpectedMsg(String opNamespace,
                                                         String domainNamespace,
                                                         String domainUid,
                                                         String reason,
                                                         String type,
                                                         OffsetDateTime timestamp,
                                                         String expectedMsg) {
    checkEvent(opNamespace, domainNamespace, domainUid, reason, type, timestamp);
    CoreV1Event event =
        getEvent(opNamespace, domainNamespace, domainUid, reason, type, timestamp);
    if (event != null && event.getMessage() != null) {
      assertTrue(event.getMessage().contains(expectedMsg),
          String.format("The event message does not contain the expected msg %s", expectedMsg));
    } else {
      fail("event is null or event message is null");
    }
  }

  private static boolean isEqualOrAfter(OffsetDateTime timestamp, CoreV1Event event) {
    return event.getLastTimestamp().isEqual(timestamp)
            || event.getLastTimestamp().isAfter(timestamp);
  }

  private static Boolean isEventLoggedOnce(String serverName, int count) {
    return count == 1 ? true : logErrorAndFail(serverName, count);
  }

  private static Boolean logErrorAndFail(String serverName, int count) {
    Logger.getLogger(K8sEvents.class.getName()).log(Level.SEVERE,
            "Pod " + serverName + " restarted " + count + " times");
    return false;
  }

  // Verify the operator instance details are correct
  private static void verifyOperatorDetails(
      CoreV1Event event, String opNamespace, String domainUid) throws ApiException {
    logger.info("Verifying operator details");
    String operatorPodName = TestActions.getOperatorPodName(OPERATOR_RELEASE_NAME, opNamespace);
    //verify DOMAIN_API_VERSION
    if (domainUid != null) {
      assertTrue(event.getInvolvedObject().getApiVersion().equals(TestConstants.DOMAIN_API_VERSION),
          "Expected " + TestConstants.DOMAIN_API_VERSION + " ,Got " + event.getInvolvedObject().getApiVersion());
    }
    //verify reporting component to be operator release
    assertTrue(event.getReportingComponent().equals("weblogic.operator"),
        "Didn't get reporting component as " + "weblogic.operator");
    //verify reporting instance to be operator instance
    assertTrue(event.getReportingInstance().equals(operatorPodName),
        "Didn't get reporting instance as " + operatorPodName);
    //verify the event was created by operator
    Map<String, String> labels = event.getMetadata().getLabels();
    assertTrue(labels.containsKey("weblogic.createdByOperator")
        && labels.get("weblogic.createdByOperator").equals("true"));
    //verify the domainUID matches
    if (domainUid != null) {
      assertTrue(labels.containsKey("weblogic.domainUID")
          && labels.get("weblogic.domainUID").equals(domainUid));
    }
  }

  public static final String DOMAIN_AVAILABLE = "DomainAvailable";
  public static final String DOMAIN_CREATED = "DomainCreated";
  public static final String DOMAIN_DELETED = "DomainDeleted";
  public static final String DOMAIN_CHANGED = "DomainChanged";
  public static final String DOMAIN_COMPLETED = "DomainCompleted";
  public static final String DOMAIN_PROCESSING_FAILED = "DomainProcessingFailed";
  public static final String DOMAIN_PROCESSING_ABORTED = "DomainProcessingAborted";
  public static final String DOMAIN_ROLL_STARTING = "DomainRollStarting";
  public static final String DOMAIN_ROLL_COMPLETED = "DomainRollCompleted";
  public static final String DOMAIN_VALIDATION_ERROR = "DomainValidationError";
  public static final String NAMESPACE_WATCHING_STARTED = "NamespaceWatchingStarted";
  public static final String NAMESPACE_WATCHING_STOPPED = "NamespaceWatchingStopped";
  public static final String STOP_MANAGING_NAMESPACE = "StopManagingNamespace";
  public static final String POD_TERMINATED = "Killing";
  public static final String POD_STARTED = "Started";
  public static final String POD_CYCLE_STARTING = "PodCycleStarting";

}
