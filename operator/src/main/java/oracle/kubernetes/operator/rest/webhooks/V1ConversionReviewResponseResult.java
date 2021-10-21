// Copyright (c) 2021, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.rest.webhooks;

import com.google.gson.annotations.SerializedName;
import oracle.kubernetes.json.Description;

public class V1ConversionReviewResponseResult {

  @Description("The API version defines the versioned schema of this Domain. Required.")
  private String status;

  @Description("The API version defines the versioned schema of this Domain. Required.")
  @SerializedName("desiredAPIVersion")
  private String message;

  public String getStatus() {
    return status;
  }

  public void setStatus(String apiVersion) {
    this.status = status;
  }

  public V1ConversionReviewResponseResult withStatus(String status) {
    this.status = status;
    return this;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public V1ConversionReviewResponseResult withMessage(String message) {
    this.message = message;
    return this;
  }

}
