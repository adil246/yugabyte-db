// Copyright (c) Yugabyte, Inc.
package com.yugabyte.yw.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.inject.Inject;
import com.typesafe.config.Config;
import com.yugabyte.yw.cloud.AWSInitializer;
import com.yugabyte.yw.cloud.AZUInitializer;
import com.yugabyte.yw.cloud.CloudAPI;
import com.yugabyte.yw.cloud.GCPInitializer;
import com.yugabyte.yw.commissioner.Commissioner;
import com.yugabyte.yw.commissioner.Common;
import com.yugabyte.yw.commissioner.tasks.CloudBootstrap;
import com.yugabyte.yw.common.*;
import com.yugabyte.yw.forms.CloudBootstrapFormData;
import com.yugabyte.yw.forms.CloudProviderFormData;
import com.yugabyte.yw.forms.KubernetesProviderFormData;
import com.yugabyte.yw.forms.KubernetesProviderFormData.RegionData;
import com.yugabyte.yw.forms.KubernetesProviderFormData.RegionData.ZoneData;
import com.yugabyte.yw.forms.YWResults;
import com.yugabyte.yw.models.*;
import com.yugabyte.yw.models.helpers.TaskType;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.api.Play;
import play.data.Form;
import play.libs.Json;
import play.mvc.Result;

import java.io.File;
import java.io.IOException;
import java.util.*;

import javax.persistence.NonUniqueResultException;

import static com.yugabyte.yw.common.ConfigHelper.ConfigType.DockerInstanceTypeMetadata;
import static com.yugabyte.yw.common.ConfigHelper.ConfigType.DockerRegionMetadata;
import static play.mvc.Http.Status.BAD_REQUEST;

@Api("Provider")
public class CloudProviderController extends AuthenticatedController {
  private final Config config;

  @Inject
  public CloudProviderController(Config config) {
    this.config = config;
  }

  public static final Logger LOG = LoggerFactory.getLogger(CloudProviderController.class);

  private static final JsonNode KUBERNETES_CLOUD_INSTANCE_TYPE =
      Json.parse("{\"instanceTypeCode\": \"cloud\", \"numCores\": 0.5, \"memSizeGB\": 1.5}");
  private static final JsonNode KUBERNETES_DEV_INSTANCE_TYPE =
      Json.parse("{\"instanceTypeCode\": \"dev\", \"numCores\": 0.5, \"memSizeGB\": 0.5}");
  private static final JsonNode KUBERNETES_INSTANCE_TYPES =
      Json.parse(
          "["
              + "{\"instanceTypeCode\": \"xsmall\", \"numCores\": 2, \"memSizeGB\": 4},"
              + "{\"instanceTypeCode\": \"small\", \"numCores\": 4, \"memSizeGB\": 7.5},"
              + "{\"instanceTypeCode\": \"medium\", \"numCores\": 8, \"memSizeGB\": 15},"
              + "{\"instanceTypeCode\": \"large\", \"numCores\": 16, \"memSizeGB\": 15},"
              + "{\"instanceTypeCode\": \"xlarge\", \"numCores\": 32, \"memSizeGB\": 30}]");

  @Inject ValidatingFormFactory formFactory;

  @Inject AWSInitializer awsInitializer;

  @Inject GCPInitializer gcpInitializer;

  @Inject AZUInitializer azuInitializer;

  @Inject Commissioner commissioner;

  @Inject ConfigHelper configHelper;

  @Inject AccessManager accessManager;

  @Inject DnsManager dnsManager;

  @Inject private play.Environment environment;

  @Inject CloudAPI.Factory cloudAPIFactory;

  /**
   * GET endpoint for listing providers
   *
   * @return JSON response with provider's
   */
  @ApiOperation(value = "listProvider", response = Provider.class, responseContainer = "List")
  public Result list(UUID customerUUID) {
    return ApiResponse.success(Provider.getAll(customerUUID));
  }

  // This endpoint we are using only for deleting provider for integration test purpose. our
  // UI should call cleanup endpoint.
  @ApiOperation(value = "deleteProvider")
  public Result delete(UUID customerUUID, UUID providerUUID) {
    Provider provider = Provider.getOrBadRequest(customerUUID, providerUUID);
    Customer customer = Customer.getOrBadRequest(customerUUID);
    if (customer.getUniversesForProvider(providerUUID).size() > 0) {
      throw new YWServiceException(BAD_REQUEST, "Cannot delete Provider with Universes");
    }

    // TODO: move this to task framework
    for (AccessKey accessKey : AccessKey.getAll(providerUUID)) {
      if (!accessKey.getKeyInfo().provisionInstanceScript.isEmpty()) {
        new File(accessKey.getKeyInfo().provisionInstanceScript).delete();
      }
      accessManager.deleteKeyByProvider(provider, accessKey.getKeyCode());
      accessKey.delete();
    }
    NodeInstance.deleteByProvider(providerUUID);
    InstanceType.deleteInstanceTypesForProvider(provider, config);
    provider.delete();
    auditService().createAuditEntry(ctx(), request());
    return YWResults.YWSuccess.withMessage("Deleted provider: " + providerUUID);
  }

  /**
   * POST endpoint for creating new providers
   *
   * @return JSON response of newly created provider
   */
  @ApiOperation(value = "createProvider", response = Provider.class)
  @ApiImplicitParams(
      @ApiImplicitParam(
          name = "providerFormData",
          value = "provider form data",
          paramType = "body",
          dataType = "com.yugabyte.yw.forms.CloudProviderFormData",
          required = true))
  public Result create(UUID customerUUID) throws IOException {
    Form<CloudProviderFormData> formData =
        formFactory.getFormDataOrBadRequest(CloudProviderFormData.class);

    Common.CloudType providerCode = formData.get().code;
    Provider existentProvider = Provider.get(customerUUID, formData.get().name, providerCode);
    if (existentProvider != null) {
      return ApiResponse.error(
          BAD_REQUEST,
          String.format("Provider with the name %s already exists", formData.get().name));
    }

    // Since the Map<String, String> doesn't get parsed, so for now we would just
    // parse it from the requestBody
    JsonNode requestBody = request().body().asJson();
    Map<String, String> config = processConfig(requestBody, providerCode);

    Provider provider = Provider.create(customerUUID, providerCode, formData.get().name, config);
    if (!config.isEmpty()) {
      String hostedZoneId = provider.getHostedZoneId();
      switch (provider.code) {
        case "aws":
          CloudAPI cloudAPI = cloudAPIFactory.get(provider.code);
          if (cloudAPI != null && !cloudAPI.isValidCreds(config, formData.get().region)) {
            provider.delete();
            throw new YWServiceException(BAD_REQUEST, "Invalid AWS Credentials.");
          }
          if (hostedZoneId != null && hostedZoneId.length() != 0) {
            return validateHostedZoneUpdate(provider, hostedZoneId);
          }
          break;
        case "gcp":
          updateGCPConfig(provider, config);
          break;
        case "kubernetes":
          updateKubeConfig(provider, config, false);
          try {
            createKubernetesInstanceTypes(provider, customerUUID);
          } catch (javax.persistence.PersistenceException ex) {
            // TODO: make instance types more multi-tenant friendly...
          }
          break;
        case "azu":
          if (hostedZoneId != null && hostedZoneId.length() != 0) {
            return validateHostedZoneUpdate(provider, hostedZoneId);
          }
          break;
      }
    }
    auditService().createAuditEntry(ctx(), request(), Json.toJson(formData.data()));
    return ApiResponse.success(provider);
  }

  // For creating the a multi-cluster kubernetes provider.
  public Result createKubernetes(UUID customerUUID) throws IOException {
    JsonNode requestBody = request().body().asJson();
    ObjectMapper mapper = new ObjectMapper();
    KubernetesProviderFormData formData =
        mapper.treeToValue(requestBody, KubernetesProviderFormData.class);

    Common.CloudType providerCode = formData.code;
    if (!providerCode.equals(Common.CloudType.kubernetes)) {
      throw new YWServiceException(
          BAD_REQUEST, "API for only kubernetes provider creation: " + providerCode);
    }

    boolean hasConfig = formData.config.containsKey("KUBECONFIG_NAME");
    if (formData.regionList.isEmpty()) {
      throw new YWServiceException(BAD_REQUEST, "Need regions in provider");
    }
    for (RegionData rd : formData.regionList) {
      if (rd.config != null) {
        if (rd.config.containsKey("KUBECONFIG_NAME")) {
          if (hasConfig) {
            throw new YWServiceException(BAD_REQUEST, "Kubeconfig can't be at two levels");
          } else {
            hasConfig = true;
          }
        }
      }
      if (rd.zoneList.isEmpty()) {
        throw new YWServiceException(BAD_REQUEST, "No zone provided in region");
      }
      for (ZoneData zd : rd.zoneList) {
        if (zd.config != null) {
          if (zd.config.containsKey("KUBECONFIG_NAME")) {
            if (hasConfig) {
              throw new YWServiceException(BAD_REQUEST, "Kubeconfig can't be at two levels");
            }
          } else if (!hasConfig) {
            throw new YWServiceException(BAD_REQUEST, "No Kubeconfig found for zone(s)");
          }
        }
      }
      hasConfig = formData.config.containsKey("KUBECONFIG_NAME");
    }

    Provider provider = null;
    Map<String, String> config = formData.config;
    provider = Provider.create(customerUUID, providerCode, formData.name);
    boolean isConfigInProvider = updateKubeConfig(provider, config, false);
    if (isConfigInProvider) {}
    List<RegionData> regionList = formData.regionList;
    for (RegionData rd : regionList) {
      Map<String, String> regionConfig = rd.config;
      Region region = Region.create(provider, rd.code, rd.name, null, rd.latitude, rd.longitude);
      boolean isConfigInRegion = updateKubeConfig(provider, region, regionConfig, false);
      if (isConfigInRegion) {}
      for (ZoneData zd : rd.zoneList) {
        Map<String, String> zoneConfig = zd.config;
        AvailabilityZone az = AvailabilityZone.create(region, zd.code, zd.name, null);
        boolean isConfigInZone = updateKubeConfig(provider, region, az, zoneConfig, false);
        if (isConfigInZone) {}
      }
    }
    try {
      createKubernetesInstanceTypes(provider, customerUUID);
    } catch (javax.persistence.PersistenceException ex) {
      provider.delete();
      throw new YWServiceException(INTERNAL_SERVER_ERROR, "Couldn't create instance types");
      // TODO: make instance types more multi-tenant friendly...
    }
    auditService().createAuditEntry(ctx(), request(), requestBody);
    return ApiResponse.success(provider);
  }

  private boolean updateKubeConfig(Provider provider, Map<String, String> config, boolean edit)
      throws IOException {
    return updateKubeConfig(provider, null, config, edit);
  }

  private boolean updateKubeConfig(
      Provider provider, Region region, Map<String, String> config, boolean edit)
      throws IOException {
    return updateKubeConfig(provider, region, null, config, edit);
  }

  private boolean updateKubeConfig(
      Provider provider,
      Region region,
      AvailabilityZone zone,
      Map<String, String> config,
      boolean edit)
      throws IOException {
    String kubeConfigFile = null;
    String pullSecretFile = null;

    if (config == null) {
      return false;
    }

    String path = provider.uuid.toString();
    if (region != null) {
      path = path + "/" + region.uuid.toString();
      if (zone != null) {
        path = path + "/" + zone.uuid.toString();
      }
    }
    boolean hasKubeConfig = config.containsKey("KUBECONFIG_NAME");
    if (hasKubeConfig) {
      kubeConfigFile = accessManager.createKubernetesConfig(path, config, edit);

      // Remove the kubeconfig file related configs from provider config.
      config.remove("KUBECONFIG_NAME");
      config.remove("KUBECONFIG_CONTENT");

      if (kubeConfigFile != null) {
        config.put("KUBECONFIG", kubeConfigFile);
      }
    }

    if (region == null) {
      if (config.containsKey("KUBECONFIG_PULL_SECRET_NAME")) {
        if (config.get("KUBECONFIG_PULL_SECRET_NAME") != null) {
          pullSecretFile = accessManager.createPullSecret(provider.uuid, config, edit);
        }
      }
      config.remove("KUBECONFIG_PULL_SECRET_NAME");
      config.remove("KUBECONFIG_PULL_SECRET_CONTENT");
      if (pullSecretFile != null) {
        config.put("KUBECONFIG_PULL_SECRET", pullSecretFile);
      }

      provider.setConfig(config);
    } else if (zone == null) {
      region.setConfig(config);
    } else {
      zone.updateConfig(config);
    }
    return hasKubeConfig;
  }

  private void updateGCPConfig(Provider provider, Map<String, String> config) throws IOException {
    // Remove the key to avoid generating a credentials file unnecessarily.
    config.remove("GCE_HOST_PROJECT");
    // If we were not given a config file, then no need to do anything here.
    if (config.isEmpty()) {
      return;
    }

    String gcpCredentialsFile =
        accessManager.createCredentialsFile(provider.uuid, Json.toJson(config));

    Map<String, String> newConfig = new HashMap<String, String>();
    if (config.get("project_id") != null) {
      newConfig.put("GCE_PROJECT", config.get("project_id"));
    }
    if (config.get("client_email") != null) {
      newConfig.put("GCE_EMAIL", config.get("client_email"));
    }
    if (gcpCredentialsFile != null) {
      newConfig.put("GOOGLE_APPLICATION_CREDENTIALS", gcpCredentialsFile);
    }
    provider.setConfig(newConfig);
    provider.save();
  }

  private void createKubernetesInstanceTypes(Provider provider, UUID customerUUID) {
    Customer customer = Customer.get(customerUUID);
    KUBERNETES_INSTANCE_TYPES.forEach(
        (instanceType -> {
          InstanceType.InstanceTypeDetails idt = new InstanceType.InstanceTypeDetails();
          idt.setVolumeDetailsList(1, 100, InstanceType.VolumeType.SSD);
          InstanceType.upsert(
              provider.uuid,
              instanceType.get("instanceTypeCode").asText(),
              instanceType.get("numCores").asDouble(),
              instanceType.get("memSizeGB").asDouble(),
              idt);
        }));
    if (environment.isDev()) {
      InstanceType.InstanceTypeDetails idt = new InstanceType.InstanceTypeDetails();
      idt.setVolumeDetailsList(1, 100, InstanceType.VolumeType.SSD);
      InstanceType.upsert(
          provider.uuid,
          KUBERNETES_DEV_INSTANCE_TYPE.get("instanceTypeCode").asText(),
          KUBERNETES_DEV_INSTANCE_TYPE.get("numCores").asDouble(),
          KUBERNETES_DEV_INSTANCE_TYPE.get("memSizeGB").asDouble(),
          idt);
    }
    if (customer.code.equals("cloud")) {
      InstanceType.InstanceTypeDetails idt = new InstanceType.InstanceTypeDetails();
      idt.setVolumeDetailsList(1, 5, InstanceType.VolumeType.SSD);
      InstanceType.upsert(
          provider.uuid,
          KUBERNETES_CLOUD_INSTANCE_TYPE.get("instanceTypeCode").asText(),
          KUBERNETES_CLOUD_INSTANCE_TYPE.get("numCores").asDouble(),
          KUBERNETES_CLOUD_INSTANCE_TYPE.get("memSizeGB").asDouble(),
          idt);
    }
  }

  // TODO: This is temporary endpoint, so we can setup docker, will move this
  // to standard provider bootstrap route soon.
  public Result setupDocker(UUID customerUUID) {
    Customer customer = Customer.getOrBadRequest(customerUUID);

    List<Provider> providerList = Provider.get(customerUUID, Common.CloudType.docker);
    if (!providerList.isEmpty()) {
      return ApiResponse.success(providerList.get(0));
    }

    Provider newProvider = Provider.create(customerUUID, Common.CloudType.docker, "Docker");
    Map<String, Object> regionMetadata = configHelper.getConfig(DockerRegionMetadata);
    regionMetadata.forEach(
        (regionCode, metadata) -> {
          Region region = Region.createWithMetadata(newProvider, regionCode, Json.toJson(metadata));
          Arrays.asList("a", "b", "c")
              .forEach(
                  (zoneSuffix) -> {
                    String zoneName = regionCode + zoneSuffix;
                    AvailabilityZone.create(region, zoneName, zoneName, "yugabyte-bridge");
                  });
        });
    Map<String, Object> instanceTypeMetadata = configHelper.getConfig(DockerInstanceTypeMetadata);
    instanceTypeMetadata.forEach(
        (itCode, metadata) ->
            InstanceType.createWithMetadata(newProvider.uuid, itCode, Json.toJson(metadata)));
    auditService().createAuditEntry(ctx(), request());
    return ApiResponse.success(newProvider);
  }

  public Result initialize(UUID customerUUID, UUID providerUUID) {
    Provider provider = Provider.getOrBadRequest(customerUUID, providerUUID);
    if (provider.code.equals("gcp")) {
      return gcpInitializer.initialize(customerUUID, providerUUID);
    } else if (provider.code.equals("azu")) {
      return azuInitializer.initialize(customerUUID, providerUUID);
    }
    return awsInitializer.initialize(customerUUID, providerUUID);
  }

  @ApiOperation(value = "bootstrap", response = YWResults.YWTask.class)
  @ApiImplicitParams(
      @ApiImplicitParam(
          value = "bootstrap params",
          dataType = "com.yugabyte.yw.commissioner.tasks.CloudBootstrap$Params",
          paramType = "body",
          required = true))
  public Result bootstrap(UUID customerUUID, UUID providerUUID) {
    // TODO(bogdan): Need to manually parse maps, maybe add try/catch on parse?
    JsonNode requestBody = request().body().asJson();
    CloudBootstrap.Params taskParams = Json.fromJson(requestBody, CloudBootstrap.Params.class);

    Provider provider = Provider.getOrBadRequest(customerUUID, providerUUID);
    Customer customer = Customer.getOrBadRequest(customerUUID);
    // Set the top-level provider info.
    taskParams.providerUUID = providerUUID;
    if (taskParams.destVpcId != null && !taskParams.destVpcId.isEmpty()) {
      if (provider.code.equals("gcp")) {
        // We need to save the destVpcId into the provider config, because we'll need it during
        // instance creation. Technically, we could make it a ybcloud parameter, but we'd still need
        // to
        // store it somewhere and the config is the easiest place to put it. As such, since all the
        // config is loaded up as env vars anyway, might as well use in in devops like that...
        Map<String, String> config = provider.getConfig();
        config.put("CUSTOM_GCE_NETWORK", taskParams.destVpcId);
        provider.setConfig(config);
        provider.save();
      } else if (provider.code.equals("aws")) {
        taskParams.destVpcId = null;
      }
    }

    // If the regionList is still empty by here, then we need to list the regions available.
    if (taskParams.perRegionMetadata == null) {
      taskParams.perRegionMetadata = new HashMap<>();
    }
    if (taskParams.perRegionMetadata.isEmpty()) {
      CloudQueryHelper queryHelper = Play.current().injector().instanceOf(CloudQueryHelper.class);
      JsonNode regionInfo = queryHelper.getRegions(provider.uuid);
      if (regionInfo instanceof ArrayNode) {
        ArrayNode regionListArray = (ArrayNode) regionInfo;
        for (JsonNode region : regionListArray) {
          taskParams.perRegionMetadata.put(
              region.asText(), new CloudBootstrap.Params.PerRegionMetadata());
        }
      }
    }

    UUID taskUUID = commissioner.submit(TaskType.CloudBootstrap, taskParams);
    CustomerTask.create(
        customer,
        providerUUID,
        taskUUID,
        CustomerTask.TargetType.Provider,
        CustomerTask.TaskType.Create,
        provider.name);

    auditService().createAuditEntry(ctx(), request(), requestBody, taskUUID);
    return new YWResults.YWTask(taskUUID).asResult();
  }

  public Result cleanup(UUID customerUUID, UUID providerUUID) {
    // TODO(bogdan): this is not currently used, be careful about the API...
    Form<CloudBootstrapFormData> formData =
        formFactory.getFormDataOrBadRequest(CloudBootstrapFormData.class);

    Provider provider = Provider.getOrBadRequest(customerUUID, providerUUID);
    return YWResults.YWSuccess.empty();

    /*
    CloudCleanup.Params taskParams = new CloudCleanup.Params();
    taskParams.providerUUID = providerUUID;
    taskParams.regionList = formData.get().regionList;
    UUID taskUUID = commissioner.submit(TaskType.CloudCleanup, taskParams);

    // TODO: add customer task
    return new YWResults.YWTask(taskUUID).asResult();
    */
  }

  @ApiOperation(value = "editProvider", response = Provider.class)
  @ApiImplicitParams(
      @ApiImplicitParam(
          value = "edit provider form data",
          name = "editProviderFormData",
          dataType = "java.lang.Object",
          required = true,
          paramType = "body"))
  public Result edit(UUID customerUUID, UUID providerUUID) throws IOException {
    Customer customer = Customer.getOrBadRequest(customerUUID);
    JsonNode formData = request().body().asJson();
    Provider provider = Provider.getOrBadRequest(customerUUID, providerUUID);

    if (Provider.HostedZoneEnabledProviders.contains(provider.code)) {
      String hostedZoneId = formData.get("hostedZoneId").asText();
      if (hostedZoneId == null || hostedZoneId.length() == 0) {
        throw new YWServiceException(BAD_REQUEST, "Required field hosted zone id");
      }
      return validateHostedZoneUpdate(provider, hostedZoneId);
    } else if (provider.code.equals("kubernetes")) {
      Map<String, String> config = processConfig(formData, Common.CloudType.kubernetes);
      if (config != null) {
        updateKubeConfig(provider, config, true);
        auditService().createAuditEntry(ctx(), request(), formData);
        return ApiResponse.success(provider);
      } else {
        throw new YWServiceException(INTERNAL_SERVER_ERROR, "Could not parse config");
      }
    } else {
      throw new YWServiceException(
          BAD_REQUEST, "Expected aws/k8s, but found providers with code: " + provider.code);
    }
  }

  private Result validateHostedZoneUpdate(Provider provider, String hostedZoneId) {
    // TODO: do we have a good abstraction to inspect this AND know that it's an error outside?
    ShellResponse response = dnsManager.listDnsRecord(provider.uuid, hostedZoneId);
    if (response.code != 0) {
      return ApiResponse.error(
          INTERNAL_SERVER_ERROR, "Invalid devops API response: " + response.message);
    }
    // The result returned from devops should be of the form
    // {
    //    "name": "dev.yugabyte.com."
    // }
    JsonNode hostedZoneData = Json.parse(response.message);
    hostedZoneData = hostedZoneData.get("name");
    if (hostedZoneData == null || hostedZoneData.asText().isEmpty()) {
      throw new YWServiceException(
          INTERNAL_SERVER_ERROR, "Invalid devops API response: " + response.message);
    }
    provider.updateHostedZone(hostedZoneId, hostedZoneData.asText());
    auditService().createAuditEntry(ctx(), request());
    return ApiResponse.success(provider);
  }

  private Map<String, String> processConfig(JsonNode requestBody, Common.CloudType providerCode) {
    Map<String, String> config = new HashMap<String, String>();
    JsonNode configNode = requestBody.get("config");
    // Confirm we had a "config" key and it was not null.
    if (configNode != null && !configNode.isNull()) {
      if (providerCode.equals(Common.CloudType.gcp)) {
        // We may receive a config file, or we may be asked to use the local service account.
        // Default to using config file.
        boolean shouldUseHostCredentials =
            configNode.has("use_host_credentials")
                && configNode.get("use_host_credentials").asBoolean();
        JsonNode contents = configNode.get("config_file_contents");
        if (!shouldUseHostCredentials && contents != null) {
          config = Json.fromJson(contents, Map.class);
        }

        contents = configNode.get("host_project_id");
        if (contents != null && !contents.textValue().isEmpty()) {
          config.put("GCE_HOST_PROJECT", contents.textValue());
        }

        contents = configNode.get("YB_FIREWALL_TAGS");
        if (contents != null && !contents.textValue().isEmpty()) {
          config.put("YB_FIREWALL_TAGS", contents.textValue());
        }
      } else {
        config = Json.fromJson(configNode, Map.class);
      }
    }
    return config;
  }
}
