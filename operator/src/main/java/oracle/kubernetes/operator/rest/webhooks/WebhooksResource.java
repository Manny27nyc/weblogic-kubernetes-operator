// Copyright (c) 2021, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.rest.webhooks;

import java.util.List;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import oracle.kubernetes.operator.logging.LoggingFacade;
import oracle.kubernetes.operator.logging.LoggingFactory;
import oracle.kubernetes.weblogic.domain.model.Domain;

/**
 * WebhooksResource is a JAX-RS resource that implements any webhook REST APIs. Initially this will just be the
 * Custom Resource Conversion Webhook
 */
@Path("webhooks")
public class WebhooksResource {

  private static final LoggingFacade LOGGER = LoggingFactory.getLogger("Operator", "Operator");

  /**
   * Placeholder.
   * @param requestReview Review request
   * @return Review response
   */
  @POST
  @Path("conversion")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public V1ConversionReview<Domain> conversion(final V1ConversionReview<Domain> requestReview) {
    V1ConversionReview<Domain> responseReview = new V1ConversionReview<>();

    responseReview.setKind(requestReview.getKind());
    responseReview.setApiVersion(requestReview.getApiVersion());

    V1ConversionReviewRequest<Domain> request = requestReview.getRequest();

    V1ConversionReviewResponse<Domain> response = new V1ConversionReviewResponse<>();
    response.setUid(request.getUid());

    List<Domain> convertedObjects = response.getConvertedObjects();
    request.getObjects().stream().forEach(object -> {
      object.setApiVersion(request.getDesiredApiVersion());
      convertedObjects.add(object);
    });

    V1ConversionReviewResponseResult responseResult = new V1ConversionReviewResponseResult();
    responseResult.setStatus("Success");
    response.setResult(responseResult);

    responseReview.setResponse(response);
    return responseReview;
  }

}
