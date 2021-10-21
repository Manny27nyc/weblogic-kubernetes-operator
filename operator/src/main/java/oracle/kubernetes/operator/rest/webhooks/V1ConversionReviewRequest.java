// Copyright (c) 2021, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.rest.webhooks;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.annotations.SerializedName;
import io.kubernetes.client.common.KubernetesObject;
import jakarta.validation.Valid;
import oracle.kubernetes.json.Description;

public class V1ConversionReviewRequest<T extends KubernetesObject> {

  @Description("The API version defines the versioned schema of this Domain. Required.")
  private String uid;

  @Description("The API version defines the versioned schema of this Domain. Required.")
  @SerializedName("desiredAPIVersion")
  private String desiredApiVersion;

  @Description("Status of WebLogic clusters in this domain.")
  @Valid
  private List<T> objects = new ArrayList<>();

  public String getUid() {
    return uid;
  }

  public void setUid(String apiVersion) {
    this.uid = uid;
  }

  public V1ConversionReviewRequest<T> withUid(String uid) {
    this.uid = uid;
    return this;
  }

  public String getDesiredApiVersion() {
    return desiredApiVersion;
  }

  public void setDesiredApiVersion(String desiredApiVersion) {
    this.desiredApiVersion = desiredApiVersion;
  }

  public V1ConversionReviewRequest<T> withDesiredApiVersion(String desiredApiVersion) {
    this.desiredApiVersion = desiredApiVersion;
    return this;
  }

  public List<T> getObjects() {
    return objects;
  }

  public void setObjects(List<T> objects) {
    this.objects = objects;
  }
}
