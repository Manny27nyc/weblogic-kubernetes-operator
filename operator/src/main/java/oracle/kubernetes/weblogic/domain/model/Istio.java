// Copyright (c) 2019, 2021, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.weblogic.domain.model;

import oracle.kubernetes.json.Description;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.ToStringBuilder;

public class Istio {

  @Description(
      "True, if this domain is deployed under an Istio service mesh. "
          + "Defaults to true when the `istio` field is specified.")
  private Boolean enabled = true;

  @Description("The operator will create a WebLogic network access point with this port that will then be exposed "
          + "from the container running the WebLogic Server instance. The readiness probe will use this network "
          + "access point to verify that the server instance is ready for application traffic. Defaults to 8888.")
  private Integer readinessPort = 8888;

  public static Integer DEFAULT_REPLICATION_PORT = 4358;

  private Integer replicationChannelPort = DEFAULT_REPLICATION_PORT;

  /**
   * True, if this domain is deployed under an Istio service mesh.
   *
   * @return True, if this domain is deployed under an Istio service mesh.
   */
  public Boolean getEnabled() {
    return this.enabled;
  }

  /**
   * Sets the Istio enabled status.
   *
   * @param enabled True, if this domain is deployed under an Istio service mesh.
   */
  public void setEnabled(Boolean enabled) {
    this.enabled = enabled;
  }

  /**
   * Get the readiness port.
   *
   * @return the readiness port.
   */
  public Integer getReadinessPort() {
    return this.readinessPort;
  }

  /**
   * Sets the Istio readiness port.
   *
   * @param readinessPort the Istio readiness port.
   */
  public void setReadinessPort(Integer readinessPort) {
    this.readinessPort = readinessPort;
  }

  /**
   * Sets the Istio enabled status.
   *
   * @param istioEnabled True, if this domain is deployed under an Istio service mesh.
   * @return this
   */
  public Istio withIstioEnabled(Boolean istioEnabled) {
    this.enabled = istioEnabled;
    return this;
  }

  public Integer getReplicationChannelPort() {
    return replicationChannelPort;
  }

  public void setReplicationChannelPort(Integer replicationChannelPort) {
    this.replicationChannelPort = replicationChannelPort;
  }

  @Override
  public String toString() {
    ToStringBuilder builder =
        new ToStringBuilder(this)
            .append("enabled", enabled)
            .append("readinessPort", readinessPort);

    if (this.replicationChannelPort != DEFAULT_REPLICATION_PORT) {
      builder.append("replicationPort", this.replicationChannelPort);
    }

    return builder.toString();
  }

  @Override
  public int hashCode() {
    HashCodeBuilder builder = new HashCodeBuilder().append(enabled).append(readinessPort);

    if (this.replicationChannelPort != DEFAULT_REPLICATION_PORT) {
      builder.append(this.replicationChannelPort);
    }

    return builder.toHashCode();
  }

  @Override
  public boolean equals(Object other) {
    if (other == this) {
      return true;
    }
    if (!(other instanceof Istio)) {
      return false;
    }

    Istio rhs = ((Istio) other);
    EqualsBuilder builder =
        new EqualsBuilder()
            .append(enabled, rhs.enabled)
            .append(readinessPort, rhs.readinessPort);

    return builder.isEquals();
  }
}
