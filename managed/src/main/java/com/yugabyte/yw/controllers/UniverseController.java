// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import com.yugabyte.yw.cloud.PublicCloudConstants;
import com.yugabyte.yw.cloud.UniverseResourceDetails;
import com.yugabyte.yw.commissioner.Commissioner;
import com.yugabyte.yw.commissioner.Common.CloudType;
import com.yugabyte.yw.commissioner.tasks.DestroyUniverse;
import com.yugabyte.yw.commissioner.tasks.PauseUniverse;
import com.yugabyte.yw.commissioner.tasks.ReadOnlyClusterDelete;
import com.yugabyte.yw.commissioner.tasks.ResumeUniverse;
import com.yugabyte.yw.commissioner.tasks.UpgradeUniverse;
import com.yugabyte.yw.common.ApiResponse;
import com.yugabyte.yw.common.CertificateHelper;
import com.yugabyte.yw.common.ConfigHelper;
import com.yugabyte.yw.common.NodeUniverseManager;
import com.yugabyte.yw.common.PlacementInfoUtil;
import com.yugabyte.yw.common.ShellResponse;
import com.yugabyte.yw.common.Util;
import com.yugabyte.yw.common.ValidatingFormFactory;
import com.yugabyte.yw.common.YWServiceException;
import com.yugabyte.yw.common.YcqlQueryExecutor;
import com.yugabyte.yw.common.YsqlQueryExecutor;
import com.yugabyte.yw.common.config.RuntimeConfigFactory;
import com.yugabyte.yw.common.kms.EncryptionAtRestManager;
import com.yugabyte.yw.common.services.YBClientService;
import com.yugabyte.yw.forms.AlertConfigFormData;
import com.yugabyte.yw.forms.DatabaseSecurityFormData;
import com.yugabyte.yw.forms.DatabaseUserFormData;
import com.yugabyte.yw.forms.DiskIncreaseFormData;
import com.yugabyte.yw.forms.EncryptionAtRestKeyParams;
import com.yugabyte.yw.forms.RunQueryFormData;
import com.yugabyte.yw.forms.UniverseConfigureTaskParams;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.forms.UniverseResp;
import com.yugabyte.yw.forms.ToggleTlsParams;
import com.yugabyte.yw.forms.UpgradeParams;
import com.yugabyte.yw.forms.YWError;
import com.yugabyte.yw.forms.YWResults.YWSuccess;
import com.yugabyte.yw.metrics.MetricQueryHelper;
import com.yugabyte.yw.models.AccessKey;
import com.yugabyte.yw.models.AvailabilityZone;
import com.yugabyte.yw.models.CertificateInfo;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.CustomerTask;
import com.yugabyte.yw.models.HealthCheck;
import com.yugabyte.yw.models.Provider;
import com.yugabyte.yw.models.Region;
import com.yugabyte.yw.models.TaskInfo;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.helpers.NodeDetails;
import com.yugabyte.yw.models.helpers.PlacementInfo;
import com.yugabyte.yw.models.helpers.TaskType;
import com.yugabyte.yw.queries.QueryHelper;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import org.yb.client.YBClient;
import play.Play;
import play.data.Form;
import play.libs.Json;
import play.libs.concurrent.HttpExecutionContext;
import play.mvc.Http.HeaderNames;
import play.mvc.Result;
import play.mvc.Results;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

import static com.yugabyte.yw.common.PlacementInfoUtil.checkIfNodeParamsValid;
import static com.yugabyte.yw.common.PlacementInfoUtil.updatePlacementInfo;
import static com.yugabyte.yw.controllers.UniverseControllerRequestBinder.bindFormDataToTaskParams;
import static com.yugabyte.yw.forms.UniverseDefinitionTaskParams.Cluster;
import static com.yugabyte.yw.forms.UniverseDefinitionTaskParams.ClusterType;
import static com.yugabyte.yw.forms.UniverseDefinitionTaskParams.ExposingServiceState;
import static com.yugabyte.yw.forms.UniverseDefinitionTaskParams.UserIntent;
import static com.yugabyte.yw.forms.YWResults.YWSuccess.empty;
import static com.yugabyte.yw.forms.YWResults.YWSuccess.withMessage;

@Api("Universe")
public class UniverseController extends AuthenticatedController {
  private static final Logger LOG = LoggerFactory.getLogger(UniverseController.class);

  @Inject ValidatingFormFactory formFactory;

  @Inject Commissioner commissioner;

  @Inject MetricQueryHelper metricQueryHelper;

  @Inject HttpExecutionContext ec;

  @Inject QueryHelper queryHelper;

  @Inject play.Configuration appConfig;

  @Inject ConfigHelper configHelper;

  @Inject EncryptionAtRestManager keyManager;

  @Inject YsqlQueryExecutor ysqlQueryExecutor;

  @Inject YcqlQueryExecutor ycqlQueryExecutor;

  @Inject private NodeUniverseManager nodeUniverseManager;

  @Inject private RuntimeConfigFactory runtimeConfigFactory;

  // The YB client to use.
  public final YBClientService ybService;

  @Inject
  public UniverseController(YBClientService service) {
    this.ybService = service;
  }

  /**
   * API that checks if a Universe with a given name already exists.
   *
   * @return true if universe already exists, false otherwise
   */
  // TODO(.*endra):  This method is buggy. The javadoc does not match impl.
  //  Replace findByName with findByName_Corrected when UI is fixed to expect opposite.
  @Deprecated
  public Result findByName(UUID customerUUID, String universeName) {
    // Verify the customer with this universe is present.
    Customer.getOrBadRequest(customerUUID);
    LOG.info("Finding Universe with name {}.", universeName);
    if (Universe.checkIfUniverseExists(universeName)) {
      throw new YWServiceException(BAD_REQUEST, "Universe already exists");
    } else {
      return withMessage("Universe does not Exist");
    }
  }

  /**
   * Find universe by name
   *
   * @return UUID of universe looked up by name or else NOT_FOUND error
   */
  public Result findByName_Corrected(UUID customerUUID, String universeName) {
    // Verify the customer with this universe is present.
    Customer.getOrBadRequest(customerUUID);
    LOG.info("Finding Universe with name {}.", universeName);
    Universe universe = Universe.getUniverseByName(universeName);
    if (universe == null) {
      throw new YWServiceException(NOT_FOUND, "Universe does not Exist");
    }
    return Results.status(OK, Json.toJson(universe.universeUUID));
  }

  @ApiOperation(value = "setDatabaseCredentials", response = YWSuccess.class)
  public Result setDatabaseCredentials(UUID customerUUID, UUID universeUUID) {
    Customer customer = Customer.getOrBadRequest(customerUUID);
    Universe universe = Universe.getValidUniverseOrBadRequest(universeUUID, customer);

    if (!customer.code.equals("cloud")) {
      throw new YWServiceException(BAD_REQUEST, "Invalid Customer type.");
    }

    Form<DatabaseSecurityFormData> formData =
        formFactory.getFormDataOrBadRequest(DatabaseSecurityFormData.class);

    DatabaseSecurityFormData data = formData.get();
    data.validation();

    if (!StringUtils.isEmpty(data.ysqlAdminUsername)) {
      ysqlQueryExecutor.updateAdminPassword(universe, data);
    }

    if (!StringUtils.isEmpty(data.ycqlAdminUsername)) {
      ycqlQueryExecutor.updateAdminPassword(universe, data);
    }

    return withMessage("Updated security in DB.");
  }

  @ApiOperation(value = "createUserInDB", response = YWSuccess.class)
  public Result createUserInDB(UUID customerUUID, UUID universeUUID) {
    Customer customer = Customer.getOrBadRequest(customerUUID);
    Universe universe = Universe.getValidUniverseOrBadRequest(universeUUID, customer);

    if (!customer.code.equals("cloud")) {
      throw new YWServiceException(BAD_REQUEST, "Invalid Customer type.");
    }

    Form<DatabaseUserFormData> formData =
        formFactory.getFormDataOrBadRequest(DatabaseUserFormData.class);

    DatabaseUserFormData data = formData.get();

    data.validation();

    if (!StringUtils.isEmpty(data.ysqlAdminUsername)) {
      ysqlQueryExecutor.createUser(universe, data);
    }
    if (!StringUtils.isEmpty(data.ycqlAdminUsername)) {
      ycqlQueryExecutor.createUser(universe, data);
    }
    return withMessage("Created user in DB.");
  }

  @VisibleForTesting static final String DEPRECATED = "Deprecated.";

  @ApiOperation(
      value = "run command in shell",
      notes = "This operation is no longer supported sue to security reasons",
      response = YWError.class)
  public Result runInShell(UUID customerUUID, UUID universeUUID) {
    throw new YWServiceException(BAD_REQUEST, DEPRECATED);
  }

  @VisibleForTesting static final String LEARN_DOMAIN_NAME = "learn.yugabyte.com";

  @VisibleForTesting
  static final String RUN_QUERY_ISNT_ALLOWED = "run_query not supported for this application";

  @ApiOperation(
      value = "Run YSQL query against this universe",
      notes = "Only valid when platform is running in mode is `OSS`",
      response = Object.class)
  public Result runQuery(UUID customerUUID, UUID universeUUID) {
    Customer customer = Customer.getOrBadRequest(customerUUID);
    Universe universe = Universe.getValidUniverseOrBadRequest(universeUUID, customer);
    String mode = appConfig.getString("yb.mode", "PLATFORM");
    if (!mode.equals("OSS")) {
      throw new YWServiceException(BAD_REQUEST, RUN_QUERY_ISNT_ALLOWED);
    }

    boolean correctOrigin = isCorrectOrigin();

    String securityLevel =
        (String) configHelper.getConfig(ConfigHelper.ConfigType.Security).get("level");
    if (!correctOrigin || securityLevel == null || !securityLevel.equals("insecure")) {
      throw new YWServiceException(BAD_REQUEST, RUN_QUERY_ISNT_ALLOWED);
    }

    Form<RunQueryFormData> formData = formFactory.getFormDataOrBadRequest(RunQueryFormData.class);

    auditService().createAuditEntry(ctx(), request(), Json.toJson(formData.data()));
    return ApiResponse.success(ysqlQueryExecutor.executeQuery(universe, formData.get()));
  }

  private static boolean isCorrectOrigin() {
    boolean correctOrigin = false;
    Optional<String> origin = request().header(HeaderNames.ORIGIN);
    if (origin.isPresent()) {
      try {
        URI uri = new URI(origin.get());
        correctOrigin = LEARN_DOMAIN_NAME.equals(uri.getHost());
      } catch (URISyntaxException e) {
        LOG.debug("Ignored exception: " + e.getMessage());
      }
    }
    return correctOrigin;
  }

  /**
   * Function to Trim keys and values of the passed map.
   *
   * @param data key value pairs.
   * @return key value pairs with trim keys and values.
   */
  @VisibleForTesting
  static Map<String, String> trimFlags(Map<String, String> data) {
    Map<String, String> trimData = new HashMap<>();
    for (Map.Entry<String, String> intent : data.entrySet()) {
      String key = intent.getKey();
      String value = intent.getValue();
      trimData.put(key.trim(), value.trim());
    }
    return trimData;
  }

  /**
   * API that binds the UniverseDefinitionTaskParams class by merging the UserIntent with the
   * generated taskParams.
   *
   * @param customerUUID the ID of the customer configuring the Universe.
   * @return UniverseDefinitionTasksParams in a serialized form
   */
  @ApiOperation(
      value = "configure the universe parameters",
      notes =
          "This API builds the new universe definition task parameters by merging the input "
              + "UserIntent with the current taskParams and returns the resulting task parameters "
              + "in a serialized form",
      response = UniverseDefinitionTaskParams.class)
  public Result configure(UUID customerUUID) {

    // Verify the customer with this universe is present.
    Customer customer = Customer.getOrBadRequest(customerUUID);

    UniverseConfigureTaskParams taskParams =
        bindFormDataToTaskParams(request(), UniverseConfigureTaskParams.class);

    if (taskParams.currentClusterType == null) {
      throw new YWServiceException(BAD_REQUEST, "currentClusterType must be set");
    }
    if (taskParams.clusterOperation == null) {
      throw new YWServiceException(BAD_REQUEST, "clusterOperation must be set");
    }
    // TODO(Rahul): When we support multiple read only clusters, change clusterType to cluster
    //  uuid.
    Cluster c =
        taskParams.getCurrentClusterType().equals(ClusterType.PRIMARY)
            ? taskParams.getPrimaryCluster()
            : taskParams.getReadOnlyClusters().get(0);
    UserIntent primaryIntent = c.userIntent;
    primaryIntent.masterGFlags = trimFlags(primaryIntent.masterGFlags);
    primaryIntent.tserverGFlags = trimFlags(primaryIntent.tserverGFlags);
    if (checkIfNodeParamsValid(taskParams, c)) {
      PlacementInfoUtil.updateUniverseDefinition(taskParams, customer.getCustomerId(), c.uuid);
    } else {
      throw new YWServiceException(
          BAD_REQUEST,
          "Invalid Node/AZ combination for given instance type " + c.userIntent.instanceType);
    }

    return ApiResponse.success(taskParams);
  }

  // TODO: This method take params in post body instead of looking up the params of universe in db
  //  this needs to be fixed as follows:
  // 1> There should be a GET method to read universe resources without giving the params in the
  // body
  // 2> Map this to HTTP GET request.
  // 3> In addition /universe_configure should also return resource estimation info back.
  @ApiOperation(
      value = "Api to get the resource estimate for a universe",
      notes =
          "Expects UniverseDefinitionTaskParams in request body and calculates the resource "
              + "estimate for NodeDetailsSet in that.",
      response = UniverseResourceDetails.class)
  public Result getUniverseResources(UUID customerUUID) {
    UniverseDefinitionTaskParams taskParams =
        bindFormDataToTaskParams(request(), UniverseDefinitionTaskParams.class);

    Set<NodeDetails> nodesInCluster;

    if (taskParams.getCurrentClusterType().equals(ClusterType.PRIMARY)) {
      nodesInCluster =
          taskParams
              .nodeDetailsSet
              .stream()
              .filter(n -> n.isInPlacement(taskParams.getPrimaryCluster().uuid))
              .collect(Collectors.toSet());
    } else {
      nodesInCluster =
          taskParams
              .nodeDetailsSet
              .stream()
              .filter(n -> n.isInPlacement(taskParams.getReadOnlyClusters().get(0).uuid))
              .collect(Collectors.toSet());
    }
    return ApiResponse.success(
        UniverseResourceDetails.create(
            nodesInCluster, taskParams, runtimeConfigFactory.globalRuntimeConf()));
  }

  /**
   * API that queues a task to create a new universe. This does not wait for the creation.
   *
   * @return result of the universe create operation.
   */
  @ApiOperation(value = "Create a YugaByte Universe", response = UniverseResp.class)
  @ApiImplicitParams(
      @ApiImplicitParam(
          name = "univ_def",
          value = "univ definition",
          dataType = "com.yugabyte.yw.forms.UniverseDefinitionTaskParams",
          paramType = "body",
          required = true))
  public Result create(UUID customerUUID) {
    // Verify the customer with this universe is present.
    Customer customer = Customer.getOrBadRequest(customerUUID);
    LOG.info("Create for {}.", customerUUID);
    // Get the user submitted form data.
    UniverseDefinitionTaskParams taskParams =
        bindFormDataToTaskParams(request(), UniverseDefinitionTaskParams.class);

    if (taskParams.getPrimaryCluster() != null
        && !Util.isValidUniverseNameFormat(
            taskParams.getPrimaryCluster().userIntent.universeName)) {
      throw new YWServiceException(BAD_REQUEST, Util.UNIV_NAME_ERROR_MESG);
    }

    for (Cluster c : taskParams.clusters) {
      Provider provider = Provider.getOrBadRequest(UUID.fromString(c.userIntent.provider));
      // Set the provider code.
      c.userIntent.providerType = CloudType.valueOf(provider.code);
      c.validate();
      // Check if for a new create, no value is set, we explicitly set it to UNEXPOSED.
      if (c.userIntent.enableExposingService == ExposingServiceState.NONE) {
        c.userIntent.enableExposingService = ExposingServiceState.UNEXPOSED;
      }
      if (c.userIntent.providerType.equals(CloudType.onprem)) {
        if (provider.getConfig().containsKey("USE_HOSTNAME")) {
          c.userIntent.useHostname = Boolean.parseBoolean(provider.getConfig().get("USE_HOSTNAME"));
        }
      }

      if (c.userIntent.providerType.equals(CloudType.kubernetes)) {
        try {
          checkK8sProviderAvailability(provider, customer);
        } catch (IllegalArgumentException e) {
          throw new YWServiceException(BAD_REQUEST, e.getMessage());
        }
      }

      // Set the node exporter config based on the provider
      if (!c.userIntent.providerType.equals(CloudType.kubernetes)) {
        AccessKey accessKey = AccessKey.get(provider.uuid, c.userIntent.accessKeyCode);
        AccessKey.KeyInfo keyInfo = accessKey.getKeyInfo();
        boolean installNodeExporter = keyInfo.installNodeExporter;
        int nodeExporterPort = keyInfo.nodeExporterPort;
        String nodeExporterUser = keyInfo.nodeExporterUser;
        taskParams.extraDependencies.installNodeExporter = installNodeExporter;
        taskParams.communicationPorts.nodeExporterPort = nodeExporterPort;

        for (NodeDetails node : taskParams.nodeDetailsSet) {
          node.nodeExporterPort = nodeExporterPort;
        }

        if (installNodeExporter) {
          taskParams.nodeExporterUser = nodeExporterUser;
        }
      }

      updatePlacementInfo(taskParams.getNodesInCluster(c.uuid), c.placementInfo);
    }

    if (taskParams.getPrimaryCluster() != null) {
      UserIntent userIntent = taskParams.getPrimaryCluster().userIntent;
      if (userIntent.providerType.isVM() && userIntent.enableYSQL) {
        taskParams.setTxnTableWaitCountFlag = true;
      }
    }

    // Create a new universe. This makes sure that a universe of this name does not already exist
    // for this customer id.
    Universe universe = Universe.create(taskParams, customer.getCustomerId());
    LOG.info("Created universe {} : {}.", universe.universeUUID, universe.name);

    // Add an entry for the universe into the customer table.
    customer.addUniverseUUID(universe.universeUUID);
    customer.save();

    LOG.info(
        "Added universe {} : {} for customer [{}].",
        universe.universeUUID,
        universe.name,
        customer.getCustomerId());

    TaskType taskType = TaskType.CreateUniverse;
    Cluster primaryCluster = taskParams.getPrimaryCluster();

    if (primaryCluster != null) {
      UserIntent primaryIntent = primaryCluster.userIntent;
      primaryIntent.masterGFlags = trimFlags(primaryIntent.masterGFlags);
      primaryIntent.tserverGFlags = trimFlags(primaryIntent.tserverGFlags);
      if (primaryCluster.userIntent.providerType.equals(CloudType.kubernetes)) {
        taskType = TaskType.CreateKubernetesUniverse;
        universe.updateConfig(
            ImmutableMap.of(Universe.HELM2_LEGACY, Universe.HelmLegacy.V3.toString()));
      } else {
        if (primaryCluster.userIntent.enableIPV6) {
          throw new YWServiceException(
              BAD_REQUEST, "IPV6 not supported for platform deployed VMs.");
        }
      }
      if (primaryCluster.userIntent.enableNodeToNodeEncrypt
          || primaryCluster.userIntent.enableClientToNodeEncrypt) {
        if (taskParams.rootCA == null) {
          taskParams.rootCA =
              CertificateHelper.createRootCA(
                  taskParams.nodePrefix, customerUUID, appConfig.getString("yb.storage.path"));
        }
        // If client encryption is enabled, generate the client cert file for each node.
        if (primaryCluster.userIntent.enableClientToNodeEncrypt) {
          CertificateInfo cert = CertificateInfo.get(taskParams.rootCA);
          if (cert.certType == CertificateInfo.Type.SelfSigned) {
            CertificateHelper.createClientCertificate(
                taskParams.rootCA,
                String.format(
                    CertificateHelper.CERT_PATH,
                    appConfig.getString("yb.storage.path"),
                    customerUUID.toString(),
                    taskParams.rootCA.toString()),
                CertificateHelper.DEFAULT_CLIENT,
                null,
                null);
          } else {
            if (!taskParams.getPrimaryCluster().userIntent.providerType.equals(CloudType.onprem)) {
              throw new YWServiceException(
                  BAD_REQUEST, "Custom certificates are only supported for onprem providers.");
            }
            if (!CertificateInfo.isCertificateValid(taskParams.rootCA)) {
              String errMsg =
                  String.format(
                      "The certificate %s needs info. Update the cert" + " and retry.",
                      CertificateInfo.get(taskParams.rootCA).label);
              LOG.error(errMsg);
              throw new YWServiceException(BAD_REQUEST, errMsg);
            }
            LOG.info(
                "Skipping client certificate creation for universe {} ({}) "
                    + "because cert {} (type {})is not a self-signed cert.",
                universe.name,
                universe.universeUUID,
                taskParams.rootCA,
                cert.certType);
          }
        }
        // Set the flag to mark the universe as using TLS enabled and therefore not allowing
        // insecure connections.
        taskParams.allowInsecure = false;
      }

      // TODO: (Daniel) - Move this out to an async task
      if (primaryCluster.userIntent.enableVolumeEncryption
          && primaryCluster.userIntent.providerType.equals(CloudType.aws)) {
        byte[] cmkArnBytes =
            keyManager.generateUniverseKey(
                taskParams.encryptionAtRestConfig.kmsConfigUUID,
                universe.universeUUID,
                taskParams.encryptionAtRestConfig);
        if (cmkArnBytes == null || cmkArnBytes.length == 0) {
          primaryCluster.userIntent.enableVolumeEncryption = false;
        } else {
          // TODO: (Daniel) - Update this to be inside of encryptionAtRestConfig
          taskParams.cmkArn = new String(cmkArnBytes);
        }
      }
    }

    universe.updateConfig(ImmutableMap.of(Universe.TAKE_BACKUPS, "true"));

    // Submit the task to create the universe.
    UUID taskUUID = commissioner.submit(taskType, taskParams);
    LOG.info(
        "Submitted create universe for {}:{}, task uuid = {}.",
        universe.universeUUID,
        universe.name,
        taskUUID);

    // Add this task uuid to the user universe.
    CustomerTask.create(
        customer,
        universe.universeUUID,
        taskUUID,
        CustomerTask.TargetType.Universe,
        CustomerTask.TaskType.Create,
        universe.name);
    LOG.info(
        "Saved task uuid "
            + taskUUID
            + " in customer tasks table for universe "
            + universe.universeUUID
            + ":"
            + universe.name);

    auditService().createAuditEntryWithReqBody(ctx(), taskUUID);
    return ApiResponse.success(createResp(universe, taskUUID));
  }

  @ApiOperation(value = "setUniverse", response = UniverseResp.class)
  public Result setUniverseKey(UUID customerUUID, UUID universeUUID) {
    Customer customer = Customer.getOrBadRequest(customerUUID);
    Universe universe = Universe.getValidUniverseOrBadRequest(universeUUID, customer);

    LOG.info("Updating universe key {} for {}.", universeUUID, customerUUID);
    // Get the user submitted form data.

    EncryptionAtRestKeyParams taskParams =
        EncryptionAtRestKeyParams.bindFromFormData(universeUUID, request());

    try {
      TaskType taskType = TaskType.SetUniverseKey;
      taskParams.expectedUniverseVersion = universe.version;
      UUID taskUUID = commissioner.submit(taskType, taskParams);
      LOG.info(
          "Submitted set universe key for {}:{}, task uuid = {}.",
          universe.universeUUID,
          universe.name,
          taskUUID);

      CustomerTask.TaskType customerTaskType = null;
      switch (taskParams.encryptionAtRestConfig.opType) {
        case ENABLE:
          if (universe.getUniverseDetails().encryptionAtRestConfig.encryptionAtRestEnabled) {
            customerTaskType = CustomerTask.TaskType.RotateEncryptionKey;
          } else {
            customerTaskType = CustomerTask.TaskType.EnableEncryptionAtRest;
          }
          break;
        case DISABLE:
          customerTaskType = CustomerTask.TaskType.DisableEncryptionAtRest;
          break;
        default:
        case UNDEFINED:
          break;
      }

      // Add this task uuid to the user universe.
      CustomerTask.create(
          customer,
          universe.universeUUID,
          taskUUID,
          CustomerTask.TargetType.Universe,
          customerTaskType,
          universe.name);
      LOG.info(
          "Saved task uuid "
              + taskUUID
              + " in customer tasks table for universe "
              + universe.universeUUID
              + ":"
              + universe.name);

      auditService().createAuditEntryWithReqBody(ctx(), taskUUID);
      return ApiResponse.success(createResp(universe, taskUUID));
    } catch (Exception e) {
      String errMsg =
          String.format(
              "Error occurred attempting to %s the universe encryption key",
              taskParams.encryptionAtRestConfig.opType.name());
      LOG.error(errMsg, e);
      throw new YWServiceException(BAD_REQUEST, errMsg);
    }
  }

  /**
   * API that sets universe version number to -1
   *
   * @return result of settings universe version to -1 (either success if universe exists else
   *     failure
   */
  @ApiOperation(value = "resetVersion", response = YWSuccess.class)
  public Result resetVersion(UUID customerUUID, UUID universeUUID) {
    Customer customer = Customer.getOrBadRequest(customerUUID);
    Universe universe = Universe.getValidUniverseOrBadRequest(universeUUID, customer);

    universe.resetVersion();
    return empty();
  }

  /**
   * API that downloads the log files for a particular node in a universe. Synchronized due to
   * potential race conditions.
   *
   * @param customerUUID ID of custoemr
   * @param universeUUID ID of universe
   * @param nodeName name of the node
   * @return tar file of the tserver and master log files (if the node is a master server).
   */
  // TODO: API
  public CompletionStage<Result> downloadNodeLogs(
      UUID customerUUID, UUID universeUUID, String nodeName) {
    return CompletableFuture.supplyAsync(
        () -> {
          Customer customer = Customer.getOrBadRequest(customerUUID);
          Universe universe = Universe.getValidUniverseOrBadRequest(universeUUID, customer);
          NodeDetails node;
          String storagePath, tarFileName, targetFile;
          ShellResponse response;

          LOG.debug("Retrieving logs for " + nodeName);
          node = universe.getNode(nodeName);
          storagePath = runtimeConfigFactory.staticApplicationConf().getString("yb.storage.path");
          tarFileName = node.cloudInfo.private_ip + "-logs.tar.gz";
          targetFile = storagePath + "/" + tarFileName;
          response = nodeUniverseManager.downloadNodeLogs(node, universe, targetFile);

          if (response.code != 0) {
            throw new YWServiceException(INTERNAL_SERVER_ERROR, response.message);
          }

          try {
            File file = new File(targetFile);
            InputStream is = new FileInputStream(file);
            file.delete();
            // return file to client
            response().setHeader("Content-Disposition", "attachment; filename=" + tarFileName);
            return ok(is).as("application/x-compressed");
          } catch (FileNotFoundException e) {
            throw new YWServiceException(INTERNAL_SERVER_ERROR, response.message);
          }
        },
        ec.current());
  }

  /**
   * API that toggles TLS state of the universe. Can enable/disable node to node and client to node
   * encryption. Supports rolling and non-rolling upgrade of the universe.
   *
   * @param customerUuid ID of customer
   * @param universeUuid ID of universe
   * @return Result of update operation with task id
   */
  public Result toggleTls(UUID customerUuid, UUID universeUuid) {
    Customer customer = Customer.getOrBadRequest(customerUuid);
    Universe universe = Universe.getValidUniverseOrBadRequest(universeUuid, customer);
    UniverseDefinitionTaskParams universeDetails = universe.getUniverseDetails();
    UserIntent userIntent = universeDetails.getPrimaryCluster().userIntent;

    LOG.info(
        "Toggle TLS for universe {} [ {} ] customer {}.",
        universe.name,
        universeUuid,
        customerUuid);

    ObjectNode formData = (ObjectNode) request().body().asJson();
    ToggleTlsParams requestParams = ToggleTlsParams.bindFromFormData(formData);

    YWError error = requestParams.verifyParams(universeDetails);
    if (error != null) {
      throw new YWServiceException(BAD_REQUEST, error.error + " - for universe: " + universeUuid);
    }

    if (!universeDetails.isUniverseEditable()) {
      throw new YWServiceException(
          BAD_REQUEST, "Universe UUID " + universeUuid + " cannot be edited.");
    }

    if (universe.nodesInTransit()) {
      throw new YWServiceException(
          BAD_REQUEST,
          "Cannot perform a toggle TLS operation on universe "
              + universeUuid
              + " as it has nodes in one of "
              + NodeDetails.IN_TRANSIT_STATES
              + " states.");
    }

    if (!CertificateInfo.isCertificateValid(requestParams.rootCA)) {
      throw new YWServiceException(
          BAD_REQUEST,
          String.format(
              "The certificate %s needs info. Update the cert and retry.",
              CertificateInfo.get(requestParams.rootCA).label));
    }

    if (requestParams.rootCA != null
        && CertificateInfo.get(requestParams.rootCA).certType
            == CertificateInfo.Type.CustomCertHostPath
        && !userIntent.providerType.equals(CloudType.onprem)) {
      throw new YWServiceException(
          BAD_REQUEST, "Custom certificates are only supported for on-prem providers.");
    }

    TaskType taskType = TaskType.UpgradeUniverse;
    UpgradeParams taskParams = new UpgradeParams();
    taskParams.taskType = UpgradeUniverse.UpgradeTaskType.ToggleTls;
    taskParams.upgradeOption = requestParams.upgradeOption;
    taskParams.universeUUID = universeUuid;
    taskParams.expectedUniverseVersion = -1;
    taskParams.enableNodeToNodeEncrypt = requestParams.enableNodeToNodeEncrypt;
    taskParams.enableClientToNodeEncrypt = requestParams.enableClientToNodeEncrypt;
    taskParams.allowInsecure =
        !(requestParams.enableNodeToNodeEncrypt || requestParams.enableClientToNodeEncrypt);

    // Create root certificate if not exist
    taskParams.rootCA = universeDetails.rootCA;
    if (taskParams.rootCA == null) {
      taskParams.rootCA =
          requestParams.rootCA != null
              ? requestParams.rootCA
              : CertificateHelper.createRootCA(
                  universeDetails.nodePrefix, customerUuid, appConfig.getString("yb.storage.path"));
    }

    // Create client certificate if not exists
    if (!userIntent.enableClientToNodeEncrypt && requestParams.enableClientToNodeEncrypt) {
      CertificateInfo cert = CertificateInfo.get(taskParams.rootCA);
      if (cert.certType == CertificateInfo.Type.SelfSigned) {
        CertificateHelper.createClientCertificate(
            taskParams.rootCA,
            String.format(
                CertificateHelper.CERT_PATH,
                appConfig.getString("yb.storage.path"),
                customerUuid.toString(),
                taskParams.rootCA.toString()),
            CertificateHelper.DEFAULT_CLIENT,
            null,
            null);
      }
    }

    UUID taskUUID = commissioner.submit(taskType, taskParams);
    LOG.info(
        "Submitted toggle tls for {} : {}, task uuid = {}.",
        universe.universeUUID,
        universe.name,
        taskUUID);

    CustomerTask.create(
        customer,
        universe.universeUUID,
        taskUUID,
        CustomerTask.TargetType.Universe,
        CustomerTask.TaskType.ToggleTls,
        universe.name);
    LOG.info(
        "Saved task uuid {} in customer tasks table for universe {} : {}.",
        taskUUID,
        universe.universeUUID,
        universe.name);
    auditService().createAuditEntry(ctx(), request(), Json.toJson(formData), taskUUID);
    return ApiResponse.success(createResp(universe, taskUUID));
  }

  /**
   * API that queues a task to update/edit a universe of a given customer. This does not wait for
   * the completion.
   *
   * @return result of the universe update operation.
   */
  @ApiOperation(value = "updateUniverse", response = UniverseResp.class)
  @ApiImplicitParams(
      @ApiImplicitParam(
          name = "univ_def",
          value = "univ definition",
          dataType = "com.yugabyte.yw.forms.UniverseDefinitionTaskParams",
          paramType = "body",
          required = true))

  // TODO: API
  public Result update(UUID customerUUID, UUID universeUUID) {
    Customer customer = Customer.getOrBadRequest(customerUUID);
    Universe universe = Universe.getValidUniverseOrBadRequest(universeUUID, customer);

    LOG.info("Update universe {} [ {} ] customer {}.", universe.name, universeUUID, customerUUID);
    // Get the user submitted form data.

    UniverseDefinitionTaskParams taskParams =
        bindFormDataToTaskParams(request(), UniverseDefinitionTaskParams.class);

    if (!universe.getUniverseDetails().isUniverseEditable()) {
      String errMsg = "Universe UUID " + universeUUID + " cannot be edited.";
      LOG.error(errMsg);
      throw new YWServiceException(BAD_REQUEST, errMsg);
    }

    if (universe.nodesInTransit()) {
      // TODO 503 - Service Unavailable
      throw new YWServiceException(
          BAD_REQUEST,
          "Cannot perform an edit operation on universe "
              + universeUUID
              + " as it has nodes in one of "
              + NodeDetails.IN_TRANSIT_STATES
              + " states.");
    }

    Cluster primaryCluster = taskParams.getPrimaryCluster();
    UUID uuid;
    PlacementInfo placementInfo;
    TaskType taskType = TaskType.EditUniverse;
    if (primaryCluster == null) {
      // Update of a read only cluster.
      List<Cluster> readReplicaClusters = taskParams.getReadOnlyClusters();
      if (readReplicaClusters.size() != 1) {
        String errMsg =
            "Can only have one read-only cluster per edit/update for now, found "
                + readReplicaClusters.size();
        LOG.error(errMsg);
        throw new YWServiceException(BAD_REQUEST, errMsg);
      }
      Cluster cluster = readReplicaClusters.get(0);
      uuid = cluster.uuid;
      placementInfo = cluster.placementInfo;
    } else {
      uuid = primaryCluster.uuid;
      placementInfo = primaryCluster.placementInfo;

      Map<String, String> universeConfig = universe.getConfig();
      if (primaryCluster.userIntent.providerType.equals(CloudType.kubernetes)) {
        taskType = TaskType.EditKubernetesUniverse;
        if (!universeConfig.containsKey(Universe.HELM2_LEGACY)) {
          throw new YWServiceException(
              BAD_REQUEST,
              "Cannot perform an edit operation on universe "
                  + universeUUID
                  + " as it is not helm 3 compatible. "
                  + "Manually migrate the deployment to helm3 "
                  + "and then mark the universe as helm 3 compatible.");
        }
      } else {
        // Set the node exporter config based on the provider
        UniverseDefinitionTaskParams universeDetails = universe.getUniverseDetails();
        boolean installNodeExporter = universeDetails.extraDependencies.installNodeExporter;
        int nodeExporterPort = universeDetails.communicationPorts.nodeExporterPort;
        String nodeExporterUser = universeDetails.nodeExporterUser;
        taskParams.extraDependencies.installNodeExporter = installNodeExporter;
        taskParams.communicationPorts.nodeExporterPort = nodeExporterPort;

        for (NodeDetails node : taskParams.nodeDetailsSet) {
          node.nodeExporterPort = nodeExporterPort;
        }

        if (installNodeExporter) {
          taskParams.nodeExporterUser = nodeExporterUser;
        }
      }
    }

    updatePlacementInfo(taskParams.getNodesInCluster(uuid), placementInfo);

    taskParams.rootCA = universe.getUniverseDetails().rootCA;
    if (!CertificateInfo.isCertificateValid(taskParams.rootCA)) {
      String errMsg =
          String.format(
              "The certificate %s needs info. Update the cert and retry.",
              CertificateInfo.get(taskParams.rootCA).label);
      LOG.error(errMsg);
      throw new YWServiceException(BAD_REQUEST, errMsg);
    }
    LOG.info(
        "Found universe {} : name={} at version={}.",
        universe.universeUUID,
        universe.name,
        universe.version);

    UUID taskUUID = commissioner.submit(taskType, taskParams);
    LOG.info(
        "Submitted edit universe for {} : {}, task uuid = {}.",
        universe.universeUUID,
        universe.name,
        taskUUID);

    // Add this task uuid to the user universe.
    CustomerTask.create(
        customer,
        universe.universeUUID,
        taskUUID,
        primaryCluster == null ? CustomerTask.TargetType.Cluster : CustomerTask.TargetType.Universe,
        CustomerTask.TaskType.Update,
        universe.name);
    LOG.info(
        "Saved task uuid {} in customer tasks table for universe {} : {}.",
        taskUUID,
        universe.universeUUID,
        universe.name);
    auditService().createAuditEntryWithReqBody(ctx(), taskUUID);
    return ApiResponse.success(createResp(universe, taskUUID));
  }

  /** List the universes for a given customer. */
  @ApiOperation(value = "List Universes", response = UniverseResp.class, responseContainer = "List")
  public Result list(UUID customerUUID) {
    // Verify the customer is present.
    Customer customer = Customer.getOrBadRequest(customerUUID);
    List<UniverseResp> universes = new ArrayList<>();
    // TODO: Restrict the list api json payload, possibly to only include UUID, Name etc
    for (Universe universe : customer.getUniverses()) {
      UniverseResp universePayload = createResp(universe, null);
      universes.add(universePayload);
    }
    return ApiResponse.success(universes);
  }

  /**
   * Mark whether the universe needs to be backed up or not.
   *
   * @return Result
   */
  @ApiOperation(value = "Set backup Flag for a universe", response = YWSuccess.class)
  public Result setBackupFlag(UUID customerUUID, UUID universeUUID) {
    Customer customer = Customer.getOrBadRequest(customerUUID);
    Universe universe = Universe.getValidUniverseOrBadRequest(universeUUID, customer);

    String active;
    Map<String, String> config = new HashMap<>();
    if (request().getQueryString("markActive") != null) {
      active = request().getQueryString("markActive");
      config.put(Universe.TAKE_BACKUPS, active);
    } else {
      throw new YWServiceException(BAD_REQUEST, "Invalid Query: Need to specify markActive value");
    }
    universe.updateConfig(config);
    auditService().createAuditEntry(ctx(), request());
    return empty();
  }

  /**
   * Mark whether the universe has been made helm compatible.
   *
   * @return Result
   */
  @ApiOperation(value = "Set the universe as helm3 compatible", response = YWSuccess.class)
  public Result setHelm3Compatible(UUID customerUUID, UUID universeUUID) {
    Customer customer = Customer.getOrBadRequest(customerUUID);
    Universe universe = Universe.getValidUniverseOrBadRequest(universeUUID, customer);

    // Check if the provider is k8s and that we haven't already marked this universe
    // as helm compatible.
    Map<String, String> universeConfig = universe.getConfig();
    if (universeConfig.containsKey(Universe.HELM2_LEGACY)) {
      throw new YWServiceException(BAD_REQUEST, "Universe was already marked as helm3 compatible.");
    }
    Cluster primaryCluster = universe.getUniverseDetails().getPrimaryCluster();
    if (!primaryCluster.userIntent.providerType.equals(CloudType.kubernetes)) {
      throw new YWServiceException(BAD_REQUEST, "Only applicable for k8s universes.");
    }

    Map<String, String> config = new HashMap<>();
    config.put(Universe.HELM2_LEGACY, Universe.HelmLegacy.V2TO3.toString());
    universe.updateConfig(config);
    auditService().createAuditEntry(ctx(), request());
    return empty();
  }

  @ApiOperation(value = "Configure Alerts for a universe", response = YWSuccess.class)
  public Result configureAlerts(UUID customerUUID, UUID universeUUID) {
    Customer customer = Customer.getOrBadRequest(customerUUID);
    Universe universe = Universe.getValidUniverseOrBadRequest(universeUUID, customer);

    Map<String, String> config = new HashMap<>();
    Form<AlertConfigFormData> formData =
        formFactory.getFormDataOrBadRequest(AlertConfigFormData.class);

    AlertConfigFormData alertConfig = formData.get();
    long disabledUntilSecs = 0;
    if (alertConfig.disabled) {
      if (null == alertConfig.disablePeriodSecs) {
        disabledUntilSecs = Long.MAX_VALUE;
      } else {
        disabledUntilSecs = (System.currentTimeMillis() / 1000) + alertConfig.disablePeriodSecs;
      }
      LOG.info(
          String.format(
              "Will disable alerts for universe %s until unix time %d [ %s ].",
              universeUUID, disabledUntilSecs, Util.unixTimeToString(disabledUntilSecs)));
    } else {
      LOG.info(
          String.format(
              "Will enable alerts for universe %s [unix time  = %d].",
              universeUUID, disabledUntilSecs));
    }
    config.put(Universe.DISABLE_ALERTS_UNTIL, Long.toString(disabledUntilSecs));
    universe.updateConfig(config);

    return empty();
  }

  @ApiOperation(value = "getUniverse", response = UniverseResp.class)
  public Result index(UUID customerUUID, UUID universeUUID) {
    Customer customer = Customer.getOrBadRequest(customerUUID);
    Universe universe = Universe.getValidUniverseOrBadRequest(universeUUID, customer);
    return ApiResponse.success(createResp(universe, null));
  }

  // TODO: return YWTaskList
  @ApiOperation(value = "Pause the universe", response = Object.class)
  public Result pause(UUID customerUUID, UUID universeUUID) {
    Customer customer = Customer.getOrBadRequest(customerUUID);
    Universe universe = Universe.getValidUniverseOrBadRequest(universeUUID, customer);

    LOG.info(
        "Pause universe, customer uuid: {}, universe: {} [ {} ] ",
        customerUUID,
        universe.name,
        universeUUID);

    // Create the Commissioner task to pause the universe.
    PauseUniverse.Params taskParams = new PauseUniverse.Params();
    taskParams.universeUUID = universeUUID;
    // There is no staleness of a pause request. Perform it even if the universe has changed.
    taskParams.expectedUniverseVersion = -1;
    taskParams.customerUUID = customerUUID;
    // Submit the task to pause the universe.
    TaskType taskType = TaskType.PauseUniverse;

    UUID taskUUID = commissioner.submit(taskType, taskParams);
    LOG.info("Submitted pause universe for " + universeUUID + ", task uuid = " + taskUUID);

    // Add this task uuid to the user universe.
    CustomerTask.create(
        customer,
        universe.universeUUID,
        taskUUID,
        CustomerTask.TargetType.Universe,
        CustomerTask.TaskType.Pause,
        universe.name);

    LOG.info("Paused universe " + universeUUID + " for customer [" + customer.name + "]");

    ObjectNode response = Json.newObject();
    response.put("taskUUID", taskUUID.toString());
    auditService().createAuditEntry(ctx(), request(), taskUUID);
    return ApiResponse.success(response);
  }

  // TODO: return YWTaskList
  @ApiOperation(value = "Resume the universe", response = Object.class)
  public Result resume(UUID customerUUID, UUID universeUUID) {
    Customer customer = Customer.getOrBadRequest(customerUUID);
    Universe universe = Universe.getValidUniverseOrBadRequest(universeUUID, customer);

    LOG.info(
        "Resume universe, customer uuid: {}, universe: {} [ {} ] ",
        customerUUID,
        universe.name,
        universeUUID);

    // Create the Commissioner task to resume the universe.
    ResumeUniverse.Params taskParams = new ResumeUniverse.Params();
    taskParams.universeUUID = universeUUID;
    // There is no staleness of a resume request. Perform it even if the universe has changed.
    taskParams.expectedUniverseVersion = -1;
    taskParams.customerUUID = customerUUID;
    // Submit the task to resume the universe.
    TaskType taskType = TaskType.ResumeUniverse;

    UUID taskUUID = commissioner.submit(taskType, taskParams);
    LOG.info("Submitted resume universe for " + universeUUID + ", task uuid = " + taskUUID);

    // Add this task uuid to the user universe.
    CustomerTask.create(
        customer,
        universe.universeUUID,
        taskUUID,
        CustomerTask.TargetType.Universe,
        CustomerTask.TaskType.Resume,
        universe.name);

    LOG.info("Resumed universe " + universeUUID + " for customer [" + customer.name + "]");

    ObjectNode response = Json.newObject();
    response.put("taskUUID", taskUUID.toString());
    auditService().createAuditEntry(ctx(), request(), taskUUID);
    return ApiResponse.success(response);
  }

  // TODO: return YWTaskList
  @ApiOperation(value = "Destroy the universe", response = Object.class)
  @ApiImplicitParams(
      @ApiImplicitParam(
          name = "isForceDelete",
          value = "isForceDelete",
          type = "boolean",
          paramType = "query"))
  public Result destroy(UUID customerUUID, UUID universeUUID) {
    Customer customer = Customer.getOrBadRequest(customerUUID);
    Universe universe = Universe.getValidUniverseOrBadRequest(universeUUID, customer);

    boolean isForceDelete = false;
    if (request().getQueryString("isForceDelete") != null) {
      isForceDelete = Boolean.parseBoolean(request().getQueryString("isForceDelete"));
    }
    boolean isDeleteBackups = false;
    if (request().getQueryString("isDeleteBackups") != null) {
      isDeleteBackups = Boolean.parseBoolean(request().getQueryString("isDeleteBackups"));
    }

    LOG.info(
        "Destroy universe, customer uuid: {}, universe: {} [ {} ] ",
        customerUUID,
        universe.name,
        universeUUID);

    // Create the Commissioner task to destroy the universe.
    DestroyUniverse.Params taskParams = new DestroyUniverse.Params();
    taskParams.universeUUID = universeUUID;
    // There is no staleness of a delete request. Perform it even if the universe has changed.
    taskParams.expectedUniverseVersion = -1;
    taskParams.customerUUID = customerUUID;
    taskParams.isForceDelete = isForceDelete;
    taskParams.isDeleteBackups = isDeleteBackups;
    // Submit the task to destroy the universe.
    TaskType taskType = TaskType.DestroyUniverse;
    UniverseDefinitionTaskParams universeDetails = universe.getUniverseDetails();
    Cluster primaryCluster = universeDetails.getPrimaryCluster();
    if (primaryCluster.userIntent.providerType.equals(CloudType.kubernetes)) {
      taskType = TaskType.DestroyKubernetesUniverse;
    }

    // Update all current tasks for this universe to be marked as done if it is a force delete.
    if (isForceDelete) {
      markAllUniverseTasksAsCompleted(universe.universeUUID);
    }

    UUID taskUUID = commissioner.submit(taskType, taskParams);
    LOG.info("Submitted destroy universe for " + universeUUID + ", task uuid = " + taskUUID);

    // Add this task uuid to the user universe.
    CustomerTask.create(
        customer,
        universe.universeUUID,
        taskUUID,
        CustomerTask.TargetType.Universe,
        CustomerTask.TaskType.Delete,
        universe.name);

    LOG.info("Destroyed universe " + universeUUID + " for customer [" + customer.name + "]");

    ObjectNode response = Json.newObject();
    response.put("taskUUID", taskUUID.toString());
    auditService().createAuditEntry(ctx(), request(), taskUUID);
    return ApiResponse.success(response);
  }

  /**
   * API that queues a task to create a read-only cluster in an existing universe.
   *
   * @return result of the cluster create operation.
   */
  @ApiOperation(value = "clusterCreate", response = UniverseResp.class)
  @ApiImplicitParams(
      @ApiImplicitParam(
          name = "univ_def",
          value = "univ definition",
          dataType = "com.yugabyte.yw.forms.UniverseDefinitionTaskParams",
          paramType = "body",
          required = true))
  public Result clusterCreate(UUID customerUUID, UUID universeUUID) {
    Customer customer = Customer.getOrBadRequest(customerUUID);
    Universe universe = Universe.getValidUniverseOrBadRequest(universeUUID, customer);

    LOG.info("Create cluster for {} in {}.", customerUUID, universeUUID);
    // Get the user submitted form data.
    UniverseDefinitionTaskParams taskParams =
        bindFormDataToTaskParams(request(), UniverseDefinitionTaskParams.class);

    if (taskParams.clusters == null || taskParams.clusters.size() != 1) {
      throw new YWServiceException(
          BAD_REQUEST,
          "Invalid 'clusters' field/size: " + taskParams.clusters + " for " + universeUUID);
    }

    List<Cluster> newReadOnlyClusters = taskParams.clusters;
    List<Cluster> existingReadOnlyClusters = universe.getUniverseDetails().getReadOnlyClusters();
    LOG.info(
        "newReadOnly={}, existingRO={}.",
        newReadOnlyClusters.size(),
        existingReadOnlyClusters.size());

    if (existingReadOnlyClusters.size() > 0 && newReadOnlyClusters.size() > 0) {
      String errMsg = "Can only have one read-only cluster per universe for now.";
      LOG.error(errMsg);
      throw new YWServiceException(BAD_REQUEST, errMsg);
    }

    if (newReadOnlyClusters.size() != 1) {
      String errMsg =
          "Only one read-only cluster expected, but we got " + newReadOnlyClusters.size();
      LOG.error(errMsg);
      throw new YWServiceException(BAD_REQUEST, errMsg);
    }

    Cluster cluster = newReadOnlyClusters.get(0);
    if (cluster.uuid == null) {
      String errMsg = "UUID of read-only cluster should be non-null.";
      LOG.error(errMsg);
      throw new YWServiceException(BAD_REQUEST, errMsg);
    }

    if (cluster.clusterType != ClusterType.ASYNC) {
      String errMsg =
          "Read-only cluster type should be "
              + ClusterType.ASYNC
              + " but is "
              + cluster.clusterType;
      LOG.error(errMsg);
      throw new YWServiceException(BAD_REQUEST, errMsg);
    }

    // Set the provider code.
    Cluster c = taskParams.clusters.get(0);
    Provider provider = Provider.getOrBadRequest(UUID.fromString(c.userIntent.provider));
    c.userIntent.providerType = CloudType.valueOf(provider.code);
    c.validate();

    if (c.userIntent.providerType.equals(CloudType.kubernetes)) {
      try {
        checkK8sProviderAvailability(provider, customer);
      } catch (IllegalArgumentException e) {
        throw new YWServiceException(BAD_REQUEST, e.getMessage());
      }
    }

    updatePlacementInfo(taskParams.getNodesInCluster(c.uuid), c.placementInfo);

    // Submit the task to create the cluster.
    UUID taskUUID = commissioner.submit(TaskType.ReadOnlyClusterCreate, taskParams);
    LOG.info(
        "Submitted create cluster for {}:{}, task uuid = {}.",
        universe.universeUUID,
        universe.name,
        taskUUID);

    // Add this task uuid to the user universe.
    CustomerTask.create(
        customer,
        universe.universeUUID,
        taskUUID,
        CustomerTask.TargetType.Cluster,
        CustomerTask.TaskType.Create,
        universe.name);
    LOG.info(
        "Saved task uuid {} in customer tasks table for universe {}:{}",
        taskUUID,
        universe.universeUUID,
        universe.name);

    auditService().createAuditEntryWithReqBody(ctx(), taskUUID);
    return ApiResponse.success(createResp(universe, taskUUID));
  }

  /**
   * API that queues a task to delete a read-only cluster in an existing universe.
   *
   * @return result of the cluster delete operation.
   */
  @ApiOperation(value = "clusterDelete", response = UniverseResp.class)
  public Result clusterDelete(UUID customerUUID, UUID universeUUID, UUID clusterUUID) {
    Customer customer = Customer.getOrBadRequest(customerUUID);
    Universe universe = Universe.getValidUniverseOrBadRequest(universeUUID, customer);

    List<Cluster> existingReadOnlyClusters = universe.getUniverseDetails().getReadOnlyClusters();
    if (existingReadOnlyClusters.size() != 1) {
      String errMsg =
          "Expected just one read only cluster, but found " + existingReadOnlyClusters.size();
      LOG.error(errMsg);
      throw new YWServiceException(BAD_REQUEST, errMsg);
    }

    Cluster cluster = existingReadOnlyClusters.get(0);
    UUID uuid = cluster.uuid;
    if (!uuid.equals(clusterUUID)) {
      String errMsg =
          "Uuid " + clusterUUID + " to delete cluster not found, only " + uuid + " found.";
      LOG.error(errMsg);
      throw new YWServiceException(BAD_REQUEST, errMsg);
    }

    boolean isForceDelete = false;
    if (request().getQueryString("isForceDelete") != null) {
      isForceDelete = Boolean.parseBoolean(request().getQueryString("isForceDelete"));
    }

    // Create the Commissioner task to destroy the universe.
    ReadOnlyClusterDelete.Params taskParams = new ReadOnlyClusterDelete.Params();
    taskParams.universeUUID = universeUUID;
    taskParams.clusterUUID = clusterUUID;
    taskParams.isForceDelete = isForceDelete;
    taskParams.expectedUniverseVersion = universe.version;

    // Submit the task to delete the cluster.
    UUID taskUUID = commissioner.submit(TaskType.ReadOnlyClusterDelete, taskParams);
    LOG.info(
        "Submitted delete cluster for {} in {}, task uuid = {}.",
        clusterUUID,
        universe.name,
        taskUUID);

    // Add this task uuid to the user universe.
    CustomerTask.create(
        customer,
        universe.universeUUID,
        taskUUID,
        CustomerTask.TargetType.Cluster,
        CustomerTask.TaskType.Delete,
        universe.name);
    LOG.info(
        "Saved task uuid {} in customer tasks table for universe {}:{}",
        taskUUID,
        universe.universeUUID,
        universe.name);

    auditService().createAuditEntry(ctx(), request(), taskUUID);
    return ApiResponse.success(createResp(universe, taskUUID));
  }

  @ApiOperation(value = "universeCost", response = UniverseResourceDetails.class)
  public Result universeCost(UUID customerUUID, UUID universeUUID) {
    Customer customer = Customer.getOrBadRequest(customerUUID);
    Universe universe = Universe.getValidUniverseOrBadRequest(universeUUID, customer);

    return ApiResponse.success(
        Json.toJson(
            UniverseResourceDetails.create(
                universe.getUniverseDetails(), runtimeConfigFactory.globalRuntimeConf())));
  }

  @ApiOperation(
      value = "list universe cost for all universes",
      response = UniverseResourceDetails.class)
  public Result universeListCost(UUID customerUUID) {
    Customer customer = Customer.getOrBadRequest(customerUUID);
    Set<Universe> universeSet;
    try {
      universeSet = customer.getUniverses();
    } catch (RuntimeException e) {
      throw new YWServiceException(
          BAD_REQUEST, "No universe found for customer with ID: " + customerUUID);
    }
    List<UniverseResourceDetails> response = new ArrayList<>(universeSet.size());
    for (Universe universe : universeSet) {
      try {
        response.add(
            UniverseResourceDetails.create(
                universe.getUniverseDetails(), runtimeConfigFactory.globalRuntimeConf()));
      } catch (Exception e) {
        LOG.error("Could not add cost details for Universe with UUID: " + universe.universeUUID);
      }
    }
    return ApiResponse.success(response);
  }

  /**
   * API that queues a task to perform an upgrade and a subsequent rolling restart of a universe.
   *
   * @return result of the universe update operation.
   */
  // TODO: return YWTaskList
  @ApiOperation(value = "Upgrade  the universe", response = Object.class)
  @ApiImplicitParams(
      @ApiImplicitParam(
          name = "upgrade_params",
          value = "upgrade params",
          dataType = "com.yugabyte.yw.forms.UpgradeParams",
          required = true,
          paramType = "body"))
  public Result upgrade(UUID customerUUID, UUID universeUUID) {
    LOG.info("Upgrade {} for {}.", customerUUID, universeUUID);
    Customer customer = Customer.getOrBadRequest(customerUUID);
    Universe universe = Universe.getValidUniverseOrBadRequest(universeUUID, customer);

    // Bind upgrade params
    UpgradeParams taskParams = bindFormDataToTaskParams(request(), UpgradeParams.class);

    if (taskParams.taskType == null) {
      throw new YWServiceException(BAD_REQUEST, "task type is required");
    }

    if (taskParams.upgradeOption == UpgradeParams.UpgradeOption.ROLLING_UPGRADE
        && universe.nodesInTransit()) {
      throw new YWServiceException(
          BAD_REQUEST,
          "Cannot perform rolling upgrade of universe "
              + universeUUID
              + " as it has nodes in one of "
              + NodeDetails.IN_TRANSIT_STATES
              + " states.");
    }

    // TODO: we need to refactor this to read from cluster
    // instead of top level task param, for now just copy the master flag and tserver flag
    // from primary cluster.
    UserIntent primaryIntent = taskParams.getPrimaryCluster().userIntent;
    primaryIntent.masterGFlags = trimFlags(primaryIntent.masterGFlags);
    primaryIntent.tserverGFlags = trimFlags(primaryIntent.tserverGFlags);
    taskParams.masterGFlags = primaryIntent.masterGFlags;
    taskParams.tserverGFlags = primaryIntent.tserverGFlags;

    CustomerTask.TaskType customerTaskType;
    // Validate if any required params are missed based on the taskType
    switch (taskParams.taskType) {
      case Software:
        customerTaskType = CustomerTask.TaskType.UpgradeSoftware;
        if (taskParams.ybSoftwareVersion == null || taskParams.ybSoftwareVersion.isEmpty()) {
          throw new YWServiceException(
              BAD_REQUEST,
              "ybSoftwareVersion param is required for taskType: " + taskParams.taskType);
        }
        break;
      case GFlags:
        customerTaskType = CustomerTask.TaskType.UpgradeGflags;
        // TODO(BUG): This looks like a bug. This should check for empty instead of null.
        // Fixing this cause unit test to break. Leaving the TODO for now.
        if (taskParams.masterGFlags == null && taskParams.tserverGFlags == null) {
          throw new YWServiceException(
              BAD_REQUEST, "gflags param is required for taskType: " + taskParams.taskType);
        }
        UserIntent univIntent = universe.getUniverseDetails().getPrimaryCluster().userIntent;
        if (taskParams.masterGFlags != null
            && taskParams.masterGFlags.equals(univIntent.masterGFlags)
            && taskParams.tserverGFlags != null
            && taskParams.tserverGFlags.equals(univIntent.tserverGFlags)) {
          throw new YWServiceException(BAD_REQUEST, "Neither master nor tserver gflags changed.");
        }
        break;
      case Restart:
        customerTaskType = CustomerTask.TaskType.Restart;
        if (taskParams.upgradeOption != UpgradeParams.UpgradeOption.ROLLING_UPGRADE) {
          throw new YWServiceException(BAD_REQUEST, "Rolling restart has to be a ROLLING UPGRADE.");
        }
        break;
      case Certs:
        customerTaskType = CustomerTask.TaskType.UpdateCert;
        if (taskParams.certUUID == null) {
          throw new YWServiceException(
              BAD_REQUEST, "certUUID is required for taskType: " + taskParams.taskType);
        }
        if (!taskParams.getPrimaryCluster().userIntent.providerType.equals(CloudType.onprem)) {
          throw new YWServiceException(
              BAD_REQUEST, "Certs can only be rotated for onprem." + taskParams.taskType);
        }
        CertificateInfo cert = CertificateInfo.get(taskParams.certUUID);
        if (cert.certType != CertificateInfo.Type.CustomCertHostPath) {
          throw new YWServiceException(
              BAD_REQUEST, "Need a custom cert. Cannot use self-signed." + taskParams.taskType);
        }
        cert = CertificateInfo.get(universe.getUniverseDetails().rootCA);
        if (cert.certType != CertificateInfo.Type.CustomCertHostPath) {
          throw new YWServiceException(
              BAD_REQUEST, "Only custom certs can be rotated." + taskParams.taskType);
        }
        break;
      default:
        throw new YWServiceException(BAD_REQUEST, "Unexpected value: " + taskParams.taskType);
    }

    LOG.info("Got task type {}", customerTaskType.toString());
    taskParams.universeUUID = universe.universeUUID;
    taskParams.expectedUniverseVersion = universe.version;

    LOG.info(
        "Found universe {} : name={} at version={}.",
        universe.universeUUID,
        universe.name,
        universe.version);

    Map<String, String> universeConfig = universe.getConfig();
    TaskType taskType = TaskType.UpgradeUniverse;
    if (taskParams.getPrimaryCluster().userIntent.providerType.equals(CloudType.kubernetes)) {
      taskType = TaskType.UpgradeKubernetesUniverse;
      if (!universeConfig.containsKey(Universe.HELM2_LEGACY)) {
        throw new YWServiceException(
            BAD_REQUEST,
            "Cannot perform upgrade operation on universe. "
                + universeUUID
                + " as it is not helm 3 compatible. "
                + "Manually migrate the deployment to helm3 "
                + "and then mark the universe as helm 3 compatible.");
      }
    }

    taskParams.rootCA = universe.getUniverseDetails().rootCA;
    if (!CertificateInfo.isCertificateValid(taskParams.rootCA)) {
      String errMsg =
          String.format(
              "The certificate %s needs info. Update the cert and retry.",
              CertificateInfo.get(taskParams.rootCA).label);
      LOG.error(errMsg);
      throw new YWServiceException(BAD_REQUEST, errMsg);
    }

    UUID taskUUID = commissioner.submit(taskType, taskParams);
    LOG.info(
        "Submitted upgrade universe for {} : {}, task uuid = {}.",
        universe.universeUUID,
        universe.name,
        taskUUID);

    // Add this task uuid to the user universe.
    CustomerTask.create(
        customer,
        universe.universeUUID,
        taskUUID,
        CustomerTask.TargetType.Universe,
        customerTaskType,
        universe.name);
    LOG.info(
        "Saved task uuid {} in customer tasks table for universe {} : {}.",
        taskUUID,
        universe.universeUUID,
        universe.name);
    ObjectNode resultNode = Json.newObject();
    resultNode.put("taskUUID", taskUUID.toString());
    auditService().createAuditEntryWithReqBody(ctx(), taskUUID);
    return Results.status(OK, resultNode);
  }

  /**
   * API that checks the status of the the tservers and masters in the universe.
   *
   * @return result of the universe status operation.
   */
  @ApiOperation(value = "Status of the Universe", response = Object.class)
  public Result status1(UUID customerUUID, UUID universeUUID) {
    Customer customer = Customer.getOrBadRequest(customerUUID);
    Universe universe = Universe.getValidUniverseOrBadRequest(universeUUID, customer);

    // Get alive status
    JsonNode result;
    try {
      result = PlacementInfoUtil.getUniverseAliveStatus(universe, metricQueryHelper);
    } catch (RuntimeException e) {
      // TODO(API) dig deeper and find root cause of RuntimeException
      throw new YWServiceException(BAD_REQUEST, e.getMessage());
    }
    if (result.has("error")) throw new YWServiceException(BAD_REQUEST, result.get("error"));
    return ApiResponse.success(result);
  }

  /**
   * API that checks the health of all the tservers and masters in the universe, as well as certain
   * conditions on the machines themselves, such as disk utilization, presence of FATAL or core
   * files, etc.
   *
   * @return result of the checker script
   */
  @ApiOperation(value = "health Check", response = Object.class)
  public Result healthCheck(UUID customerUUID, UUID universeUUID) {
    Customer customer = Customer.getOrBadRequest(customerUUID);
    Universe.getValidUniverseOrBadRequest(universeUUID, customer);

    // Get alive status
    try {
      List<HealthCheck> checks = HealthCheck.getAll(universeUUID);
      ArrayNode detailsList = Json.newArray();
      for (HealthCheck check : checks) {
        detailsList.add(Json.stringify(Json.parse(check.detailsJson)));
      }
      return ApiResponse.success(detailsList);
    } catch (RuntimeException e) {
      throw new YWServiceException(BAD_REQUEST, e.getMessage());
    }
  }

  /**
   * Endpoint to retrieve the IP of the master leader for a given universe.
   *
   * @param customerUUID UUID of Customer the target Universe belongs to.
   * @param universeUUID UUID of Universe to retrieve the master leader private IP of.
   * @return The private IP of the master leader.
   */
  @ApiOperation(value = "getMasterLeaderIP", response = Object.class)
  public Result getMasterLeaderIP(UUID customerUUID, UUID universeUUID) {
    Customer customer = Customer.getOrBadRequest(customerUUID);
    Universe universe = Universe.getValidUniverseOrBadRequest(universeUUID, customer);

    final String hostPorts = universe.getMasterAddresses();
    String certificate = universe.getCertificateNodetoNode();
    YBClient client = null;
    // Get and return Leader IP
    try {
      client = ybService.getClient(hostPorts, certificate);
      ObjectNode result =
          Json.newObject().put("privateIP", client.getLeaderMasterHostAndPort().getHost());
      ybService.closeClient(client, hostPorts);
      return ApiResponse.success(result);
    } catch (RuntimeException e) {
      throw new YWServiceException(BAD_REQUEST, e.getMessage());
    } finally {
      ybService.closeClient(client, hostPorts);
    }
  }

  @ApiOperation(value = "updateDiskSize", response = Object.class)
  public Result updateDiskSize(UUID customerUUID, UUID universeUUID) {
    LOG.info("Disk Size Increase {} for {}.", customerUUID, universeUUID);
    Customer customer = Customer.getOrBadRequest(customerUUID);
    Universe universe = Universe.getValidUniverseOrBadRequest(universeUUID, customer);

    // Bind disk size increase data.
    DiskIncreaseFormData taskParams =
        bindFormDataToTaskParams(request(), DiskIncreaseFormData.class);

    if (taskParams.size == 0) {
      throw new YWServiceException(BAD_REQUEST, "Size cannot be 0.");
    }

    UserIntent primaryIntent = taskParams.getPrimaryCluster().userIntent;
    if (taskParams.size <= primaryIntent.deviceInfo.volumeSize) {
      throw new YWServiceException(BAD_REQUEST, "Size can only be increased.");
    }
    if (primaryIntent.deviceInfo.storageType == PublicCloudConstants.StorageType.Scratch) {
      throw new YWServiceException(BAD_REQUEST, "Scratch type disk cannot be modified.");
    }
    if (taskParams.getPrimaryCluster().isAwsClusterWithEphemeralStorage()) {
      throw new YWServiceException(BAD_REQUEST, "Cannot modify instance volumes.");
    }

    primaryIntent.deviceInfo.volumeSize = taskParams.size;
    taskParams.universeUUID = universe.universeUUID;
    taskParams.expectedUniverseVersion = universe.version;
    LOG.info(
        "Found universe {} : name={} at version={}.",
        universe.universeUUID,
        universe.name,
        universe.version);

    TaskType taskType = TaskType.UpdateDiskSize;
    if (taskParams.getPrimaryCluster().userIntent.providerType.equals(CloudType.kubernetes)) {
      throw new YWServiceException(BAD_REQUEST, "Kubernetes disk size increase not yet supported.");
    }

    UUID taskUUID = commissioner.submit(taskType, taskParams);
    LOG.info(
        "Submitted update disk universe for {} : {}, task uuid = {}.",
        universe.universeUUID,
        universe.name,
        taskUUID);

    CustomerTask.TaskType customerTaskType = CustomerTask.TaskType.UpdateDiskSize;

    // Add this task uuid to the user universe.
    CustomerTask.create(
        customer,
        universe.universeUUID,
        taskUUID,
        CustomerTask.TargetType.Universe,
        customerTaskType,
        universe.name);
    LOG.info(
        "Saved task uuid {} in customer tasks table for universe {} : {}.",
        taskUUID,
        universe.universeUUID,
        universe.name);
    ObjectNode resultNode = Json.newObject();
    resultNode.put("taskUUID", taskUUID.toString());
    auditService().createAuditEntryWithReqBody(ctx(), taskUUID);
    return Results.status(OK, resultNode);
  }

  @ApiOperation(value = "getLiveQueries", response = Object.class)
  public Result getLiveQueries(UUID customerUUID, UUID universeUUID) {
    LOG.info("Live queries for customer {}, universe {}", customerUUID, universeUUID);
    Customer customer = Customer.getOrBadRequest(customerUUID);
    Universe universe = Universe.getValidUniverseOrBadRequest(universeUUID, customer);

    try {
      JsonNode resultNode = queryHelper.liveQueries(universe);
      return Results.status(OK, resultNode);
    } catch (NullPointerException e) {
      LOG.error("Universe does not have a private IP or DNS", e);
      throw new YWServiceException(INTERNAL_SERVER_ERROR, "Universe failed to fetch live queries");
    } catch (Throwable t) {
      LOG.error("Error retrieving queries for universe", t);
      throw new YWServiceException(INTERNAL_SERVER_ERROR, t.getMessage());
    }
  }

  @ApiOperation(value = "getSlowQueries", response = Object.class)
  public Result getSlowQueries(UUID customerUUID, UUID universeUUID) {
    LOG.info("Slow queries for customer {}, universe {}", customerUUID, universeUUID);
    Customer customer = Customer.getOrBadRequest(customerUUID);
    Universe universe = Universe.getValidUniverseOrBadRequest(universeUUID, customer);

    try {
      JsonNode resultNode = queryHelper.slowQueries(universe);
      return Results.status(OK, resultNode);
    } catch (NullPointerException e) {
      LOG.error("Universe does not have a private IP or DNS", e);
      throw new YWServiceException(INTERNAL_SERVER_ERROR, "Universe failed to fetch slow queries");
    } catch (Throwable t) {
      LOG.error("Error retrieving queries for universe", t);
      throw new YWServiceException(INTERNAL_SERVER_ERROR, t.getMessage());
    }
  }

  public Result resetSlowQueries(UUID customerUUID, UUID universeUUID) {
    LOG.info("Resetting Slow queries for customer {}, universe {}", customerUUID, universeUUID);
    Customer customer = Customer.getOrBadRequest(customerUUID);
    Universe universe = Universe.getValidUniverseOrBadRequest(universeUUID, customer);

    try {
      JsonNode resultNode = queryHelper.resetQueries(universe);
      return Results.status(OK, resultNode);
    } catch (NullPointerException e) {
      throw new YWServiceException(INTERNAL_SERVER_ERROR, "Failed reach node, invalid IP or DNS.");
    } catch (Throwable t) {
      LOG.error("Error resetting slow queries for universe", t);
      throw new YWServiceException(INTERNAL_SERVER_ERROR, t.getMessage());
    }
  }

  @ApiOperation(value = "markAllUniverseTasksAsCompleted", response = Object.class)
  private void markAllUniverseTasksAsCompleted(UUID universeUUID) {
    List<CustomerTask> existingTasks = CustomerTask.findIncompleteByTargetUUID(universeUUID);
    for (CustomerTask task : existingTasks) {
      task.markAsCompleted();
      TaskInfo taskInfo = TaskInfo.get(task.getTaskUUID());
      if (taskInfo != null) {
        taskInfo.setTaskState(TaskInfo.State.Failure);
        taskInfo.save();
      }
    }
  }

  /**
   * Throw an exception if the given provider has an AZ with KUBENAMESPACE in the config and the
   * provdier has a cluster associated with it. Providers with namespace setting don't support
   * multiple clusters.
   *
   * @param providerToCheck Provider object
   */
  private static void checkK8sProviderAvailability(Provider providerToCheck, Customer customer) {
    boolean isNamespaceSet = false;
    for (Region r : Region.getByProvider(providerToCheck.uuid)) {
      for (AvailabilityZone az : AvailabilityZone.getAZsForRegion(r.uuid)) {
        if (az.getConfig().containsKey("KUBENAMESPACE")) {
          isNamespaceSet = true;
        }
      }
    }

    if (isNamespaceSet) {
      for (UUID universeUUID : Universe.getAllUUIDs(customer)) {
        Universe u = Universe.getOrBadRequest(universeUUID);
        List<Cluster> clusters = u.getUniverseDetails().getReadOnlyClusters();
        clusters.add(u.getUniverseDetails().getPrimaryCluster());
        for (Cluster c : clusters) {
          UUID providerUUID = UUID.fromString(c.userIntent.provider);
          if (providerUUID.equals(providerToCheck.uuid)) {
            String msg =
                "Universe "
                    + u.name
                    + " ("
                    + u.universeUUID
                    + ") already exists with provider "
                    + providerToCheck.name
                    + " ("
                    + providerToCheck.uuid
                    + "). Only one universe can be created with providers having KUBENAMESPACE set "
                    + "in the AZ config.";
            LOG.error(msg);
            throw new IllegalArgumentException(msg);
          }
        }
      }
    }
  }

  private UniverseResp createResp(Universe universe, UUID taskUUID) {
    UniverseResourceDetails resourceDetails =
        UniverseResourceDetails.create(
            universe.getUniverseDetails(), runtimeConfigFactory.globalRuntimeConf());
    String sampleAppCommandTxt = getManifest(universe);
    return new UniverseResp(universe, taskUUID, resourceDetails, sampleAppCommandTxt);
  }

  /** Returns the command to run the sample apps in the universe. */
  private String getManifest(Universe universe) {
    Set<NodeDetails> nodeDetailsSet = universe.getUniverseDetails().nodeDetailsSet;
    Integer yqlServerRpcPort = universe.getUniverseDetails().communicationPorts.yqlServerRpcPort;
    StringBuilder nodeBuilder = new StringBuilder();
    Cluster cluster = universe.getUniverseDetails().getPrimaryCluster();
    String sampleAppCommand;
    boolean isKubernetesProvider = cluster.userIntent.providerType.equals(CloudType.kubernetes);
    // Building --nodes param value of the command
    nodeDetailsSet
        .stream()
        .filter(
            nodeDetails ->
                (nodeDetails.isTserver
                    && nodeDetails.state != null
                    && nodeDetails.state.name().equals("Live")))
        .forEach(
            nodeDetails ->
                nodeBuilder.append(
                    String.format(
                        nodeBuilder.length() == 0 ? "%s:%s" : ",%s:%s",
                        nodeDetails.cloudInfo.private_ip,
                        yqlServerRpcPort)));
    // If node to client TLS is enabled.
    if (cluster.userIntent.enableClientToNodeEncrypt) {
      String randomFileName = UUID.randomUUID().toString();
      if (isKubernetesProvider) {
        String certContent = CertificateHelper.getCertPEM(universe.getUniverseDetails().rootCA);
        Yaml yaml = new Yaml();
        String sampleAppCommandTxt =
            yaml.dump(
                yaml.load(
                    Play.application()
                        .resourceAsStream("templates/k8s-sample-app-command-pod.yml")));
        sampleAppCommandTxt =
            sampleAppCommandTxt
                .replace("<root_cert_content>", certContent)
                .replace("<nodes>", nodeBuilder.toString());

        String secretCommandTxt =
            yaml.dump(
                yaml.load(
                    Play.application()
                        .resourceAsStream("templates/k8s-sample-app-command-secret.yml")));
        secretCommandTxt =
            secretCommandTxt
                .replace("<root_cert_content>", certContent)
                .replace("<nodes>", nodeBuilder.toString());
        sampleAppCommandTxt = secretCommandTxt + "\n---\n" + sampleAppCommandTxt;
        sampleAppCommand = "echo -n \"" + sampleAppCommandTxt + "\" | kubectl create -f -";
      } else {
        sampleAppCommand =
            ("export FILE_NAME=/tmp/<file_name>.crt "
                    + "&& echo -n \"<root_cert_content>\" > "
                    + "$FILE_NAME && docker run -d -v $FILE_NAME:/home/root.crt:ro yugabytedb/yb-sample-apps "
                    + "--workload CassandraKeyValue --nodes <nodes> --ssl_cert /home/root.crt")
                .replace(
                    "<root_cert_content>",
                    universe.getUniverseDetails().rootCA != null
                        ? CertificateHelper.getCertPEMFileContents(
                            universe.getUniverseDetails().rootCA)
                        : "")
                .replace("<nodes>", nodeBuilder.toString());
      }
      sampleAppCommand = sampleAppCommand.replace("<file_name>", randomFileName);
    } else {
      // If TLS is disabled.
      if (isKubernetesProvider) {
        String commandTemplateKubeCtl =
            "kubectl run "
                + "--image=yugabytedb/yb-sample-apps yb-sample-apps "
                + "-- --workload CassandraKeyValue --nodes <nodes>";
        sampleAppCommand = commandTemplateKubeCtl.replace("<nodes>", nodeBuilder.toString());
      } else {
        String commandTemplateDocker =
            "docker run "
                + "-d yugabytedb/yb-sample-apps "
                + "--workload CassandraKeyValue --nodes <nodes>";
        sampleAppCommand = commandTemplateDocker.replace("<nodes>", nodeBuilder.toString());
      }
    }
    return sampleAppCommand;
  }
}
