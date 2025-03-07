---
title: "Azure Kubernetes Service (AKS)"
date: 2021-10-27T15:27:38-05:00
weight: 9
description: "Deploying WebLogic Server on Azure Kubernetes Service."
---

### Contents

- [Introduction](#introduction)
- [Basics](#basics)
- [Configure AKS cluster](#configure-aks-cluster)
- [TLS/SSL configuration](#tlsssl-configuration)
- [Networking](#networking)
- [DNS Configuration](#dns-configuration)
- [Database](#database)
- [Review + create](#review--create)


#### Introduction

{{< readfile file="/samples/azure-kubernetes-service/includes/aks-value-prop.txt" >}}

This document describes the Azure Marketplace offer that makes it easy to get started with WebLogic Server on Azure. The offer handles all the initial setup, creating the AKS cluster, container registry, WebLogic Kubernetes Operator installation, and domain creation using the model-in-image domain home source type. For complete details on domain home source types, see [Choose a domain home source type]({{< relref "/userguide/managing-domains/choosing-a-model/_index.md" >}}).

It is also possible to run the WebLogic Kubernetes Operator manually, without the aid of the Azure Marketplace offer.  The steps for doing so are documented in the sample [Azure Kubernetes Service]({{< relref "/samples/azure-kubernetes-service/_index.md" >}}).

#### Basics

Use the **Basics** blade to provide the basic configuration details for deploying an Oracle WebLogic Server configured cluster. To do this, enter the values for the fields listed in the following tables.

##### Project details


| Field | Description |
|-------|-------------|
| Subscription | Select a subscription to use for the charges accrued by this offer. You must have a valid active subscription associated with the Azure account that is currently logged in. If you don’t have it already, follow the steps described in [Associate or add an Azure subscription to your Azure Active Directory tenant](https://docs.microsoft.com/en-us/azure/active-directory/fundamentals/active-directory-how-subscriptions-associated-directory).| 
| Resource group | A resource group is a container that holds related resources for an Azure solution. The resource group includes those resources that you want to manage as a group. You decide which resources belong in a resource group based on what makes the most sense for your organization. If you have an existing resource group into which you want to deploy this solution, you can enter its name here; however, the resource group must have no pre-existing resources in it. Alternatively, you can click the **Create new**, and enter the name so that Azure creates a new resource group before provisioning the resources.  For more information about resource groups, see the [Azure documentation](https://docs.microsoft.com/en-us/azure/azure-resource-manager/resource-group-overview#resource-groups). |

##### Instance details

| Field | Description |
|-------|-------------|
| Region | Select an Azure region from the drop-down list. |

##### Credentials for WebLogic

| Field | Description |
|-------|-------------|
| Username for WebLogic Administrator | Enter a user name to access the WebLogic Server Administration Console which is started automatically after the provisioning. For more information about the WebLogic Server Administration Console, see [Overview of Administration Consoles](https://docs.oracle.com/pls/topic/lookup?ctx=en/middleware/standalone/weblogic-server/wlazu&id=INTRO-GUID-CC01963A-6073-4ABD-BC5F-5C509CA1EA90) in _Understanding Oracle WebLogic Server_. |
| Password for WebLogic Administrator | Enter a password to access the WebLogic Server Administration Console. |
| Confirm password | Re-enter the value of the preceding field. |
| Password for WebLogic Deploy Tooling runtime encrytion | The deployment uses Weblogic Deploy Tooling, including the capability to encrypt the model. This password is used for that encrption. For more information, see [Encrypt Model Tool](https://oracle.github.io/weblogic-deploy-tooling/userguide/tools/encrypt/) and the [WebLogic Deploy Tooling documentation](https://oracle.github.io/weblogic-deploy-tooling/userguide/tools/encrypt/).|
| Confirm password | Re-enter the value of the preceding field. |
| User assigned managed identity | The deployment requires a user-assigned managed identity with the **Contributor** or **Owner** role in the subscription referenced previously.  For more information, please see [Create, list, delete, or assign a role to a user-assigned managed identity using the Azure portal](https://docs.microsoft.com/en-us/azure/active-directory/managed-identities-azure-resources/how-to-manage-ua-identity-portal). |

##### Optional Basic Configuration

| Field | Description |
|-------|-------------|
| Accept defaults for optional configuration? | If you want to retain the default values for the optional configuration, such as **Name prefix for Managed Server**, **WebLogic Domain Name** and others, set the toggle button to **Yes**, and click **Next: Configure AKS cluster**. If you want to specify different values for the optional configuration, set the toggle button to **No**, and enter the following details. |
| Name prefix for Managed Server | Enter a prefix for the Managed Server name. |
| WebLogic Domain Name | Enter the name of the domain that will be created by the offer. |
| Maximum dynamic cluster size | The maximum size of the dynamic WebLogic cluster created. |
|Custom Java Options to start WebLogic Server | Java VM arguments passed to the invocation of WebLogic Server. For more information, see the [FAQ]({{< relref "/faq/resource-settings/_index.md" >}}). |
|Enable T3 tunneling for Administration Server| If selected, configure the necessary settings to enable T3 tunneling to the Administration Server.  For more details, see [External network access security]({{< relref "/security/domain-security/weblogic-channels.md" >}}).|
|Enable T3 tunneling for WebLogic cluster| If selected, configure the necessary settings to enable T3 tunneling to the WebLogic Server cluster.  For more details, see [External network access security]({{< relref "/security/domain-security/weblogic-channels.md" >}}).|

When you are satisfied with your selections, select **Next : Configure AKS cluster**.

#### Configure AKS cluster

Use the **Configure AKS Cluster** blade to configure fundamental details of how Oracle WebLogic Server runs on AKS. To do this, enter the values for the fields listed in the following tables.

##### Azure Kubernetes Service

In this section, you can configure some options about the AKS which will run WebLogic Server.

| Field | Description |
|-------|-------------|
|Create a new AKS cluster| If set to **Yes**, the deployment will create a new AKS cluster resource in the specified resource group. If set to **No**, you have the opportunity to select an existing AKS cluster, into which the deployment is configured. Note: the offer assumes the existing AKS cluster has no WebLogic related deployments. |
| Node count | The initial number of nodes in the AKS cluster. This value can be changed after deployment. For information, see [Scaling]({{< relref "/userguide/managing-domains/domain-lifecycle/scaling.md" >}}). |
| Node size | The default VM size is 2x Standard DSv2, 2 vcpus, 7 GB memory. If you want to select a different VM size, select **Change Size**, select the size from the list (for example, A3) on the Select a VM size page, and select **Select**. For more information about sizing the virtual machine, see the [Azure documentation on Sizes](https://docs.microsoft.com/en-us/azure/cloud-services/cloud-services-sizes-specs).|
|Enable Container insights| If selected, configure the necessary settings to integrate with Container insights. For more information, see [Container insights overview](https://aka.ms/wls-aks-container-insights).|
|Create Persistent Volume using Azure File share service|If selected, configure the necessary settings to mount a persistent volume to the nodes of the AKS cluster. For more information, see [Persistent storage]({{< relref "/userguide/managing-domains/persistent-storage/_index.md" >}}).|

##### Image selection

In this section, you can configure the image that is deployed using the model-in-image domain home source type. There are several options for the WebLogic image and the application image deployed therein.

| Field | Description |
|-------|-------------|
| Use a pre-existing WebLogic Server Docker image from Oracle Container Registry? | If set to **Yes**, the subsequent options are constrained to allow only selecting from a set of pre-existing WebLogic Server Docker images stored in the Oracle Container Registry. Note: the images in the Oracle Container Registry are unpatched. If set to **No**, the user may refer to a pre-existing Azure Container Registry, and must specify the Docker tag of the WebLogic Server image within that registry that will be used to create the domain. The specified image is assumed to be compatible with the WebLogic Kubernetes Operator. This allows the use of custom images, such as with a specific set patches (PSUs). For more about WebLogic Server images see [WebLogic Server images]({{< relref "/userguide/base-images/_index.md" >}}).|
|Create a new Azure Container Registry to store application images?|If set to **Yes**, the offer will create a new Azure Container Registry (ACR) to hold the Docker images for use in the deployment.  If set to **No**, you must specify an existing ACR. In this case, you must be sure the selected ACR has the admin account enabled. For details, please see [Admin account](https://docs.microsoft.com/en-us/azure/container-registry/container-registry-authentication?WT.mc_id=Portal-Microsoft_Azure_CreateUIDef&tabs=azure-cli#admin-account). |
| Select existing ACR instance | This option is shown only if **Use a pre-existing WebLogic Server Docker image from Oracle Container Registry?** is set to **No**. If visible, select an existing Acure Container Registry instance. |
| Please provide the image path | This option is shown only if **Use a pre-existing WebLogic Server Docker image from Oracle Container Registry?** is set to **No**. If visible, the value must be a fully qualified Docker tag of an image within the specified ACR. |
| Username for Oracle Single Sign-On authentication | The Oracle Single Sign-on account user name for which the Terms and Restrictions for the selected WebLogic Server image have been accepted. |
| Password for Oracle Single Sign-On authentication | The password for that account. | 
| Confirm password | Re-enter the value of the preceding field. |
| Select WebLogic Server Docker tag | Select one of the supported images. |

##### Java EE Application

In this section you can deploy a Java EE Application along with the WebLogic Server deployment.

| Field | Description |
|-------|-------------|
| Deploy your application package? | If set to **Yes**, you must specify a Java EE WAR, EAR, or JAR file suitable for deployment with the selected version of WebLogic Server. If set to **No**, no application is deployed.| 
| Application package (.war,.ear,.jar) | With the **Browse** button, you can select a file from a pre-existing Azure Storage Account and Storage Container within that account.  To learn how to create a Storage Account and Container, see [Create a storage account](https://docs.microsoft.com/en-us/azure/storage/common/storage-account-create?tabs=azure-portal). |
| Fail deployment if application does not become ACTIVE. | If selected, the deployment will wait for the deployed application to reach the **ACTIVE** state and fail the deployment if it does not. For more details, see the [Oracle documentation](https://aka.ms/wls-aks-deployment-state). |
| Number of WebLogic Managed Server replicas | The initial value of the `replicas` field of the Domain. For information, see [Scaling]({{< relref "/userguide/managing-domains/domain-lifecycle/scaling.md" >}}). |

When you are satisfied with your selections, select **Next : TLS/SSL configuration**.

#### TLS/SSL configuration

With the **TLS/SSL configuration** blade, you can configure Oracle WebLogic Server Administration Console on a secure HTTPS port, with your own SSL certificate provided by a Certifying Authority (CA).

Select **Yes** or **No** for the option **Configure WebLogic Server Administration Console, Remote Console, cluster and custom T3 channel to use HTTPS (Secure) ports, with your own TLS/SSL certificate.** based on your preference. If you select **No**, you don't have to provide any details, and can proceed by selecting **Next : Networking** >. If you select **Yes**, you can choose to provide the required configuration details by either uploading existing keystores or by using keystores stored in Azure Key Vault.

If you want to upload existing keystores, select **Upload existing KeyStores** for the option **How would you like to provide required configuration**, and enter the values for the fields listed in the following table.

##### TLS/SSL configuration settings

| Field | Description |
|-------|-------------|
|Identity KeyStore Data file(.jks,.p12)| Upload a custom identity keystore data file by doing the following: {{< line_break >}} 1. Click on the file icon. {{< line_break >}} 2. Navigate to the folder where the identity keystore file resides, and select the file. {{< line_break >}} 3. Click Open. |
| Password | Enter the passphrase for the custom identity keystore. |
| Confirm password | Re-enter the value of the preceding field. |
| The Identity KeyStore type (JKS,PKCS12) | Select the type of custom identity keystore. The supported values are JKS and PKCS12. |
| The alias of the server's private key within the Identity KeyStore | Enter the alias for the private key. |
| The passphrase for the server's private key within the Identity KeyStore | Enter the passphrase for the private key. |
| Confirm passphrase | Re-enter the value of the preceding field. |
| Trust KeyStore Data file(.jks,.p12) | Upload a custom trust keystore data file by doing the following: {{< line_break >}} 1. Click on the file icon. {{< line_break >}} 2. Navigate to the folder where the identity keystore file resides, and select the file. {{< line_break >}} 3. Click Open. |
| Password | Enter the password for the custom trust keystore. |
| Confirm password | Re-enter the value of the preceding field. |
| The Identity KeyStore type (JKS,PKCS12) | Select the type of custom trust keystore. The supported values are JKS and PKCS12. |

When you are satisfied with your selections, select **Next : Networking**.

#### Networking

Use this blade to configure options for load balancing and ingress controller.

##### Standard Load Balancer service

Selecting **Yes** here will cause the offer to provision the Azure Load Balancer as a Kubernetes load balancer service. Note, you must select **Yes** and provide further configuration when T3 tunneling is enabled on the Basics blade. For more information on the Standard Load Balancer see [Use a public Standard Load Balancer in Azure Kubernetes Service (AKS)](https://aka.ms/wls-aks-standard-load-balancer).  You can still deploy an Azure Application Gateway even if you select **No** here.

If you select **Yes**, you have the option of configuring the Load Balancer as an internal Load Balancer.  For more information on Azure internal load balancers see [Use an internal load balancer with Azure Kubernetes Service (AKS)](https://aka.ms/wls-aks-internal-load-balancer).

If you select **Yes**, you must fill in the following table to map the services to load balancer ports.

**Service name prefix** column:

You can fill in any valid value in this column.

**Target** and **Port** column:

The current offer has some restrictions on the T3 ports.

* For a **Target** value of **admin-server-t3**, you must use port 7005.
* For a **Target** value of **cluster-1-t3**, you must use port 8011.

For the non-T3 ports, the recommended values are the usual 7001 for the **admin-server** and 8001 for the **cluster-1**.

##### Application Gateway Ingress Controller

In this section, you can create an Azure Application Gateway instance as the ingress controller of your WebLogic Server. This Application Gateway is pre-configured for end-to-end-SSL with TLS termination at the gateway using the provided SSL certificate and load balances across your cluster.

Select **Yes** or **No** for the option **Connect to Azure Application Gateway?** based on your preference. If you select **No**, you don't have to provide any details, and can proceed by selecting **Next : DNS Configuration >**. If you select **Yes**, you must specify the details required for the Application Gateway integration by entering the values for the fields as described next.

You must select one of the following three options, each described in turn.

* Upload a TLS/SSL certificate: Upload the pre-signed certificate now.
* Identify an Azure Key Vault: The Key Vault must already contain the certificate and its password stored as secrets.
* Generate a self-signed front-end certificate: Generate a self-signed front-end certificate and apply it during deployment.

**Upload a TLS/SSL certificate**

| Field | Description |
|-------|-------------|
| Frontend TLS/SSL certificate(.pfx) | For information on how to create a certificate in PFX format, see [Overview of TLS termination and end to end TLS with Application Gateway](https://docs.microsoft.com/en-us/azure/application-gateway/ssl-overview). |
| Password | The password for the certificate |
| Confirm password | Re-enter the value of the preceding field. |
| Trusted root certificate(.cer, .cert) | A trusted root certificate is required to allow back-end instances in the application gateway. The root certificate is a Base-64 encoded X.509(.CER) format root certificate. |
| Service Principal | A Base64 encoded JSON string of a service principal for the selected subscription. You can generate one with command `az ad sp create-for-rbac --sdk-auth | base64 -w0`. For more information, see [Create a service principal](https://docs.microsoft.com/en-us/cli/azure/create-an-azure-service-principal-azure-cli#create-a-service-principal). |

**Identify an Azure Key Vault**

| Field | Description |
|-------|-------------|
| Resource group name in current subscription containing the KeyVault | Enter the name of the Resource Group containing the Key Vault that stores the application gateway SSL certificate and the data required for SSL termination. |
| Name of the Azure KeyVault containing secrets for the Certificate for SSL Termination | Enter the name of the Azure Key Vault that stores the application gateway SSL certificate and the data required for SSL termination. |
| The name of the secret in the specified KeyVault whose value is the SSL Certificate Data | Enter the name of the Azure Key Vault secret that holds the value of the SSL certificate data. |
| The name of the secret in the specified KeyVault whose value is the password for the SSL Certificate | Enter the name of the Azure Key Vault secret that holds the value of the SSL certificate password. |
| Service Principal | A Base64 encoded JSON string of a service principal for the selected subscription. You can generate one with command `az ad sp create-for-rbac --sdk-auth | base64 -w0`. For more information, see [Create a service principal](https://docs.microsoft.com/en-us/cli/azure/create-an-azure-service-principal-azure-cli#create-a-service-principal). |

**Generate a self-signed frontend certificate**

| Field | Description |
|-------|-------------|
| Trusted root certificate(.cer, .cert) | A trusted root certificate is required to allow back-end instances in the application gateway. The root certificate is a Base-64 encoded X.509(.CER) format root certificate. |
| Service Principal | A Base64 encoded JSON string of a service principal for the selected subscription. You can generate one with command `az ad sp create-for-rbac --sdk-auth | base64 -w0`. For more information, see [Create a service principal](https://docs.microsoft.com/en-us/cli/azure/create-an-azure-service-principal-azure-cli#create-a-service-principal). |

Regardless of how you provide the certificates, there are several other options when configuring the Application Gateway, as described next.

| Field | Description |
|-------|-------------|
|Enable cookie based affinity | Select this box to enable cookie based affinity (sometimes called "sticky sessions"). For more information, see [Enable Cookie based affinity with an Application Gateway](https://docs.microsoft.com/en-us/azure/application-gateway/ingress-controller-cookie-affinity). |
| Create ingress for Administration Console. | Select **Yes** to create an ingress for the Administration Console with the path `/console`. |
| Create ingress for WebLogic Remote Console. | Select **Yes** to create an ingress for the Remote Console with the path `/remoteconsole`. |

When you are satisfied with your selections, select **Next : DNS Configuration**.

#### DNS Configuration

With the **DNS Configuration** blade, you can provision the Oracle WebLogic Server Administration Console using a custom DNS name.

Select **Yes** or **No** for the option **Configure Custom DNS Alias?** based on your preference. If you select **No**, you don't have to provide any details, and can proceed by selecting **Next : Database >**. If you select **Yes**, you must choose either to configure a custom DNS alias based on an existing Azure DNS zone, or create an Azure DNS zone and a custom DNS alias. This can be done by selecting **Yes** or **No** for the option **Use an existing Azure DNS Zone**.

{{% notice note %}}
For more information about the DNS zones, see [Overview of DNS zones and records](https://docs.microsoft.com/en-us/azure/dns/dns-zones-records).
{{% /notice %}}

If you choose to configure a custom DNS alias based on an existing Azure DNS zone, by selecting **Yes** for the option **Use an existing Azure DNS Zone**, you must specify the DNS configuration details by entering the values for the fields listed in the following table.

| Field | Description |
|-------|-------------|
| DNS Zone Name	| Enter the DNS zone name. |
| Name of the resource group contains the DNS Zone in current subscription | Enter the name of the resource group that contains the DNS zone in the current subscription. |
| Label for Oracle WebLogic Server Administration Console | Enter a label to generate a sub-domain of the Oracle WebLogic Server Administration Console. For example, if the domain is `mycompany.com` and the sub-domain is `admin`, then the WebLogic Server Administration Console URL will be `admin.mycompany.com`. |
| Label for WebLogic Cluster | Specify a label to generate subdomain of WebLogic Cluster. |

If you choose to create an Azure DNS zone and a custom DNS alias, by selecting **No** for the option **Use an existing Azure DNS Zone**, you must specify the values for the following fields:

* DNS Zone Name
* Label for Oracle WebLogic Server Administration Console
* Label for WebLogic Cluster

See the preceding table for the description of these fields.

{{% notice note %}}
In the case of creating an Azure DNS zone and a custom DNS alias, you must perform the DNS domain delegation at your DNS registry post deployment. See [Delegation of DNS zones with Azure DNS](https://docs.microsoft.com/en-us/azure/dns/dns-domain-delegation).
{{% /notice %}}

When you are satisfied with your selections, select **Next : Database**.

#### Database

Use the Database blade to configure Oracle WebLogic Server to connect to an existing database. Select **Yes** or **No** for the option **Connect to Database?** based on your preference. If you select **No**, you don't have to provide any details, and can proceed by clicking **Next : Review + create >**. If you select **Yes**, you must specify the details of your database by entering the values for the fields listed in the following table.

| Field | Description |
|-------|-------------|
| Choose database type | Select an existing database that you want Oracle WebLogic Server to connect to, from the drop-down list. The available options are:{{< line_break >}}{{< line_break >}} • Azure Database for PostgreSQL {{< line_break >}} • Oracle Database {{< line_break >}} • Azure SQL {{< line_break >}} • Other |
| JNDI Name	| Enter the JNDI name for your database JDBC connection. |
| DataSource Connection String | Enter the JDBC connection string for your database. For information about obtaining the JDBC connection string, see [Obtain the JDBC Connection String for Your Database](https://docs.oracle.com/en/middleware/standalone/weblogic-server/wlazu/obtain-jdbc-connection-string-your-database.html#GUID-6523B742-EB68-4AF4-A85C-8B4561C133F3). |
| Global transactions protocol | Determines the transaction protocol (global transaction processing behavior) for the data source. For more information, see [JDBC Data Source Transaction Options](https://docs.oracle.com/en/middleware/standalone/weblogic-server/14.1.1.0/jdbca/transactions.html#GUID-4C929E67-5FD7-477B-A749-1EA0F4FD25D4). **IMPORTANT: The correct value for this parameter depends on the selected database type. For PostgreSQL, select EmulateTwoPhaseCommit**. |
| Database Username	| Enter the user name of your database. |
| Database Password	| Enter the password for the database user. |
| Confirm password | Re-enter the value of the preceding field. |

If you select **Other** as the database type, there are some additional values you must provide. WebLogic Server provides support for application data access to any database using a JDBC-compliant driver. Refer to the [documentation for driver requirements](https://aka.ms/wls-aks-dbdriver).

| Field | Description |
|-------|-------------|
| DataSource driver (.jar) | Use the **Browse** button to upload the JAR file for the JDBC driver to a storage container. To learn how to create a Storage Account and Container, see [Create a storage account](https://docs.microsoft.com/en-us/azure/storage/common/storage-account-create?tabs=azure-portal). |
| DataSource driver name | The fully qualified Java class name of the JDBC driver. |
| Test table name | The name of the database table to use when testing physical database connections. This value depends on the specified database. Some suggested values include the following. {{< line_break >}}{{< line_break >}} • For Oracle, use `SQL ISVALID`. {{< line_break >}} • For PostgreSQL, SQL Server and MariaDB use `SQL SELECT 1`. {{< line_break >}} • For Informix use `SYSTABLES`.|

When you are satisfied with your selections, select **Next : Review + create**.

#### Review + create

In the **Review + create blade**, review the details you provided for deploying Oracle WebLogic Server on AKS. If you want to make changes to any of the fields, click **< previous** or click on the respective blade and update the details.

If you want to use this template to automate the deployment, download it by selecting **Download a template for automation**.

Click **Create** to create this offer. This process may take 30 to 60 minutes.

