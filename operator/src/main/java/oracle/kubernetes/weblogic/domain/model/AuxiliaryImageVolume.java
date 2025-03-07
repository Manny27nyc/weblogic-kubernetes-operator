// Copyright (c) 2021, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.weblogic.domain.model;

import java.util.Optional;

import jakarta.validation.constraints.NotNull;
import oracle.kubernetes.json.Description;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.ToStringBuilder;

public class AuxiliaryImageVolume {

  public static final String DEFAULT_AUXILIARY_IMAGE_PATH = "/auxiliary";

  @Description("The name of the volume. Required.")
  @NotNull
  private String name;

  @Description("The mount path. The files in the path are populated from the same named directory in the images "
          + "supplied by each container in `serverPod.auxiliaryImages`. Each volume must be configured with "
          + "a different mount path. Defaults to '/auxiliary'.")
  @NotNull
  private String mountPath;

  @Description("The emptyDir volume medium. This is an advanced setting that rarely needs to be configured. "
          + "Defaults to unset, which means the volume's files are stored on the local node's file system for "
          + "the life of the pod.")
  private String medium;

  @Description("The emptyDir volume size limit. Defaults to unset.")
  private String sizeLimit;

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public AuxiliaryImageVolume name(String name) {
    this.name = name;
    return this;
  }

  public String getMountPath() {
    return Optional.ofNullable(mountPath).orElse(DEFAULT_AUXILIARY_IMAGE_PATH);
  }

  public void setMountPath(String mountPath) {
    this.mountPath = mountPath;
  }

  public AuxiliaryImageVolume mountPath(String mountPath) {
    this.mountPath = mountPath;
    return this;
  }

  public String getMedium() {
    return medium;
  }

  public void setMedium(String medium) {
    this.medium = medium;
  }

  public AuxiliaryImageVolume medium(String medium) {
    this.medium = medium;
    return this;
  }

  public String getSizeLimit() {
    return sizeLimit;
  }

  public void setSizeLimit(String sizeLimit) {
    this.sizeLimit = sizeLimit;
  }

  public AuxiliaryImageVolume sizeLimit(String sizeLimit) {
    this.sizeLimit = sizeLimit;
    return this;
  }

  @Override
  public String toString() {
    ToStringBuilder builder =
            new ToStringBuilder(this)
                    .append("name", name)
                    .append("mountPath", mountPath)
                    .append("medium", medium)
                    .append("sizeLimit", sizeLimit);
    return builder.toString();
  }

  @Override
  public int hashCode() {
    HashCodeBuilder builder = new HashCodeBuilder()
            .append(name)
            .append(mountPath)
            .append(medium)
            .append(sizeLimit);
    return builder.toHashCode();
  }

  @Override
  public boolean equals(Object other) {
    if (other == this) {
      return true;
    }
    if (!(other instanceof AuxiliaryImageVolume)) {
      return false;
    }

    AuxiliaryImageVolume rhs = ((AuxiliaryImageVolume) other);
    EqualsBuilder builder =
            new EqualsBuilder()
                    .append(name, rhs.name)
                    .append(mountPath, rhs.mountPath)
                    .append(medium, rhs.medium)
                    .append(sizeLimit, rhs.sizeLimit);

    return builder.isEquals();
  }
}
