/*
 * Copyright 2017 Walmart, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.oneops.boo.workflow;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.oneops.api.resource.model.Deployment;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.Uninterruptibles;
import com.oneops.api.OOInstance;
import com.oneops.api.exception.OneOpsClientAPIException;
import com.oneops.api.resource.model.CiResource;
import com.oneops.api.resource.model.RedundancyConfig;
import com.oneops.boo.BooCli;
import com.oneops.boo.ClientConfig;
import com.oneops.boo.LogUtils;
import com.oneops.boo.exception.BooException;
import com.oneops.boo.utils.BooUtils;
import com.oneops.boo.yaml.Constants;
import com.oneops.boo.yaml.PlatformBean;
import com.oneops.boo.yaml.ScaleBean;
import com.oneops.client.api.exception.OneOpsComponentExistException;


public class BuildAllPlatforms extends AbstractWorkflow {

  /** The Constant LOG. */
  private static final Logger LOG = LoggerFactory.getLogger(BuildAllPlatforms.class);

  /** The Constant ACTIVE. */
  // final private static String NAME = "ciName"; // Get component name.
  private static final String ACTIVE = "active";

  /** The Constant FAILED. */
  private static final String FAILED = "failed";

  /** The Constant NEWLINE. */
  private static final String NEWLINE = System.getProperty("line.separator");

  /** The utils. */
  private final BooUtils utils = new BooUtils();

  /** The retries. */
  private int retries = 6;

  //
  private int numOfThreads = 32;

  /**
   * Instantiates a new builds the all platforms.
   *
   * @param instance the instance
   * @param config the config
   * @throws OneOpsClientAPIException the one ops client API exception
   */

  public BuildAllPlatforms(OOInstance instance, ClientConfig config, String comment)
      throws OneOpsClientAPIException {
    super(instance, config, comment);
  }


  /**
   *
   * @param isUpdate the is update
   * @param isAssemblyOnly the is assembly only
   * @return
   * @throws OneOpsClientAPIException
   */
  @Override
  public Deployment process(boolean isUpdate, boolean isAssemblyOnly) throws OneOpsClientAPIException {
    boolean isAssemblyExist = this.isAssemblyExist();
    if (isUpdate && !isAssemblyExist) {
      throw new OneOpsClientAPIException(this.assemblyBean.getName() + " not exists!");
    }
    if (!config.getYaml().getAssembly().getAutoGen()) {
      if (!isUpdate && isAssemblyExist) {
        throw new OneOpsClientAPIException(this.assemblyBean.getName() + " already exists!");
      }
    }
    this.bar.update(1, 100);
    this.createAssemblyIfNotExist();
    this.bar.update(5, 100);
    this.createPlatforms(isUpdate);
    this.bar.update(15, 100);
    if (isUpdate) {
      this.updatePlatformComponents();
    }
    this.updatePlatformVariables(isUpdate);
    this.bar.update(20, 100);
    this.createEnv();
    this.bar.update(30, 100);
    if (isUpdate) {
      this.updatePlatformCloudScale();
    }
    this.updateEnv();
    this.bar.update(40, 100);
    utils.waitTimeout(1);
    if (isUpdate) {
      try {
        this.pullDesign();
      } catch (Exception e) {
        // Ignore
        // e.printStackTrace();
      }
    }
    this.bar.update(50, 100);
    String status = this.getStatus();
    if (ACTIVE.equalsIgnoreCase(status)) {
      LogUtils.info(Constants.ACTIVE_DEPLOYMENT_EXISTING);
      return null;
    }

    if (FAILED.equalsIgnoreCase(status)) {
      LogUtils.info(Constants.FAIL_DEPLOYMENT_EXISTING);
      return null;
    }
    this.updateScaling();
    this.bar.update(70, 100);
    // Added retries
    boolean retry = true;
    String deployError = null;
    this.relayEnableDelivery(config.getYaml().getBoo().isEnable());
    if (isUpdate) {
      this.commitEnv();
    }
    if (BooCli.isNoDeploy()) {
      this.bar.update(100, 100);
      LogUtils.info(Constants.CREATE_WITHOUT_DEPLOYMENT);
      return null;
    }
    LogUtils.info(Constants.START_DEPLOYMENT);
    Deployment deployment = null;
    while (retry && retries > 0) {
      utils.waitTimeout(2);
      try {
        deployment = this.deploy(isUpdate);
        retry = false;
      } catch (Exception e) {
        deployError = e.getMessage();
        retries--;
      }
    }
    this.bar.update(100, 100);
    if (!retry) { // If no error for deployment.
      LogUtils.info(Constants.DEPLOYMENT_RUNNING);
      return deployment;
    } else {
      if (deployError != null && deployError.contains(Constants.NO_DEPLOYMENT)) {
        System.out.printf(Constants.NO_NEED_DEPLOY);
      } else {
        System.err.printf(Constants.DEPLOYMENT_FAILED, deployError);
      }

      System.out.println();
    }
    return null;
  }


  /**
   * Relay enable delivery.
   *
   * @param enable the enable
   * @return true, if successful
   */
  public boolean relayEnableDelivery(boolean enable) {
    try {
      transition.updateRelay(this.envName, "default", null, null, null, null, null, false, enable);
      return Boolean.TRUE;
    } catch (OneOpsClientAPIException e) {
      System.err.println("Cannot update relay!");
    }
    return Boolean.FALSE;
  }

  /**
   * Checks if is platform exist.
   *
   * @param platformName the platform name
   * @return true, if is platform exist
   * @throws OneOpsClientAPIException the one ops client API exception
   * @throws OneOpsComponentExistException the one ops component exist exception
   */
  public boolean isPlatformExist(String platformName)
      throws OneOpsClientAPIException, OneOpsComponentExistException {
    CiResource response = null;
    try {
      response = design.getPlatform(platformName);
    } catch (OneOpsClientAPIException e) {
      String msg = String.format("The platform %s is not exist!", platformName);
      throw new OneOpsComponentExistException(msg);
    }
    return response == null ? false : true;
  }


  /**
   * Creates the platforms.
   *
   * @param isUpdate the is update
   * @return true, if successful
   * @throws OneOpsClientAPIException the one ops client API exception
   */
  @SuppressWarnings("unchecked")
  public boolean createPlatforms(boolean isUpdate) throws OneOpsClientAPIException {
    List<PlatformBean> platforms = this.config.getYaml().getPlatformsList();
    Collections.sort(platforms);
    for (PlatformBean platform : platforms) {
      LogUtils.info(Constants.CREATING_PLATFORM, platform.getName());
      this.createPlatform(platform);
      if (platform.getComponents() == null) {
        continue;
      }
      for (Map.Entry<String, Object> entry : platform.getComponents().entrySet()) {
        String componentName = entry.getKey();
        Object value = entry.getValue();
        if (value instanceof Map) {
          Map<String, Object> components = (Map<String, Object>) value;
          this.handleAttachments(components, platform.getName(), componentName);
          this.updateComponentVariables(platform.getName(), componentName, components);
        } else {
          if (LOG.isInfoEnabled()) {
            LOG.info("Unknow type {}.", value.getClass());
          }
        }
      }
      if(platform.getLinks() != null && platform.getLinks().size() > 0) {
    	  design.updatePlatformLinks(platform.getName(), platform.getLinks());
      }
    }
    return true;
  }

  /**
   * We tolerate that if update attachment failed, won't stop the whole process.
   * 
   * @param components Components map.
   * @param platformName Platform name.
   * @param componentName Component name.
   */
  private void handleAttachments(Map<String, Object> components, String platformName,
      String componentName) {
    try {
      this.handleAttachmentsIntl(components, platformName, componentName);
    } catch (Exception e) {
      // Ignore
    } finally {
      if (components.get(Constants.ATTACHMENTS) != null) {
        components.remove(Constants.ATTACHMENTS);
      }
    }
  }

  /**
   * Handle attachments intl.
   *
   * @param components the components
   * @param platformName the platform name
   * @param componentName the component name
   * @throws OneOpsClientAPIException the one ops client API exception
   */
  @SuppressWarnings("unchecked")
  private void handleAttachmentsIntl(Map<String, Object> components, String platformName,
      String componentName) throws OneOpsClientAPIException {
    if (components.get(Constants.ATTACHMENTS) != null) {
      Map<String, Object> attachments = (Map<String, Object>) components.get(Constants.ATTACHMENTS);
      for (Map.Entry<String, Object> entry : attachments.entrySet()) {
        String attachment = entry.getKey();
        Map<String, String> attributes = (Map<String, String>) entry.getValue();
        if (this.isAttachmentExists(platformName, componentName, attachment)) {
          this.updateAttachment(platformName, componentName, attachment, attributes);
        } else {
          this.addAttachment(platformName, componentName, attachment, attributes);
        }
      }
    }

  }

  /**
   * Creates the platform.
   *
   * @param platform the platform
   * @return true, if successful
   * @throws OneOpsClientAPIException the one ops client API exception
   */
  public boolean createPlatform(PlatformBean platform) throws OneOpsClientAPIException {
    boolean isExist = false;
    try {
      isExist = this.isPlatformExist(platform.getName());
    } catch (OneOpsComponentExistException e) {
      // Ignore
    }
    if (!isExist) {
      CiResource response =
          design.createPlatform(platform.getName(), platform.getPack(), platform.getPackVersion(),
              platform.getPackSource(), Constants.DESCRIPTION, Constants.DESCRIPTION);
      if (response != null) {
        design.commitDesign();
      }
      LogUtils.info(Constants.CREATING_PLATFORM_SUCCEED, platform.getName());
    } else {
      LogUtils.info(Constants.PLATFORM_EXISTING, platform.getName());
    }
    return true;

  }

  /**
   * Checks if is component exist.
   *
   * @param platformName the platform name
   * @param componentName the component name
   * @return true, if is component exist
   * @throws OneOpsClientAPIException the one ops client API exception
   * @throws OneOpsComponentExistException the one ops component exist exception
   */
  public boolean isComponentExist(String platformName, String componentName)
      throws OneOpsClientAPIException, OneOpsComponentExistException {
    boolean isExist = false;
    try {
      design.getPlatformComponent(platformName, componentName);
      isExist = true;
    } catch (OneOpsClientAPIException e) {
      // e.printStackTrace();
      throw new OneOpsComponentExistException(e.getMessage());
    }
    return isExist;
  }


  /**
   * Update platform variables.
   *
   * @param isUpdate the is update
   * @return true, if successful
   * @throws OneOpsClientAPIException the one ops client API exception
   */
  public boolean updatePlatformVariables(boolean isUpdate) throws OneOpsClientAPIException {
    List<PlatformBean> platforms = this.config.getYaml().getPlatformsList();
    for (PlatformBean platform : platforms) {
      Map<String, String> secureVariables = platform.getSecureVariables();
      Set<String> yamlVarSet = new HashSet<String>();
      if (secureVariables != null && secureVariables.size() > 0) {
        this.updateOrAddPlatformVariables(platform.getName(), secureVariables, true, isUpdate);
        // collect all variables (secvar or var) in yaml
        for (Map.Entry<String, String> entry : secureVariables.entrySet()) {
          yamlVarSet.add(entry.getKey());
        }
      }
      Map<String, String> variables = platform.getVariables();
      if (variables != null && variables.size() > 0) {
        this.updateOrAddPlatformVariables(platform.getName(), variables, false, isUpdate);
        // collect all variables (secvar or var) in yaml
        for (Map.Entry<String, String> entry : variables.entrySet()) {
          yamlVarSet.add(entry.getKey());
        }
      }

      List<CiResource> response = design.listPlatformVariables(platform.getName());
//      List<String> servVarList = response.getList(Constants.CINAME);
      for (CiResource resource : response) {
        if (!yamlVarSet.contains(resource.getCiName())) {
          design.deletePlatformVariable(platform.getName(), resource.getCiName());
        }
      }
    }
    if (platforms.size() > 0) {
      design.commitDesign();
    }
    return true;
  }


  /**
   * Update or add platform variables.
   *
   * @param platformName the platform name
   * @param variables the variables
   * @param isSecure the is secure
   * @param isUpdate the is update
   * @throws OneOpsClientAPIException the one ops client API exception
   */
  @SuppressWarnings("serial")
  private void updateOrAddPlatformVariables(String platformName, Map<String, String> variables,
      boolean isSecure, boolean isUpdate) throws OneOpsClientAPIException {
    if (variables == null || variables.size() == 0) {
      return;
    }
    for (final Map.Entry<String, String> entry : variables.entrySet()) {
      this.updateOrAddPlatformVariablesIntl(platformName, new HashMap<String, String>() {
        {
          put(entry.getKey(), entry.getValue());
        }
      }, isSecure, isUpdate);
    }
  }

  /**
   * Have to add isExist method later.
   *
   * @param platformName Platform name.
   * @param variables A map of variables.
   * @param isSecure Is secure variable.
   * @param isUpdate the is update
   * @throws OneOpsClientAPIException when update failed.
   */
  private void updateOrAddPlatformVariablesIntl(String platformName, Map<String, String> variables,
      boolean isSecure, boolean isUpdate) throws OneOpsClientAPIException {
	  for (Entry<String, String> entry : variables.entrySet()) {
		  design.updateOrAddPlatformVariables(platformName, entry.getKey(), entry.getValue(), isSecure);
	}
   
  }

  /**
   * Right now support components with two layers config.
   *
   * @param platformName Platform name.
   * @param componentName Component name.
   * @param attributes Component variables.
   * @throws OneOpsClientAPIException the one ops client API exception
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private void updateComponentVariables(String platformName, String componentName,
      Map<String, Object> attributes) throws OneOpsClientAPIException {
    // Create thread pool to add users parallel
    ExecutorService executor = Executors.newFixedThreadPool(numOfThreads);

    for (Map.Entry<String, Object> entry : attributes.entrySet()) {
      String key = entry.getKey();
      Object value = entry.getValue();
      // Another Map, so key is ciName
      if (value instanceof Map) {
        Map<String, String> attris = (Map<String, String>) value;
        if (attris.containsKey(Constants.AUTHO_KEYS)) {
          Runnable worker = new UpdateComponentTask(this, platformName, componentName, key, attris);
          executor.execute(worker);
        } else {
          this.updateComponentVariablesInternal(platformName, componentName, key, attris);
        }
      } else if (value instanceof String) {
        Map<String, String> att = (Map) attributes;
        if (att.containsKey(Constants.AUTHO_KEYS)) {
          Runnable worker = new UpdateComponentTask(this, platformName, componentName, key, att);
          executor.execute(worker);
        } else {
          this.updateComponentVariablesInternal(platformName, componentName, componentName, att);
        }
        break;
      }
    }
    executor.shutdown();
    while (!executor.isTerminated()) {
      Uninterruptibles.sleepUninterruptibly(10, TimeUnit.MILLISECONDS);
    }
  }

  /**
   * Update component variables internal.
   *
   * @param platformName the platform name
   * @param componentName the component name
   * @param uniqueName the unique name
   * @param attributes the attributes
   * @return true, if successful
   * @throws OneOpsClientAPIException the one ops client API exception
   */
  private boolean updateComponentVariablesInternal(String platformName, String componentName,
      String uniqueName, Map<String, String> attributes) throws OneOpsClientAPIException {
    LogUtils.info(Constants.UPDATE_COMPONENTS, componentName, platformName);
    boolean isExist = Boolean.FALSE;
    try {
      isExist = this.isComponentExist(platformName, uniqueName);
    } catch (OneOpsComponentExistException e1) {
      // Ignore
      isExist = Boolean.FALSE;
    }
    if (isExist) {
      design.updatePlatformComponent(platformName, uniqueName, attributes);
    } else {
      design.addPlatformComponent(platformName, componentName, uniqueName, attributes);
    }
    // design.commitDesign();
    return true;
  }

  /**
   * Gets the custom ips.
   *
   * @param platformName the platform name
   * @param componentName the component name
   * @return the custom ips
   * @throws OneOpsClientAPIException the one ops client API exception
   */
  public String getCustomIps(String platformName, String componentName)
      throws OneOpsClientAPIException {
    return utils.getIps(platformName, componentName, this);
  }

  /**
   * Prints the ips.
   *
   * @param platformName the platform name
   * @param componentName the component name
   * @return the string
   * @throws OneOpsClientAPIException the one ops client API exception
   */
  public String printIps(String platformName, String componentName)
      throws OneOpsClientAPIException {
    List<Map<String, Object>> ips = this.getIpsInternal(platformName, componentName);
    StringBuilder str = new StringBuilder();
    for (Map<String, Object> ip : ips) {
      str.append(ip.get(Constants.PRIVATE_IP));
      str.append(NEWLINE);
    }
    return str.toString();
  }

  /**
   * Run an Ansible playbook.
   *
   * @param playbookPath the path to the playbook
   * @param ipList the list of IP addresses to use
   * @param invFilePath the path to save the inventory to (can be null)
   * @return the string
   * @throws BooException the one ops client API exception
   */
  public String runPlaybook(String playbookPath, List<String> ipList, String invFilePath)
      throws BooException {
    String result = "";
    
    // Make sure the playbook exists and is readable
    File playbookFile = new File(playbookPath);
    
    if (!playbookFile.exists() || !playbookFile.canRead())
      throw new BooException("The path [" + playbookPath + "] is not a readable file. The playbook could not be run.");
    
    // Create a temporary inventory file
    File invFile = null;
    FileWriter invWriter = null;
    
    try
    {
      invFile = (invFilePath == null) ? File.createTempFile("boo", ".properties") : new File(invFilePath);
      
      if (invFile.exists() && !invFile.canWrite())
        throw new BooException("Unable to write to inventory file [" + invFile.getAbsolutePath() + "]");
      
      invWriter = new FileWriter(invFile);
      
      for (String ipItem : ipList) {
        invWriter.write(ipItem);
        invWriter.write(NEWLINE);
      }
      
      invWriter.close();
    }
    catch (IOException ioEx)
    {
      throw new BooException("An error occurred while generating the inventory file", ioEx);
    }
    finally
    {
      if (invWriter != null)
        try { invWriter.close(); } catch (Exception ex) { }
    }
    
    // Run the Ansible playbook
    try
    {
      System.err.println("\nRunning playbook...");
      ProcessBuilder procBuilder = new ProcessBuilder("ansible-playbook", "-i", invFile.getAbsolutePath(), playbookPath);
      procBuilder.redirectErrorStream(true);
      
      Process proc = procBuilder.start();

      BufferedReader stdInput = new BufferedReader(new InputStreamReader(proc.getInputStream()));

      try {
        String outStr = null;
        while ((outStr = stdInput.readLine()) != null) {
          System.out.println(outStr);
        }
      }
      finally {
        stdInput.close();
      }
    }
    catch (Exception ex) {
      throw new BooException("An error occurred while running ansible-playbook", ex);
    }
    finally {
      // Clean up the inventory file only if a temporary file was used
      if ((invFile != null) && invFile.exists() && (invFilePath == null))
        invFile.delete();
    }
    
    return result;
  }
  
  /**
   * Update scaling.
   *
   * @return true, if successful
   * @throws OneOpsClientAPIException the one ops client API exception
   */
  public boolean updateScaling() throws OneOpsClientAPIException {
    List<ScaleBean> scales = this.config.getYaml().getScales();
    if (scales == null) {
      return false;
    }
    for (ScaleBean scale : scales) {
      RedundancyConfig config = new RedundancyConfig();
      config.setCurrent(scale.getCurrent());
      config.setMin(scale.getMin());
      config.setMax(scale.getMax());
      config.setPercentDeploy(scale.getPercentDeploy());
      LogUtils.info(Constants.COMPUTE_SIZE, envName, scale.getPlatform());
      transition.updatePlatformRedundancyConfig(envName, scale.getPlatform(), scale.getComponent(), config);
    }
    if (StringUtils.isBlank(this.comments)) {
      transition.commitEnvironment(envName, null, Constants.DESCRIPTION);
    } else {
      transition.commitEnvironment(envName, null, comments);
    }
    return true;
  }

  /**
   * Update User related Component.
   *
   * @return true, if successful
   * @throws OneOpsClientAPIException the one ops client API exception
   */
  @SuppressWarnings("unchecked")
  public boolean updatePlatformComponents() throws OneOpsClientAPIException {
    List<PlatformBean> platforms = this.config.getYaml().getPlatformsList();
    for (PlatformBean platform : platforms) {
      Map<String, Object> yamlComponents = platform.getComponents();
      if (yamlComponents == null) {
        continue;
      }
      Set<String> yamlCompSet = new HashSet<String>();
      for (Map.Entry<String, Object> entry : yamlComponents.entrySet()) {
        yamlCompSet.add(entry.getKey());
        Object value = entry.getValue();
        if (value instanceof Map) {
          Map<String, Object> target = (Map<String, Object>) value;
          yamlCompSet.addAll(target.keySet());
        }
      }
      List<CiResource> response = design.listPlatformComponents(platform.getName());
      for (CiResource resource : response) {
        if (this.isUserCustomizedComponent(platform.getName(), resource.getCiName())
            && !yamlCompSet.contains(resource.getCiName())) {
          design.deletePlatformComponent(platform.getName(), resource.getCiName());
        }
      }
    }
    return true;
  }

  public Deployment getDeployment(Long deploymentId) throws OneOpsClientAPIException {
    return transition.getDeploymentStatus(envName, deploymentId);
  }
}
