// Copyright (c) 2018, 2021, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.calls;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import javax.annotation.Nonnull;

import io.kubernetes.client.common.KubernetesListObject;
import io.kubernetes.client.openapi.ApiCallback;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1ListMeta;
import oracle.kubernetes.operator.helpers.DomainPresenceInfo;
import oracle.kubernetes.operator.helpers.Pool;
import oracle.kubernetes.operator.helpers.ResponseStep;
import oracle.kubernetes.operator.logging.LoggingContext;
import oracle.kubernetes.operator.logging.LoggingFacade;
import oracle.kubernetes.operator.logging.LoggingFactory;
import oracle.kubernetes.operator.logging.MessageKeys;
import oracle.kubernetes.operator.work.AsyncFiber;
import oracle.kubernetes.operator.work.Component;
import oracle.kubernetes.operator.work.NextAction;
import oracle.kubernetes.operator.work.Packet;
import oracle.kubernetes.operator.work.Step;
import oracle.kubernetes.weblogic.domain.model.Domain;
import oracle.kubernetes.weblogic.domain.model.DomainCondition;

import static oracle.kubernetes.operator.DomainFailureReason.Kubernetes;
import static oracle.kubernetes.operator.KubernetesConstants.HTTP_CONFLICT;
import static oracle.kubernetes.operator.KubernetesConstants.HTTP_GATEWAY_TIMEOUT;
import static oracle.kubernetes.operator.KubernetesConstants.HTTP_INTERNAL_ERROR;
import static oracle.kubernetes.operator.KubernetesConstants.HTTP_NOT_FOUND;
import static oracle.kubernetes.operator.KubernetesConstants.HTTP_TOO_MANY_REQUESTS;
import static oracle.kubernetes.operator.KubernetesConstants.HTTP_UNAVAILABLE;
import static oracle.kubernetes.operator.calls.CallResponse.createFailure;
import static oracle.kubernetes.operator.calls.CallResponse.createSuccess;
import static oracle.kubernetes.operator.helpers.NamespaceHelper.getOperatorNamespace;
import static oracle.kubernetes.operator.logging.MessageKeys.ASYNC_SUCCESS;
import static oracle.kubernetes.weblogic.domain.model.DomainConditionType.Failed;

/**
 * A Step driven by an asynchronous call to the Kubernetes API, which results in a series of
 * callbacks until canceled.
 */
public class AsyncRequestStep<T> extends Step implements RetryStrategyListener {
  public static final String RESPONSE_COMPONENT_NAME = "response";
  public static final String CONTINUE = "continue";
  public static final int FIBER_TIMEOUT = 0;

  private static final Random R = new Random();
  private static final int HIGH = 200;
  private static final int LOW = 10;
  private static final int SCALE = 100;
  private static final int MAX = 10000;
  private static final LoggingFacade LOGGER = LoggingFactory.getLogger("Operator", "Operator");

  private final Pool<ApiClient> helper;
  private final RequestParams requestParams;
  private final CallFactory<T> factory;
  private final int maxRetryCount;
  private final RetryStrategy customRetryStrategy;
  private final String fieldSelector;
  private final String labelSelector;
  private final String resourceVersion;
  private int timeoutSeconds;
  private DomainCondition recordedFailure;

  /**
   * Construct async step.
   *
   * @param next Next
   * @param requestParams Request parameters
   * @param factory Factory
   * @param helper Client pool
   * @param timeoutSeconds Timeout
   * @param maxRetryCount Max retry count
   * @param fieldSelector Field selector
   * @param labelSelector Label selector
   * @param resourceVersion Resource version
   */
  public AsyncRequestStep(
      ResponseStep<T> next,
      RequestParams requestParams,
      CallFactory<T> factory,
      Pool<ApiClient> helper,
      int timeoutSeconds,
      int maxRetryCount,
      String fieldSelector,
      String labelSelector,
      String resourceVersion) {
    this(next, requestParams, factory, null, helper, timeoutSeconds, maxRetryCount,
            null, fieldSelector, labelSelector, resourceVersion);
  }

  /**
   * Construct async step.
   *
   * @param next Next
   * @param requestParams Request parameters
   * @param factory Factory
   * @param customRetryStrategy Custom retry strategy
   * @param helper Client pool
   * @param timeoutSeconds Timeout
   * @param maxRetryCount Max retry count
   * @param gracePeriodSeconds Grace period
   * @param fieldSelector Field selector
   * @param labelSelector Label selector
   * @param resourceVersion Resource version
   */
  public AsyncRequestStep(
          ResponseStep<T> next,
          RequestParams requestParams,
          CallFactory<T> factory,
          RetryStrategy customRetryStrategy,
          Pool<ApiClient> helper,
          int timeoutSeconds,
          int maxRetryCount,
          Integer gracePeriodSeconds,
          String fieldSelector,
          String labelSelector,
          String resourceVersion) {
    super(next);
    this.helper = helper;
    this.requestParams = requestParams;
    this.factory = factory;
    this.customRetryStrategy = customRetryStrategy;
    this.timeoutSeconds = timeoutSeconds;
    this.maxRetryCount = maxRetryCount;
    this.fieldSelector = fieldSelector;
    this.labelSelector = labelSelector;
    this.resourceVersion = resourceVersion;

    // TODO, RJE: consider reimplementing the connection between the response and request steps using just
    // elements in the packet so that all step implementations are stateless.
    next.setPrevious(this);
  }

  /**
   * Access continue field, if any, from list metadata.
   * @param result Kubernetes list result
   * @return Continue value
   */
  public static String accessContinue(Object result) {
    return Optional.ofNullable(result)
        .filter(KubernetesListObject.class::isInstance)
        .map(KubernetesListObject.class::cast)
        .map(KubernetesListObject::getMetadata)
        .map(V1ListMeta::getContinue)
        .filter(Predicate.not(String::isEmpty))
        .orElse(null);
  }

  @Override
  protected String getDetail() {
    return requestParams.call;
  }

  @Override
  public void listenTimeoutDoubled() {
    timeoutSeconds *= 2;
  }

  class AsyncRequestStepProcessing {

    final Packet packet;
    final RetryStrategy retryStrategy;
    final String cont;
    final AtomicBoolean didResume = new AtomicBoolean(false);
    final ApiClient client;

    public AsyncRequestStepProcessing(Packet packet, RetryStrategy retry, String cont) {
      this.packet = packet;
      retryStrategy = Optional.ofNullable(retry)
            .orElse(new DefaultRetryStrategy(maxRetryCount, AsyncRequestStep.this, AsyncRequestStep.this));
      this.cont = Optional.ofNullable(cont).orElse("");
      client = helper.take();
    }

    // Create a call to Kubernetes that we can cancel if it doesn't succeed in time.
    private CancellableCall createCall(AsyncFiber fiber) throws ApiException {
      return factory.generate(requestParams, client, cont, new ApiCallbackImpl(this, fiber));
    }

    // The Kubernetes request succeeded. Recycle the client, add the response to the packet, and proceed.
    void onSuccess(AsyncFiber fiber, T result, int statusCode, Map<String, List<String>> responseHeaders) {
      removeExistingFailureCondition();
      if (firstTimeResumed()) {
        if (LOGGER.isFinerEnabled()) {
          logSuccess(result, statusCode, responseHeaders);
        }

        helper.recycle(client);
        addResponseComponent(Component.createFor(
            createSuccess(requestParams, result, statusCode).withResponseHeaders(responseHeaders)));
        fiber.resume(packet);
      }
    }

    private void removeExistingFailureCondition() {
      DomainPresenceInfo.fromPacket(packet)
            .map(DomainPresenceInfo::getDomain)
            .map(Domain::getStatus)
            .ifPresent(status -> status.removeCondition(recordedFailure));
    }

    // We received a failure from Kubernetes. Recycle the client,
    // add the failure into the packet and prepare to try again.
    void onFailure(AsyncFiber fiber, ApiException ae, int statusCode, Map<String, List<String>> responseHeaders) {
      if (firstTimeResumed()) {
        if (statusCode != HTTP_NOT_FOUND) {
          addDomainFailureStatus(ae);
          if (LOGGER.isFineEnabled()) {
            logFailure(ae, statusCode, responseHeaders);
          }
        }

        if (ae.getCause() instanceof java.net.ProtocolException) {
          helper.discard(client);
        } else {
          helper.recycle(client);
        }

        addResponseComponent(Component.createFor(
              RetryStrategy.class, retryStrategy,
              createFailure(requestParams, ae, statusCode).withResponseHeaders(responseHeaders)));
        fiber.resume(packet);
      }
    }

    private void addDomainFailureStatus(ApiException apiException) {
      DomainPresenceInfo.fromPacket(packet)
            .map(DomainPresenceInfo::getDomain)
            .ifPresent(domain -> updateFailureStatus(domain, apiException));
    }

    private void updateFailureStatus(@Nonnull Domain domain, ApiException apiException) {
      final var condition = new DomainCondition(Failed).withReason(Kubernetes).withMessage(createMessage(apiException));
      if (recordedFailure == null) {
        addFailureStatus(domain, condition);
      } else if (!recordedFailure.equals(condition)) {
        domain.getStatus().removeCondition(recordedFailure);
        addFailureStatus(domain, condition);
      }
    }

    private void addFailureStatus(@Nonnull Domain domain, DomainCondition condition) {
      recordedFailure = condition;
      domain.getOrCreateStatus().addCondition(recordedFailure);
    }

    private String createMessage(ApiException apiException) {
      return AsyncRequestStep.this.requestParams.createFailureMessage(apiException);
    }

    // If this is the first event after the fiber resumes, it indicates that we did not receive
    // a callback within the timeout. So cancel the call and prepare to try again.
    private void handleTimeout(AsyncFiber fiber, CancellableCall cc) {
      if (firstTimeResumed()) {
        try {
          cc.cancel();
        } finally {
          if (LOGGER.isFinerEnabled()) {
            logTimeout();
          }
          addResponseComponent(Component.createFor(RetryStrategy.class, retryStrategy));
          fiber.resume(packet);
        }
      }
    }

    // A throwable occurred while attempting to set up the call. So prepare to try again.
    private void resumeAfterThrowable(AsyncFiber fiber) {
      if (firstTimeResumed()) {
        addResponseComponent(Component.createFor(RetryStrategy.class, retryStrategy));
        fiber.resume(packet);
      }
    }

    private void addResponseComponent(Component component) {
      packet.getComponents().put(RESPONSE_COMPONENT_NAME, component);
    }

    private boolean firstTimeResumed() {
      return didResume.compareAndSet(false, true);
    }
  }

  private String getOperation() {
    return this.requestParams.call;
  }

  @Override
  public NextAction apply(Packet packet) {
    // we don't have the domain presence information and logging context information yet,
    // add a logging context to pass the namespace information to the LoggingFormatter
    if (requestParams.namespace != null 
        && DomainPresenceInfo.fromPacket(packet).isEmpty()
        && LoggingContext.fromPacket(packet).isEmpty()
        && !requestParams.namespace.equals(getOperatorNamespace())) {
      packet.getComponents().put(
          LoggingContext.LOGGING_CONTEXT_KEY,
          Component.createFor(new LoggingContext()
              .namespace(requestParams.namespace)
              .domainUid(requestParams.domainUid)));
    }

    // clear out earlier results
    String cont = (String) packet.remove(CONTINUE);
    RetryStrategy retry = null;
    Component oldResponse = packet.getComponents().remove(RESPONSE_COMPONENT_NAME);
    if (oldResponse != null) {
      @SuppressWarnings("unchecked")
      CallResponse<T> old = oldResponse.getSpi(CallResponse.class);
      if (cont != null && old != null && old.getResult() != null) {
        // called again, access continue value, if available
        cont = accessContinue(old.getResult());
      }

      retry = oldResponse.getSpi(RetryStrategy.class);
    }
    if ((retry == null) && (customRetryStrategy != null)) {
      retry = customRetryStrategy;
    }

    if (LOGGER.isFinerEnabled()) {
      logAsyncRequest();
    }

    AsyncRequestStepProcessing processing = new AsyncRequestStepProcessing(packet, retry, cont);
    return doSuspend(
        (fiber) -> {
          try {
            CancellableCall cc = processing.createCall(fiber);
            scheduleTimeoutCheck(fiber, timeoutSeconds, () -> processing.handleTimeout(fiber, cc));
          } catch (ApiException t) {
            logAsyncFailure(t, t.getResponseBody());
            processing.resumeAfterThrowable(fiber);
          } catch (Throwable t) {
            logAsyncFailure(t, "");
            processing.resumeAfterThrowable(fiber);
          }
        });
  }

  // Schedule the timeout check to happen on the fiber at some number of seconds in the future.
  private void scheduleTimeoutCheck(AsyncFiber fiber, int timeoutSeconds, Runnable timeoutCheck) {
    fiber.scheduleOnce(timeoutSeconds, TimeUnit.SECONDS, timeoutCheck);
  }

  private void logAsyncRequest() {
    // called from the apply method where we have the necessary information for logging context
    LOGGER.finer(
        MessageKeys.ASYNC_REQUEST,
        identityHash(),
        requestParams.call,
        requestParams.namespace,
        requestParams.name,
        requestParams.body != null ? LoggingFactory.getJson().serialize(requestParams.body) : "",
        fieldSelector,
        labelSelector,
        resourceVersion);
  }

  private void logAsyncFailure(Throwable t, String responseBody) {
    // called from the apply method where we have the necessary information for logging context
    LOGGER.warning(
        MessageKeys.ASYNC_FAILURE,
        t.getMessage(),
        0,
        null,
        requestParams,
        requestParams.namespace,
        requestParams.name,
        requestParams.body != null
            ? LoggingFactory.getJson().serialize(requestParams.body)
            : "",
        fieldSelector,
        labelSelector,
        resourceVersion,
        responseBody);
  }

  private void logTimeout() {
    // called from a code path where we don't have the necessary information for logging context
    // so we need to use the thread context to pass in the logging context
    try (LoggingContext ignored =
             LoggingContext.setThreadContext()
                 .namespace(requestParams.namespace)
                 .domainUid(requestParams.domainUid)) {
      LOGGER.finer(
          MessageKeys.ASYNC_TIMEOUT,
          identityHash(),
          requestParams.call,
          requestParams.namespace,
          requestParams.name,
          requestParams.body != null
              ? LoggingFactory.getJson().serialize(requestParams.body)
              : "",
          fieldSelector,
          labelSelector,
          resourceVersion);
    }
  }

  private void logSuccess(T result, int statusCode, Map<String, List<String>> responseHeaders) {
    // called from a code path where we don't have the necessary information for logging context
    // so we need to use the thread context to pass in the logging context
    try (LoggingContext ignored =
             LoggingContext.setThreadContext()
                 .namespace(requestParams.namespace)
                 .domainUid(requestParams.domainUid)) {
      LOGGER.finer(
          ASYNC_SUCCESS,
          identityHash(),
          requestParams.call,
          result,
          statusCode,
          responseHeaders);
    }
  }

  private void logFailure(ApiException ae, int statusCode, Map<String, List<String>> responseHeaders) {
    // called from a code path where we don't have the necessary information for logging context
    // so we need to use the thread context to pass in the logging context
    try (LoggingContext ignored =
             LoggingContext.setThreadContext()
                 .namespace(requestParams.namespace)
                 .domainUid(requestParams.domainUid)) {
      LOGGER.fine(
          MessageKeys.ASYNC_FAILURE,
          identityHash(),
          ae.getMessage(),
          statusCode,
          responseHeaders,
          requestParams.call,
          requestParams.namespace,
          requestParams.name,
          requestParams.body != null
              ? LoggingFactory.getJson().serialize(requestParams.body)
              : "",
          fieldSelector,
          labelSelector,
          resourceVersion,
          ae.getResponseBody());
    }
  }

  // creates a unique ID that allows matching requests to responses
  private String identityHash() {
    return Integer.toHexString(System.identityHashCode(this));
  }

  private final class DefaultRetryStrategy implements RetryStrategy {
    private long retryCount = 0;
    private final int maxRetryCount;
    private final Step retryStep;
    private final RetryStrategyListener listener;

    DefaultRetryStrategy(int maxRetryCount, Step retryStep, RetryStrategyListener listener) {
      this.maxRetryCount = maxRetryCount;
      this.retryStep = retryStep;
      this.listener = listener;
    }

    @Override
    public NextAction doPotentialRetry(Step conflictStep, Packet packet, int statusCode) {
      if (mayRetryOnStatusValue(statusCode)) {
        optionallyAdjustListenTimeout(statusCode);
        return retriesLeft() ? backOffAndRetry(packet, retryStep) : null;
      } else if (isRestartableConflict(conflictStep, statusCode)) {
        return backOffAndRetry(packet, conflictStep);
      } else {
        return null;
      }
    }

    private void optionallyAdjustListenTimeout(int statusCode) {
      if (statusCode == FIBER_TIMEOUT || statusCode == HTTP_GATEWAY_TIMEOUT) {
        listener.listenTimeoutDoubled();
      }
    }

    // Check statusCode, many statuses should not be retried
    // https://github.com/kubernetes/community/blob/master/contributors/devel/sig-architecture/api-conventions.md#http-status-codes
    private boolean mayRetryOnStatusValue(int statusCode) {
      return statusCode == FIBER_TIMEOUT
            || statusCode == HTTP_TOO_MANY_REQUESTS
            || statusCode == HTTP_INTERNAL_ERROR
            || statusCode == HTTP_UNAVAILABLE
            || statusCode == HTTP_GATEWAY_TIMEOUT;
    }

    @Nonnull
    private NextAction backOffAndRetry(Packet packet, Step nextStep) {
      final long waitTime = getNextWaitTime();
      LOGGER.finer(MessageKeys.ASYNC_RETRY, identityHash(), String.valueOf(waitTime),
            requestParams.call, requestParams.namespace, requestParams.name);

      final NextAction na = new NextAction();
      na.delay(nextStep, packet, waitTime, TimeUnit.MILLISECONDS);
      return na;
    }

    // Compute wait time, increasing exponentially
    private int getNextWaitTime() {
      return Math.min((2 << ++retryCount) * SCALE, MAX) + (R.nextInt(HIGH - LOW) + LOW);
    }

    // Conflict is an optimistic locking failure.  Therefore, we can't
    // simply retry the request.  Instead, application code needs to rebuild
    // the request based on latest contents.  If provided, a conflict step will do that.
    private boolean isRestartableConflict(Step conflictStep, int statusCode) {
      return statusCode == HTTP_CONFLICT && conflictStep != null;
    }

    private boolean retriesLeft() {
      return (retryCount + 1) <= maxRetryCount;
    }

    @Override
    public void reset() {
      retryCount = 0;
    }
  }

  private class ApiCallbackImpl implements ApiCallback<T> {

    private final AsyncRequestStepProcessing processing;
    private final AsyncFiber fiber;

    public ApiCallbackImpl(AsyncRequestStepProcessing processing, AsyncFiber fiber) {
      this.processing = processing;
      this.fiber = fiber;
    }

    @Override
    public void onUploadProgress(long bytesWritten, long contentLength, boolean done) {
      // no-op
    }

    @Override
    public void onDownloadProgress(long bytesRead, long contentLength, boolean done) {
      // no-op
    }

    @Override
    public void onFailure(ApiException ae, int statusCode, Map<String, List<String>> responseHeaders) {
      processing.onFailure(fiber, ae, statusCode, responseHeaders);
    }

    @Override
    public void onSuccess(T result, int statusCode, Map<String, List<String>> responseHeaders) {
      processing.onSuccess(fiber, result, statusCode, responseHeaders);
    }
  }
}
