// Copyright (c) 2020, 2021, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1LocalObjectReference;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1SecretReference;
import oracle.weblogic.domain.Cluster;
import oracle.weblogic.domain.Configuration;
import oracle.weblogic.domain.Domain;
import oracle.weblogic.domain.DomainSpec;
import oracle.weblogic.domain.Istio;
import oracle.weblogic.domain.Model;
import oracle.weblogic.domain.OnlineUpdate;
import oracle.weblogic.domain.ServerPod;
import oracle.weblogic.kubernetes.annotations.IntegrationTest;
import oracle.weblogic.kubernetes.annotations.Namespaces;
import oracle.weblogic.kubernetes.logging.LoggingFacade;
import oracle.weblogic.kubernetes.utils.ExecResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static oracle.weblogic.kubernetes.TestConstants.ADMIN_PASSWORD_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.ADMIN_USERNAME_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.DOMAIN_API_VERSION;
import static oracle.weblogic.kubernetes.TestConstants.K8S_NODEPORT_HOST;
import static oracle.weblogic.kubernetes.TestConstants.MII_BASIC_IMAGE_NAME;
import static oracle.weblogic.kubernetes.TestConstants.MII_BASIC_IMAGE_TAG;
import static oracle.weblogic.kubernetes.TestConstants.OCIR_SECRET_NAME;
import static oracle.weblogic.kubernetes.TestConstants.WEBLOGIC_IMAGE_TAG;
import static oracle.weblogic.kubernetes.TestConstants.WEBLOGIC_SLIM;
import static oracle.weblogic.kubernetes.actions.ActionConstants.ITTESTS_DIR;
import static oracle.weblogic.kubernetes.actions.ActionConstants.MODEL_DIR;
import static oracle.weblogic.kubernetes.actions.ActionConstants.RESOURCE_DIR;
import static oracle.weblogic.kubernetes.actions.TestActions.addLabelsToNamespace;
import static oracle.weblogic.kubernetes.actions.TestActions.patchDomainResourceWithNewIntrospectVersion;
import static oracle.weblogic.kubernetes.utils.ApplicationUtils.checkAppUsingHostHeader;
import static oracle.weblogic.kubernetes.utils.CommonMiiTestUtils.replaceConfigMapWithModelFiles;
import static oracle.weblogic.kubernetes.utils.CommonMiiTestUtils.verifyIntrospectorRuns;
import static oracle.weblogic.kubernetes.utils.CommonMiiTestUtils.verifyPodIntrospectVersionUpdated;
import static oracle.weblogic.kubernetes.utils.CommonMiiTestUtils.verifyPodsNotRolled;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.checkPodReadyAndServiceExists;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.isWebLogicPsuPatchApplied;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.startPortForwardProcess;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.stopPortForwardProcess;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.withStandardRetryPolicy;
import static oracle.weblogic.kubernetes.utils.ConfigMapUtils.createConfigMapAndVerify;
import static oracle.weblogic.kubernetes.utils.DeployUtil.deployToClusterUsingRest;
import static oracle.weblogic.kubernetes.utils.DomainUtils.createDomainAndVerify;
import static oracle.weblogic.kubernetes.utils.ExecCommand.exec;
import static oracle.weblogic.kubernetes.utils.FileUtils.generateFileFromTemplate;
import static oracle.weblogic.kubernetes.utils.ImageUtils.createOcirRepoSecret;
import static oracle.weblogic.kubernetes.utils.IstioUtils.createAdminServer;
import static oracle.weblogic.kubernetes.utils.IstioUtils.deployHttpIstioGatewayAndVirtualservice;
import static oracle.weblogic.kubernetes.utils.IstioUtils.deployIstioDestinationRule;
import static oracle.weblogic.kubernetes.utils.IstioUtils.getIstioHttpIngressPort;
import static oracle.weblogic.kubernetes.utils.IstioUtils.isLocalHostBindingsEnabled;
import static oracle.weblogic.kubernetes.utils.OperatorUtils.installAndVerifyOperator;
import static oracle.weblogic.kubernetes.utils.PodUtils.getPodCreationTime;
import static oracle.weblogic.kubernetes.utils.PodUtils.setPodAntiAffinity;
import static oracle.weblogic.kubernetes.utils.SecretUtils.createSecretWithUsernamePassword;
import static oracle.weblogic.kubernetes.utils.ThreadSafeLogger.getLogger;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Test istio enabled WebLogic Domain in mii model")
@IntegrationTest
class ItIstioMiiDomain {

  private static String opNamespace = null;
  private static String domainNamespace = null;

  private String domainUid = "istio-mii";
  private String configMapName = "dynamicupdate-istio-configmap";
  private final String clusterName = "cluster-1"; // do not modify
  private final String adminServerPodName = domainUid + "-admin-server";
  private final String managedServerPrefix = domainUid + "-managed-server";
  private final String workManagerName = "newWM";
  private final int replicaCount = 2;
  
  private static LoggingFacade logger = null;

  /**
   * Install Operator.
   * @param namespaces list of namespaces created by the IntegrationTestWatcher
  */
  @BeforeAll
  public static void initAll(@Namespaces(2) List<String> namespaces) {
    logger = getLogger();

    // get a new unique opNamespace
    logger.info("Assign unique namespace for Operator");
    assertNotNull(namespaces.get(0), "Namespace list is null");
    opNamespace = namespaces.get(0);

    logger.info("Assign unique namespace for Domain");
    assertNotNull(namespaces.get(1), "Namespace list is null");
    domainNamespace = namespaces.get(1);

    // Label the domain/operator namespace with istio-injection=enabled
    Map<String, String> labelMap = new HashMap();
    labelMap.put("istio-injection", "enabled");
    assertDoesNotThrow(() -> addLabelsToNamespace(domainNamespace,labelMap));
    assertDoesNotThrow(() -> addLabelsToNamespace(opNamespace,labelMap));

    // install and verify operator
    installAndVerifyOperator(opNamespace, domainNamespace);
  }

  /**
   * Create a domain using model-in-image model.
   * Add istio configuration with default readinessPort.
   * Do not add any AdminService under AdminServer configuration.
   * Deploy istio gateways and virtual service.
   *
   * Verify server pods are in ready state and services are created.
   * Verify WebLogic console is accessible thru istio ingress port.
   * Verify WebLogic console is accessible thru kubectl forwarded port.
   * Deploy a web application thru istio http ingress port using REST api.  
   * Access web application thru istio http ingress port using curl.
   * 
   * Create a configmap with a sparse model file to add a new workmanager 
   * with custom min threads constraint and a max threads constraint
   * Patch the domain resource with the configmap.
   * Update the introspect version of the domain resource.
   * Verify rolling restart of the domain by comparing PodCreationTimestamp
   * before and after rolling restart.
   * Verify new work manager is configured.
   */
  @Test
  @DisplayName("Create WebLogic Domain with mii model with istio")
  void testIstioModelInImageDomain() {

    // Create the repo secret to pull the image
    // this secret is used only for non-kind cluster
    createOcirRepoSecret(domainNamespace);

    // create secret for admin credentials
    logger.info("Create secret for admin credentials");
    String adminSecretName = "weblogic-credentials";
    assertDoesNotThrow(() -> createSecretWithUsernamePassword(
                                    adminSecretName,
                                    domainNamespace,
                                    "weblogic",
                                    "welcome1"),
        String.format("createSecret failed for %s", adminSecretName));

    // create encryption secret
    logger.info("Create encryption secret");
    String encryptionSecretName = "encryptionsecret";
    assertDoesNotThrow(() -> createSecretWithUsernamePassword(
                                      encryptionSecretName,
                                      domainNamespace,
                            "weblogicenc",
                            "weblogicenc"),
                    String.format("createSecret failed for %s", encryptionSecretName));

    // create WDT config map without any files
    createConfigMapAndVerify(configMapName, domainUid, domainNamespace, Collections.EMPTY_LIST);
    // create the domain object
    Domain domain = createDomainResource(domainUid,
                                      domainNamespace,
                                      adminSecretName,
                                      OCIR_SECRET_NAME,
                                      encryptionSecretName,
                                      replicaCount,
                              MII_BASIC_IMAGE_NAME + ":" + MII_BASIC_IMAGE_TAG,
                              configMapName);

    // create model in image domain
    createDomainAndVerify(domain, domainNamespace);

    logger.info("Check admin service {0} is created in namespace {1}",
        adminServerPodName, domainNamespace);
    checkPodReadyAndServiceExists(adminServerPodName, domainUid, domainNamespace);
    // check managed server services created
    for (int i = 1; i <= replicaCount; i++) {
      logger.info("Check managed service {0} is created in namespace {1}",
          managedServerPrefix + i, domainNamespace);
      checkPodReadyAndServiceExists(managedServerPrefix + i, domainUid, domainNamespace);
    }

    String clusterService = domainUid + "-cluster-" + clusterName + "." + domainNamespace + ".svc.cluster.local";

    Map<String, String> templateMap  = new HashMap();
    templateMap.put("NAMESPACE", domainNamespace);
    templateMap.put("DUID", domainUid);
    templateMap.put("ADMIN_SERVICE",adminServerPodName);
    templateMap.put("CLUSTER_SERVICE", clusterService);

    Path srcHttpFile = Paths.get(RESOURCE_DIR, "istio", "istio-http-template.yaml");
    Path targetHttpFile = assertDoesNotThrow(
        () -> generateFileFromTemplate(srcHttpFile.toString(), "istio-http.yaml", templateMap));
    logger.info("Generated Http VS/Gateway file path is {0}", targetHttpFile);
    
    boolean deployRes = assertDoesNotThrow(
        () -> deployHttpIstioGatewayAndVirtualservice(targetHttpFile)); 
    assertTrue(deployRes, "Failed to deploy Http Istio Gateway/VirtualService");

    Path srcDrFile = Paths.get(RESOURCE_DIR, "istio", "istio-dr-template.yaml");
    Path targetDrFile = assertDoesNotThrow(
        () -> generateFileFromTemplate(srcDrFile.toString(), "istio-dr.yaml", templateMap));
    logger.info("Generated DestinationRule file path is {0}", targetDrFile);

    deployRes = assertDoesNotThrow(
        () -> deployIstioDestinationRule(targetDrFile));
    assertTrue(deployRes, "Failed to deploy Istio DestinationRule");

    int istioIngressPort = getIstioHttpIngressPort();
    logger.info("Istio Ingress Port is {0}", istioIngressPort);

    // We can not verify Rest Management console thru Adminstration NodePort 
    // in istio, as we can not enable Adminstration NodePort
    if (!WEBLOGIC_SLIM) {
      String consoleUrl = "http://" + K8S_NODEPORT_HOST + ":" + istioIngressPort + "/console/login/LoginForm.jsp";
      boolean checkConsole = 
          checkAppUsingHostHeader(consoleUrl, domainNamespace + ".org");
      assertTrue(checkConsole, "Failed to access WebLogic console");
      logger.info("WebLogic console is accessible");
      String localhost = "localhost";
      String forwardPort = 
           startPortForwardProcess(localhost, domainNamespace, 
           domainUid, 7001);
      assertNotNull(forwardPort, "port-forward command fails to assign local port");
      logger.info("Forwarded local port is {0}", forwardPort);
      consoleUrl = "http://" + localhost + ":" + forwardPort + "/console/login/LoginForm.jsp";
      checkConsole = 
          checkAppUsingHostHeader(consoleUrl, domainNamespace + ".org");
      assertTrue(checkConsole, "Failed to access WebLogic console thru port-forwarded port");
      logger.info("WebLogic console is accessible thru port forwarding");
      stopPortForwardProcess(domainNamespace);
    } else {
      logger.info("Skipping WebLogic console in WebLogic slim image");
    }

    if (isWebLogicPsuPatchApplied()) {
      String curlCmd2 = "curl -j -sk --show-error --noproxy '*' "
          + " -H 'Host: " + domainNamespace + ".org'"
          + " --user " + ADMIN_USERNAME_DEFAULT + ":" + ADMIN_PASSWORD_DEFAULT
          + " --url http://" + K8S_NODEPORT_HOST + ":" + istioIngressPort
          + "/management/weblogic/latest/domainRuntime/domainSecurityRuntime?" 
          + "link=none";

      ExecResult result = null;
      logger.info("curl command {0}", curlCmd2);
      result = assertDoesNotThrow(
        () -> exec(curlCmd2, true));

      if (result.exitValue() == 0) {
        logger.info("curl command returned {0}", result.toString());
        assertTrue(result.stdout().contains("SecurityValidationWarnings"), 
                "Could not access the Security Warning Tool page");
        assertTrue(!result.stdout().contains("minimum of umask 027"), "umask warning check failed");
        logger.info("No minimum umask warning reported");
      } else {
        assertTrue(false, "Curl command failed to get DomainSecurityRuntime");
      }
    } else {
      logger.info("Skipping Security warning check, since Security Warning tool "
            + " is not available in the WLS Release {0}", WEBLOGIC_IMAGE_TAG);
    } 

    Path archivePath = Paths.get(ITTESTS_DIR, "../operator/integration-tests/apps/testwebapp.war");
    ExecResult result = null;
    result = deployToClusterUsingRest(K8S_NODEPORT_HOST, 
        String.valueOf(istioIngressPort),
        ADMIN_USERNAME_DEFAULT, ADMIN_PASSWORD_DEFAULT, 
        clusterName, archivePath, domainNamespace + ".org", "testwebapp");
    assertNotNull(result, "Application deployment failed");
    logger.info("Application deployment returned {0}", result.toString());
    assertEquals("202", result.stdout(), "Deployment didn't return HTTP status code 202");

    String url = "http://" + K8S_NODEPORT_HOST + ":" + istioIngressPort + "/testwebapp/index.jsp";
    logger.info("Application Access URL {0}", url);
    boolean checkApp = checkAppUsingHostHeader(url, domainNamespace + ".org");
    assertTrue(checkApp, "Failed to access WebLogic application");

    //Verify the dynamic configuration update
    LinkedHashMap<String, OffsetDateTime> pods = new LinkedHashMap<>();
    // get the creation time of the admin server pod before patching
    OffsetDateTime adminPodCreationTime = getPodCreationTime(domainNamespace, adminServerPodName);
    pods.put(adminServerPodName, getPodCreationTime(domainNamespace, adminServerPodName));
    // get the creation time of the managed server pods before patching
    for (int i = 1; i <= replicaCount; i++) {
      pods.put(managedServerPrefix + i, getPodCreationTime(domainNamespace, managedServerPrefix + i));
    }
    for (int i = 1; i <= replicaCount; i++) {
      pods.put(managedServerPrefix + i, getPodCreationTime(domainNamespace, managedServerPrefix + i));
    }

    replaceConfigMapWithModelFiles(configMapName, domainUid, domainNamespace,
        Arrays.asList(MODEL_DIR + "/model.config.wm.yaml"), withStandardRetryPolicy);

    String introspectVersion = patchDomainResourceWithNewIntrospectVersion(domainUid, domainNamespace);

    verifyIntrospectorRuns(domainUid, domainNamespace);

    String wmRuntimeUrl  = "http://" + K8S_NODEPORT_HOST + ":"  
           + istioIngressPort + "/management/weblogic/latest/domainRuntime"
           + "/serverRuntimes/managed-server1/applicationRuntimes"
           + "/testwebapp/workManagerRuntimes/newWM/"
           + "maxThreadsConstraintRuntime ";

    boolean checkWm =
          checkAppUsingHostHeader(wmRuntimeUrl, domainNamespace + ".org");
    assertTrue(checkWm, "Failed to access WorkManagerRuntime");
    logger.info("Found new work manager runtime");

    verifyPodsNotRolled(domainNamespace, pods);
    verifyPodIntrospectVersionUpdated(pods.keySet(), introspectVersion, domainNamespace);
  }

  private Domain createDomainResource(String domainUid, String domNamespace, 
           String adminSecretName, String repoSecretName, 
           String encryptionSecretName, int replicaCount, 
           String miiImage, String configmapName) {

    // create the domain CR
    Domain domain = new Domain()
        .apiVersion(DOMAIN_API_VERSION)
        .kind("Domain")
        .metadata(new V1ObjectMeta()
            .name(domainUid)
            .namespace(domNamespace))
        .spec(new DomainSpec()
            .domainUid(domainUid)
            .domainHomeSourceType("FromModel")
            .image(miiImage)
            .addImagePullSecretsItem(new V1LocalObjectReference()
                .name(repoSecretName))
            .webLogicCredentialsSecret(new V1SecretReference()
                .name(adminSecretName)
                .namespace(domNamespace))
            .includeServerOutInPodLog(true)
            .serverStartPolicy("IF_NEEDED")
            .serverPod(new ServerPod()
                .addEnvItem(new V1EnvVar()
                    .name("JAVA_OPTIONS")
                    .value("-Dweblogic.StdoutDebugEnabled=false"))
                .addEnvItem(new V1EnvVar()
                    .name("USER_MEM_ARGS")
                    .value("-Djava.security.egd=file:/dev/./urandom ")))
            .adminServer(createAdminServer())
            .addClustersItem(new Cluster()
                .clusterName(clusterName)
                .replicas(replicaCount)
                .serverStartState("RUNNING"))
            .configuration(new Configuration()
                    .istio(new Istio()
                         .enabled(Boolean.TRUE)
                         .readinessPort(8888)
                         .localhostBindingsEnabled(isLocalHostBindingsEnabled()))
                     .model(new Model()
                         .domainType("WLS")
                         .configMap(configmapName)
                         .onlineUpdate(new OnlineUpdate().enabled(true))
                         .runtimeEncryptionSecret(encryptionSecretName))
            .introspectorJobActiveDeadlineSeconds(300L)));
    setPodAntiAffinity(domain);
    return domain;
  }
}
