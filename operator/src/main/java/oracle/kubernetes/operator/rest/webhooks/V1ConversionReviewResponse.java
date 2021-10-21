// Copyright (c) 2021, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.rest.webhooks;

import java.util.ArrayList;
import java.util.List;

import io.kubernetes.client.common.KubernetesObject;
import jakarta.validation.Valid;
import oracle.kubernetes.json.Description;

public class V1ConversionReviewResponse<T extends KubernetesObject> {

  @Description("The API version defines the versioned schema of this Domain. Required.")
  private String uid;

  @Description("The API version defines the versioned schema of this Domain. Required.")
  private V1ConversionReviewResponseResult result;

  @Description("Status of WebLogic clusters in this domain.")
  @Valid
  private List<T> convertedObjects = new ArrayList<>();

  public String getUid() {
    return uid;
  }

  public void setUid(String uid) {
    this.uid = uid;
  }

  public V1ConversionReviewResponse<T> withUid(String uid) {
    this.uid = uid;
    return this;
  }

  public V1ConversionReviewResponseResult getResult() {
    return result;
  }

  public void setResult(V1ConversionReviewResponseResult result) {
    this.result = result;
  }

  public V1ConversionReviewResponse<T> withResult(V1ConversionReviewResponseResult result) {
    this.result = result;
    return this;
  }

  public List<T> getConvertedObjects() {
    return convertedObjects;
  }

  public void setConvertedObjects(List<T> objects) {
    this.convertedObjects = convertedObjects;
  }
}
