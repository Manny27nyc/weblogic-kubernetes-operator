---
title: "Startup and shutdown"
date: 2019-02-23T17:04:41-05:00
draft: false
weight: 1
description: "There are fields on the Domain that specify which WebLogic Server instances should be running,
started, or restarted. To start, stop, or restart servers, modify these fields on the Domain."
---

### Contents

- [Introduction](#introduction)
- [Starting and stopping servers](#starting-and-stopping-servers)
  - [`serverStartPolicy` rules](#serverstartpolicy-rules)
  - [Available `serverStartPolicy` values](#available-serverstartpolicy-values)
  - [Administration Server start and stop rules](#administration-server-start-and-stop-rules)
  - [Standalone Managed Server start and stop rules](#standalone-managed-server-start-and-stop-rules)
  - [Clustered Managed Server start and stop rules](#clustered-managed-server-start-and-stop-rules)
- [Server start state](#server-start-state)
- [Common starting and stopping scenarios](#common-starting-and-stopping-scenarios)
  - [Normal running state](#normal-running-state)
  - [Shut down all the servers](#shut-down-all-the-servers)
  - [Only start the Administration Server](#only-start-the-administration-server)
  - [Shut down a cluster](#shut-down-a-cluster)
  - [Shut down a specific standalone server](#shut-down-a-specific-standalone-server)
  - [Force a specific clustered Managed Server to start](#force-a-specific-clustered-managed-server-to-start)
- [Shutdown options](#shutdown-options)
  - [Shutdown environment variables](#shutdown-environment-variables)
  - [`shutdown` rules](#shutdown-rules)
- [Restarting servers](#restarting-servers)
  - [Fields that cause servers to be restarted](#fields-that-cause-servers-to-be-restarted)
- [Rolling restarts](#rolling-restarts)
- [Draining a node and PodDisruptionBudget](#draining-a-node-and-poddisruptionbudget)
- [Common restarting scenarios](#common-restarting-scenarios)
  - [Using `restartVersion` to force the operator to restart servers](#using-restartversion-to-force-the-operator-to-restart-servers)
  - [Restart all the servers in the domain](#restart-all-the-servers-in-the-domain)
  - [Restart all the servers in the cluster](#restart-all-the-servers-in-the-cluster)
  - [Restart the Administration Server](#restart-the-administration-server)
  - [Restart a standalone or clustered Managed Server](#restart-a-standalone-or-clustered-managed-server)
  - [Full domain restarts](#full-domain-restarts)
- [Domain lifecycle sample scripts](#domain-lifecycle-sample-scripts)

### Introduction

There are fields on the Domain that specify which servers should be running,
which servers should be restarted, and the desired initial state. To start, stop, or restart servers, modify these fields on the Domain
(for example, by using `kubectl` or the Kubernetes REST API).  The operator will detect the changes and apply them. Beginning
with operator version 2.2.0, there are now fields to control server shutdown handling, such as whether the shutdown
will be graceful, the timeout, and if in-flight sessions are given the opportunity to complete.

### Starting and stopping servers

The `serverStartPolicy` and `replicas` fields of the Domain controls which servers should be running.
The operator monitors these fields and creates or deletes the corresponding WebLogic Server instance Pods.

{{% notice note %}} Do not use the WebLogic Server Administration Console to start or stop servers.
{{% /notice %}}

#### `serverStartPolicy` rules

You can specify the `serverStartPolicy` property at the domain, cluster, and server levels. Each level supports a different set of values.

#### Available `serverStartPolicy` values
| Level | Default Value | Supported Values |
| --- | --- | --- |
| Domain | `IF_NEEDED` | `IF_NEEDED`, `ADMIN_ONLY`, `NEVER` |
| Cluster | `IF_NEEDED` | `IF_NEEDED`, `NEVER` |
| Server | `IF_NEEDED` | `IF_NEEDED`, `ALWAYS`, `NEVER` |

#### Administration Server start and stop rules
| Domain | Admin Server | Started / Stopped |
| --- | --- | --- |
| `NEVER` | any value | Stopped |
| `ADMIN_ONLY`, `IF_NEEDED` | `NEVER` | Stopped |
| `ADMIN_ONLY`, `IF_NEEDED` | `IF_NEEDED`, `ALWAYS` | Started |

#### Standalone Managed Server start and stop rules
| Domain | Standalone Server | Started / Stopped |
| --- | --- | --- |
| `ADMIN_ONLY`, `NEVER` | any value | Stopped |
| `IF_NEEDED` | `NEVER` | Stopped |
| `IF_NEEDED` | `IF_NEEDED`, `ALWAYS` | Started |

#### Clustered Managed Server start and stop rules
| Domain | Cluster | Clustered Server | Started / Stopped |
| --- | --- | --- | --- |
| `ADMIN_ONLY`, `NEVER` | any value | any value | Stopped |
| `IF_NEEDED` | `NEVER` | any value | Stopped |
| `IF_NEEDED` | `IF_NEEDED` | `NEVER` | Stopped |
| `IF_NEEDED` | `IF_NEEDED` | `ALWAYS` | Started |
| `IF_NEEDED` | `IF_NEEDED` | `IF_NEEDED` | Started if needed to get to the cluster's `replicas` count |

{{% notice note %}}
Servers configured as `ALWAYS` count toward the cluster's `replicas` count.
{{% /notice %}}

{{% notice note %}}
If more servers are configured as `ALWAYS` than the cluster's `replicas` count, they will all be started and the `replicas` count will be exceeded.
{{% /notice %}}

### Server start state

For some use cases, such as an externally managed zero downtime patching (ZDP), it may be necessary to start WebLogic Server instances
so that at the end of its startup process, the server is in an administrative state.  This can be achieved using the `serverStartState`
field, which is available at domain, cluster, and server levels. When `serverStartState` is set to `ADMIN`, then servers will
progress only to the administrative state.  Then you could use the WebLogic Server Administration Console, REST API, or a WLST script to make any necessary
updates before advancing the server to the running state.

Changes to the `serverStartState` property do not affect already started servers.

### Common starting and stopping scenarios

#### Normal running state
Normally, the Administration Server, all of the standalone Managed Servers, and enough Managed Servers members in each cluster to satisfy its `replicas` count, should be started.
In this case, the Domain does not need to specify `serverStartPolicy`, or list any `clusters` or `servers`, but it does need to specify a `replicas` count.

For example:
```
  kind: Domain
  metadata:
    name: domain1
  spec:
    image: ...
    replicas: 3
```

#### Shut down all the servers
Sometimes you need to completely shut down the domain (for example, take it out of service).
```
  kind: Domain
  metadata:
    name: domain1
  spec:
    serverStartPolicy: "NEVER"
    ...
```

#### Only start the Administration Server
Sometimes you want to start the Administration Server only, that is, take the Managed Servers out of service but leave the Administration Server running so that you can administer the domain.
```
  kind: Domain
  metadata:
    name: domain1
  spec:
    serverStartPolicy: "ADMIN_ONLY"
    ...
```

#### Shut down a cluster
To shut down a cluster (for example, take it out of service), add it to the Domain and set its `serverStartPolicy` to `NEVER`.
```
  kind: Domain
  metadata:
    name: domain1
  spec:
    clusters:
    - clusterName: "cluster1"
      serverStartPolicy: "NEVER"
    ...
```

#### Shut down a specific standalone server
To shut down a specific standalone server, add it to the Domain and set its `serverStartPolicy` to `NEVER`.
```
  kind: Domain
  metadata:
    name: domain1
  spec:
    managedServers:
    - serverName: "server1"
      serverStartPolicy: "NEVER"
    ...
```
{{% notice note %}}
The Administration Server can be shut down by setting the `serverStartPolicy` of the `adminServer` to `NEVER`.
Care should be taken when shutting down the Administration Server. If a Managed Server cannot connect
to the Administration Server during startup, it will try to start up in
[*Managed Server Independence (MSI)* mode](https://docs.oracle.com/en/middleware/fusion-middleware/weblogic-server/12.2.1.4/start/failures.html#GUID-CA4696B6-B462-4FD8-92A9-F27DEA8A2E87)
but this could fail due to reasons such as no accessible
[*Authentication Provider*](https://docs.oracle.com/en/middleware/fusion-middleware/weblogic-server/12.2.1.4/secmg/atn_intro.html#GUID-E56E30B4-5C18-4A21-A683-AC166792A9DE)
from the Managed Server pod.
{{% /notice %}}


#### Force a specific clustered Managed Server to start
Normally, all of the Managed Servers members in a cluster are identical and it doesn't matter which ones are running as long as the operator starts enough of them to get to the cluster's `replicas` count.
However, sometimes some of the Managed Servers are different (for example, support some extra services that the other servers in the cluster use) and need to always be started.

This is done by adding the server to the Domain and setting its `serverStartPolicy` to `ALWAYS`.
```
  kind: Domain
  metadata:
    name: domain1
  spec:
    managedServers:
    - serverName: "cluster1_server1"
      serverStartPolicy: "ALWAYS"
    ...
```

{{% notice note %}}
The server will count toward the cluster's `replicas` count.  Also, if you configure more than the `replicas` servers count to `ALWAYS`, they will all be started, even though the `replicas` count will be exceeded.
{{%/ notice %}}

### Shutdown options

The Domain YAML file includes the field `serverPod` that is available under `spec`, `adminServer`, and each entry of
`clusters` and `managedServers`. The `serverPod` field controls many details of how Pods are generated for WebLogic Server instances.

The `shutdown` field of `serverPod` controls how servers will be shut down and has three fields:
`shutdownType`, `timeoutSeconds`, and `ignoreSessions`.  The `shutdownType` field can be set to either `Graceful`, the default,
or `Forced` specifying the type of shutdown.  The `timeoutSeconds` property configures how long the server is given to
complete shutdown before the server is killed.  The `ignoreSessions` property, which is only applicable for graceful shutdown, when `false`,
the default, allows the shutdown process to take longer to give time for any active sessions to complete up to the configured timeout.
The operator runtime monitors this property but will not restart any server pods solely to adjust the shutdown options.
Instead, server pods created or restarted because of another property change will be configured to shutdown, at the appropriate
time, using the shutdown options set when the WebLogic Server instance Pod is created.

#### Shutdown environment variables

The operator configures shutdown behavior with the use of the following environment variables. Users may
instead simply configure these environment variables directly.  When a user-configured environment variable is present,
the operator will not override the environment variable based on the shutdown configuration.

| Environment Variables | Default Value | Supported Values |
| --- | --- | --- |
| `SHUTDOWN_TYPE` | `Graceful` | `Graceful` or `Forced` |
| `SHUTDOWN_TIMEOUT` | 30 | Whole number in seconds where 0 means no timeout |
| `SHUTDOWN_IGNORE_SESSIONS` | `false` | Boolean indicating if active sessions should be ignored; only applicable if shutdown is graceful |

#### `shutdown` rules

You can specify the `serverPod` field, including the `shutdown` field, at the domain, cluster, and server levels. If
`shutdown` is specified at multiple levels, such as for a cluster and for a member server that is part of that cluster,
then the shutdown configuration for a specific server is the combination of all of the relevant values with each field
having the value from the `shutdown` field at the most specific scope.  

For instance, given the following Domain YAML file:
```
  kind: Domain
  metadata:
    name: domain1
  spec:
    serverPod:
      shutdown:
        shutdownType: Graceful
        timeoutSeconds: 45
    clusters:
    - clusterName: "cluster1"
      serverPod:
        shutdown:
          ignoreSessions: true
    managedServers:
    - serverName: "cluster1_server1"
      serverPod:
        shutdown:
          timeoutSeconds: 60
          ignoreSessions: false
    ...
```

Graceful shutdown is used for all servers in the domain because this is specified at the domain level and is not overridden at
any cluster or server level.  The "cluster1" cluster defaults to ignoring sessions; however, the "cluster1_server1" server
instance will not ignore sessions and will have a longer timeout.

### Restarting servers

The operator automatically recreates (restarts) WebLogic Server instance Pods when fields on the Domain that affect Pod generation change (such as `image`, `volumes`, and `env`).
The `restartVersion` field on the Domain lets you force the operator to restart a set of WebLogic Server instance Pods.

The operator does rolling restarts of clustered servers so that service is maintained.

#### Fields that cause servers to be restarted

The operator will restart servers when any of the follow fields on the Domain that affect the WebLogic Server instance Pod generation are changed:

* `auxiliaryImages`
* `auxiliaryImageVolumes`
* `containerSecurityContext`
* `domainHome`
* `domainHomeInImage`
* `domainHomeSourceType`
* `env`
* `image`
* `imagePullPolicy`
* `imagePullSecrets`
* `includeServerOutInPodLog`
* `logHomeEnabled`
* `logHome`
* `livenessProbe`
* `nodeSelector`
* `podSecurityContext`
* `readinessProbe`
* `resources`
* `restartVersion`
* `volumes`
* `volumeMounts`

For Model in Image, a change to the `introspectVersion` field, which causes the operator to initiate a new [introspection]({{< relref "/userguide/managing-domains/domain-lifecycle/introspection.md" >}}), will result in the restarting of servers if the introspection results in the generation of a modified WebLogic domain home. See the documentation on Model in Image [runtime updates]({{< relref "/userguide/managing-domains/model-in-image/runtime-updates.md" >}}) for a description of changes to the model or associated resources, such as Secrets, that will cause the generation of a modified WebLogic domain home.

{{% notice note %}}
If the only change detected is the addition or modification of a customer-specified label or annotation,
the operator will *patch* the Pod rather than restarting it. Removing a label or annotation from
the Domain will cause neither a restart nor a patch. It is possible to force a restart to remove
such a label or annotation by modifying the `restartVersion`.
{{% /notice %}}

{{% notice note %}}
Prior to version 2.2.0, the operator incorrectly restarted servers when the `serverStartState` field was changed.  Now,
this property has no affect on already running servers.
{{% /notice %}}

### Rolling restarts

Clustered servers that need to be restarted are gradually restarted (for example, "rolling restarted") so that the cluster is not taken out of service and in-flight work can be migrated to other servers in the cluster.

The `maxUnavailable` field on the Domain determines how many of the cluster's servers may be taken out of service at a time when doing a rolling restart.
It can be specified at the domain and cluster levels and defaults to 1 (that is, by default, clustered servers are restarted one at a time).

When using in-memory session replication, Oracle WebLogic Server employs a primary-secondary session replication model to provide high availability of application session state (that is, HTTP and EJB sessions).
The primary server creates a primary session state on the server to which the client first connects, and a secondary replica on another WebLogic Server instance in the cluster.
Specifying a `maxUnavailable` property value of `1` protects against inadvertent session state loss which could occur if both the primary and secondary
servers are shut down at the same time during the rolling restart process.

{{% notice note %}}
If you are supplying updated models or secrets for a running Model in Image domain, and you want the configuration updates to take effect using a rolling restart, consult [Modifying WebLogic Configuration]({{< relref "/userguide/managing-domains/domain-lifecycle/restarting/_index.md#modifying-the-weblogic-domain-configuration" >}}) and [Runtime updates]({{< relref "/userguide/managing-domains/model-in-image/runtime-updates.md" >}}) before consulting this document.
{{% /notice %}}

### Draining a node and PodDisruptionBudget

A Kubernetes cluster administrator can [drain a Node](https://kubernetes.io/docs/tasks/administer-cluster/safely-drain-node/) for repair, upgrade, or scaling down the Kubernetes cluster.

Beginning in version 3.2, the operator takes advantage of the [PodDisruptionBudget](https://kubernetes.io/docs/concepts/workloads/pods/disruptions/#pod-disruption-budgets) feature offered by Kubernetes for high availability during a Node drain operation. The operator creates a PodDisruptionBudget (PDB) for each WebLogic cluster in the Domain namespace to limit the number of WebLogic Server pods simultaneously evicted when draining a node. The maximum number of WebLogic cluster's server pods evicted simultaneously is determined by the `maxUnavailable` field on the Domain resource. The `.spec.minAvailable` field of the PDB for a cluster is calculated from the difference of the current `replicas` count and `maxUnavailable` value configured for the cluster. For example, if you have a WebLogic cluster with three replicas and a `maxUnavailable` of `1`, the `.spec.minAvailable` for PDB is set to `2`. In this case, Kubernetes ensures that at least two pods for the WebLogic cluster's Managed Servers are available at any given time, and it only evicts a pod when all three pods are ready. For details about safely draining a node and the PodDisruptionBudget concept, see [Safely Drain a Node](https://kubernetes.io/docs/tasks/administer-cluster/safely-drain-node/) and [PodDisruptionBudget](https://kubernetes.io/docs/concepts/workloads/pods/disruptions/).

### Common restarting scenarios

#### Using `restartVersion` to force the operator to restart servers

The `restartVersion` property lets you force the operator to restart servers.

Each time you want to restart some servers, you need to set `restartVersion` to a different value. The specific value does not matter so most customers use whole number values.

The operator will detect the new value and restart the affected servers (using the same mechanisms as when other fields that affect the WebLogic Server instance Pod generation are changed, including doing rolling restarts of clustered servers).

The `restartVersion` property can be specified at the domain, cluster, and server levels.  A server will be restarted if any of these three values change.

{{% notice note %}}
The servers will also be restarted if `restartVersion` is removed from the Domain (for example, if you had previously specified a value to cause a restart, then you remove that value after the previous restart has completed).
{{% /notice %}}


#### Restart all the servers in the domain

Set `restartVersion` at the domain level to a new value.

```
  kind: Domain
  metadata:
    name: domain1
  spec:
    restartVersion: "5"
    ...
```

#### Restart all the servers in the cluster

Set `restartVersion` at the cluster level to a new value.

```
  kind: Domain
  metadata:
    name: domain1
  spec:
    clusters:
    - clusterName : "cluster1"
      restartVersion: "5"
      maxUnavailable: 2
    ...
```

#### Restart the Administration Server

Set `restartVersion` at the `adminServer` level to a new value.

```
  kind: Domain
  metadata:
    name: domain1
  spec:
    adminServer:
      restartVersion: "5"
    ...
```

#### Restart a standalone or clustered Managed Server

Set `restartVersion` at the `managedServer` level to a new value.

```
  kind: Domain
  metadata:
    name: domain1
  spec:
    managedServers:
    - serverName: "standalone_server1"
      restartVersion: "1"
    - serverName: "cluster1_server1"
      restartVersion: "2"
    ...
```
#### Full domain restarts

To do a full domain restart, first shut down all servers (Administration Server and Managed Servers), taking the domain out of service,
then restart them.  Unlike rolling restarts, the operator cannot detect and initiate a full domain restart; you must always manually initiate it.

To manually initiate a full domain restart:

1. Change the domain-level `serverStartPolicy` on the Domain to `NEVER`.
```
  kind: Domain
  metadata:
    name: domain1
  spec:
    serverStartPolicy: "NEVER"
    ...
```

2. Wait for the operator to stop ALL the servers for that domain.

3. To restart the domain, set the domain level `serverStartPolicy` back to `IF_NEEDED`. Alternatively, you do not
have to specify the `serverStartPolicy` as the default value is `IF_NEEDED`.

```
  kind: Domain
  metadata:
    name: domain1
  spec:
    serverStartPolicy: "IF_NEEDED"
    ...
```

4. The operator will restart all the servers in the domain.

### Domain lifecycle sample scripts

See the [Life cycle sample scripts]({{< relref "/userguide/managing-domains/domain-lifecycle/scripts.md" >}})
for scripts that help with initiating domain life cycle operations.
