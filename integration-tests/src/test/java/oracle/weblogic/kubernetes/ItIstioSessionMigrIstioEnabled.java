// Copyright (c) 2021, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import oracle.weblogic.domain.Domain;
import oracle.weblogic.kubernetes.annotations.IntegrationTest;
import oracle.weblogic.kubernetes.annotations.Namespaces;
import oracle.weblogic.kubernetes.logging.LoggingFacade;
import oracle.weblogic.kubernetes.utils.ExecResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static oracle.weblogic.kubernetes.TestConstants.ADMIN_SERVER_NAME_BASE;
import static oracle.weblogic.kubernetes.TestConstants.MANAGED_SERVER_NAME_BASE;
import static oracle.weblogic.kubernetes.TestConstants.OCIR_SECRET_NAME;
import static oracle.weblogic.kubernetes.actions.ActionConstants.MODEL_DIR;
import static oracle.weblogic.kubernetes.actions.TestActions.addLabelsToNamespace;
import static oracle.weblogic.kubernetes.actions.TestActions.execCommand;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.checkServiceExists;
import static oracle.weblogic.kubernetes.utils.ConfigMapUtils.createConfigMapAndVerify;
import static oracle.weblogic.kubernetes.utils.DomainUtils.createDomainAndVerify;
import static oracle.weblogic.kubernetes.utils.ImageUtils.createMiiImageAndVerify;
import static oracle.weblogic.kubernetes.utils.ImageUtils.createOcirRepoSecret;
import static oracle.weblogic.kubernetes.utils.ImageUtils.dockerLoginAndPushImageToRegistry;
import static oracle.weblogic.kubernetes.utils.IstioUtils.createIstioDomainResource;
import static oracle.weblogic.kubernetes.utils.OperatorUtils.installAndVerifyOperator;
import static oracle.weblogic.kubernetes.utils.PodUtils.checkPodReady;
import static oracle.weblogic.kubernetes.utils.SecretUtils.createSecretWithUsernamePassword;
import static oracle.weblogic.kubernetes.utils.ThreadSafeLogger.getLogger;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

@DisplayName("Test WLS Session Migration via istio enabled")
@IntegrationTest
class ItIstioSessionMigrIstioEnabled {

  private static String opNamespace = null;
  private static String domainNamespace = null;

  // constants for creating domain image using model in image
  private static final String SESSMIGR_MODEL_FILE = "model.sessmigr.yaml";
  private static final String SESSMIGR_IMAGE_NAME = "sessmigr-mii-image";

  // constants for web service
  private static final String SESSMIGR_APP_NAME = "sessmigr-app";
  private static final String SESSMIGR_APP_WAR_NAME = "sessmigr-war";
  private static final int SESSION_STATE = 4;
  private static Map<String, String> httpAttrMap;

  // constants for operator and WebLogic domain
  private static String domainUid = "sessmigr-domain-1";
  private static String clusterName = "cluster-1";
  private static String adminServerPodName = domainUid + "-" + ADMIN_SERVER_NAME_BASE;
  private static String managedServerPrefix = domainUid + "-" + MANAGED_SERVER_NAME_BASE;
  private static int managedServerPort = 7100;
  private String configMapName = "istio-configmap";
  private static int replicaCount = 2;

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

    // map to save HTTP response data
    httpAttrMap = new HashMap<String, String>();
    httpAttrMap.put("sessioncreatetime", "(.*)sessioncreatetime>(.*)</sessioncreatetime(.*)");
    httpAttrMap.put("sessionid", "(.*)sessionid>(.*)</sessionid(.*)");
    httpAttrMap.put("primary", "(.*)primary>(.*)</primary(.*)");
    httpAttrMap.put("secondary", "(.*)secondary>(.*)</secondary(.*)");
    httpAttrMap.put("count", "(.*)countattribute>(.*)</countattribute(.*)");
  }

  /**
   * The test sends a HTTP request to set http session state(count number), get the primary and secondary server name,
   * session create time and session state and from the util method and save HTTP session info,
   * then stop the primary server by changing ServerStartPolicy to NEVER and patching domain.
   * Send another HTTP request to get http session state (count number), primary server and
   * session create time. Verify that a new primary server is selected and HTTP session state is migrated.
   */
  @Test
  @DisplayName("Stop the primary server, verify that a new primary server is picked and HTTP session state is migrated")
  void testSessionMigration() {
    final String primaryServerAttr = "primary";
    final String webServiceSetUrl = SESSMIGR_APP_WAR_NAME + "/?setCounter=" + SESSION_STATE;

    List<String> appList = new ArrayList();
    appList.add(SESSMIGR_APP_NAME);

    // build the model file list
    final List<String> modelList = Collections.singletonList(MODEL_DIR + "/" + SESSMIGR_MODEL_FILE);

    // create image with model files
    logger.info("Create image with model file and verify");
    String miiImage =
        createMiiImageAndVerify(SESSMIGR_IMAGE_NAME, modelList, appList);

    // docker login and push image to docker registry if necessary
    dockerLoginAndPushImageToRegistry(miiImage);

    //String monitoringExporterSrcDir = Paths.get(RESULTS_ROOT, "monitoringexp", "srcdir").toString();
    //cloneMonitoringExporter(monitoringExporterSrcDir);
    String managedServerPrefix = domainUid + "-managed-server";
    assertDoesNotThrow(() ->
        setupIstioModelInImageDomain(miiImage, domainNamespace, domainUid, managedServerPrefix),
        "setup for istio based domain failed");

    Map<String, String> httpAttrInfo = processHttpRequest(webServiceSetUrl, " -c ");

    // get HTTP response data
    String primaryServerName = httpAttrInfo.get(primaryServerAttr);
    logger.info("======primaryServerName is {0}", primaryServerName);
  }

  private int setupIstioModelInImageDomain(String miiImage,
                                           String domainNamespace,
                                           String domainUid,
                                           String managedServerPrefix) {

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
    Domain domain = createIstioDomainResource(domainUid,
        domainNamespace,
        adminSecretName,
        OCIR_SECRET_NAME,
        encryptionSecretName,
        replicaCount,
        miiImage,
        configMapName,
        clusterName);

    // create model in image domain
    createDomainAndVerify(domain, domainNamespace);
    String adminServerPodName = domainUid + "-admin-server";
    logger.info("Check admin service {0} is created in namespace {1}",
        adminServerPodName, domainNamespace);
    checkServiceExists(adminServerPodName, domainNamespace);

    // check admin server pod is ready
    logger.info("Wait for admin server pod {0} to be ready in namespace {1}",
        adminServerPodName, domainNamespace);
    checkPodReady(adminServerPodName, domainUid, domainNamespace);

    // check managed server services created
    for (int i = 1; i <= replicaCount; i++) {
      logger.info("Check managed service {0} is created in namespace {1}",
          managedServerPrefix + i, domainNamespace);
      checkServiceExists(managedServerPrefix + i, domainNamespace);
    }

    // check managed server pods are ready
    for (int i = 1; i <= replicaCount; i++) {
      logger.info("Wait for managed pod {0} to be ready in namespace {1}",
          managedServerPrefix + i, domainNamespace);
      checkPodReady(managedServerPrefix + i, domainUid, domainNamespace);
    }

    return 0;
  }

  private Map<String, String> processHttpRequest(String curlUrlPath,
                                                 String headerOption) {
    String[] httpAttrArray = {"sessioncreatetime", "sessionid", "primary", "secondary", "count"};
    Map<String, String> httpAttrInfo = new HashMap<String, String>();

    // build curl command
    String curlCmd = buildCurlCommand(curlUrlPath, headerOption);
    logger.info("==== Command to set HTTP request and get HTTP response {0} ", curlCmd);

    // set HTTP request and get HTTP response
    ExecResult execResult = assertDoesNotThrow(
        () -> execCommand(domainNamespace, adminServerPodName,
        null, true, "/bin/sh", "-c", curlCmd));
    if (execResult.exitValue() == 0) {
      logger.info("\n ===== HTTP response is \n " + execResult.stdout());
      assertAll("Check that primary server name is not null or empty",
          () -> assertNotNull(execResult.stdout(), "Primary server name shouldn’t be null"),
          () -> assertFalse(execResult.stdout().isEmpty(), "Primary server name shouldn’t be  empty")
      );

      for (String httpAttrKey : httpAttrArray) {
        String httpAttrValue = getHttpResponseAttribute(execResult.stdout(), httpAttrKey);
        httpAttrInfo.put(httpAttrKey, httpAttrValue);
      }
    } else {
      fail("==== Failed to process HTTP request " + execResult.stderr());
    }

    return httpAttrInfo;
  }

  private String buildCurlCommand(String curlUrlPath, String headerOption) {
    final String httpHeaderFile = "/u01/domains/header";
    final String clusterAddress = domainUid + "-cluster-" + clusterName;
    logger.info("Build a curl command with pod name {0}, curl URL path {1} and HTTP header option {2}",
        clusterAddress, curlUrlPath, headerOption);

    int waittime = 5;
    return new StringBuilder()
        .append("curl --silent --show-error")
        .append(" --connect-timeout ").append(waittime).append(" --max-time ").append(waittime)
        .append(" http://")
        .append(clusterAddress)
        .append(":")
        .append(managedServerPort)
        .append("/")
        .append(curlUrlPath)
        .append(headerOption)
        .append(httpHeaderFile).toString();
  }

  private String getHttpResponseAttribute(String httpResponseString, String attribute) {
    // retrieve the search pattern that matches the given HTTP data attribute
    String attrPatn = httpAttrMap.get(attribute);
    assertNotNull(attrPatn,"HTTP Attribute key shouldn’t be null");

    // search the value of given HTTP data attribute
    Pattern pattern = Pattern.compile(attrPatn);
    Matcher matcher = pattern.matcher(httpResponseString);
    String httpAttribute = null;

    if (matcher.find()) {
      httpAttribute = matcher.group(2);
    }

    return httpAttribute;
  }
}
