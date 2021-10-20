// Copyright (c) 2017, 2021, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.steps;

import io.kubernetes.client.openapi.models.V1Job;
import oracle.kubernetes.operator.JobAwaiterStepFactory;
import oracle.kubernetes.operator.JobWatcher;
import oracle.kubernetes.operator.ProcessingConstants;
import oracle.kubernetes.operator.logging.LoggingFacade;
import oracle.kubernetes.operator.logging.LoggingFactory;
import oracle.kubernetes.operator.work.NextAction;
import oracle.kubernetes.operator.work.Packet;
import oracle.kubernetes.operator.work.Step;

public class WatchDomainIntrospectorJobReadyStep extends Step {
  private static final LoggingFacade LOGGER = LoggingFactory.getLogger("Operator", "Operator");

  public WatchDomainIntrospectorJobReadyStep(Step next) {
    super(next);
  }

  @Override
  public NextAction apply(Packet packet) {
    LOGGER.info("REG-> next linked step is " + getNext().getName());
    V1Job domainIntrospectorJob = (V1Job) packet.get(ProcessingConstants.DOMAIN_INTROSPECTOR_JOB);

    if (hasNotCompleted(domainIntrospectorJob)) {
      JobAwaiterStepFactory jw = packet.getSpi(JobAwaiterStepFactory.class);
      final Step step = jw.waitForReady(domainIntrospectorJob, getNext());
      LOGGER.info("REG-> not complete, so invoke " + step.getName());
      return doNext(step, packet);
    } else {
      LOGGER.info("REG-> already complete, proceed");
      return doNext(packet);
    }
  }

  private boolean hasNotCompleted(V1Job domainIntrospectorJob) {
    return domainIntrospectorJob != null && !JobWatcher.isComplete(domainIntrospectorJob);
  }
}
