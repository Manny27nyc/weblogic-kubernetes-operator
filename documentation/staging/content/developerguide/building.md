---
title: "Building"
date: 2019-02-23T17:19:24-05:00
draft: false
description: "Learn about the operator build process."
weight: 3
---


The operator is built using [Apache Maven](http://maven.apache.org).  The build machine will also need to have Docker installed.

To build the operator, issue the following command in the project directory:

```shell
$ mvn clean install
```

This will compile the source files, build JAR files containing the compiled classes and libraries needed to run the operator, and will also execute all of the unit tests.

Contributions must conform to [coding and formatting standards]({{< relref "/developerguide/coding-standards.md" >}}).

#### Building the operator container image

These commands should be executed in the project root directory:

```shell
$ ./buildDockerImage.sh -t weblogic-kubernetes-operator:some-tag
```

Replace `<version>` with the version of the project found in the `pom.xml` file in the project root directory.

We recommend that you use a tag other than `latest`, to make it easy to distinguish your image.  In the example above, the tag could be the GitHub ID of the developer.

#### Running the operator from an IDE

The operator can be run from an IDE, which is useful for debugging.  In order to do so, the machine running the IDE must be configured with a Kubernetes configuration file in `~/.kube/config` or in a location pointed to by the `KUBECONFIG` environment variable.

Configure the IDE to run the class `oracle.kubernetes.operator.Main`.

You may need to create a directory called `/operator` on your machine.  Please be aware that the operator code is targeted to Linux, and although it will run fine on macOS, it will probably not run on other operating systems.  If you develop on another operating system, you should deploy the operator to a Kubernetes cluster and use remote debugging instead.

#### Running the operator in a Kubernetes cluster

If you're not running Kubernetes on your development machine, you'll need to make the container image available to a registry visible to your Kubernetes cluster.  Either `docker push` the image to a private registry or upload your image to a machine running Docker and Kubernetes as follows:

On your build machine:
```shell
$ docker save weblogic-kubernetes-operator:some-tag > operator.tar
```
```shell
$ scp operator.tar YOUR_USER@YOUR_SERVER:/some/path/operator.tar
```

On the Kubernetes server:
```shell
$ docker load < /some/path/operator.tar
```

Use the Helm charts to [install the operator]({{< relref "/userguide/managing-operators/installation.md" >}}).

If the operator's behavior or pod log is insufficient to diagnose and resolve failures, then you can connect a Java debugger to the operator using the [debugging options]({{< relref "/userguide/managing-operators/using-helm#debugging-options" >}}).
