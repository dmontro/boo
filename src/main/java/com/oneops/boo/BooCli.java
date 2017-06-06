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
package com.oneops.boo;

import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;

import com.oneops.api.resource.model.Deployment;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.jayway.restassured.RestAssured;
import com.oneops.api.OOInstance;
import com.oneops.api.exception.OneOpsClientAPIException;
import com.oneops.boo.exception.BooException;
import com.oneops.boo.utils.BooUtils;
import com.oneops.boo.workflow.BuildAllPlatforms;
import com.oneops.boo.yaml.Constants;

/**
 * The Class BooCli.
 */
public class BooCli {

  /** The Constant LOG. */
  private static final Logger LOG = LoggerFactory.getLogger(BooCli.class);

  /** The is quiet. */
  private static boolean isQuiet = false;

  /** The is forced. */
  private static boolean isForced = false;

  /** The is no deploy. */
  private static boolean isNoDeploy = false;

  /** The Constant YES_NO. */
  private static final String YES_NO =
      "WARNING! There are %s instances using the %s configuration. Do you want to destroy all of them? (y/n)";

  private static int DEPLOYMENT_TIMEOUT_SECONDS = 90 * 60; //90 minutes

  /** The config file. */
  private File configFile;

  /** The flow. */
  private BuildAllPlatforms flow;

  /** The options. */
  private Options options = new Options();

  /** The config. */
  private ClientConfig config;

  /** The injector. */
  private Injector injector;

  /** The boo utils. */
  private BooUtils booUtils = new BooUtils();

  private String comment = null;

  private String profile = ClientConfig.ONEOPS_DEFAULT_PROFILE;

  /**
   * Instantiates a new boo cli.
   */
  public BooCli() {
    Option help = new Option("h", "help", false, "show help.");
    Option create = Option.builder("c").longOpt("create")
        .desc(
            "Create a new Assembly specified by -f. If Assembly automatic naming is enabled, each invocation will create a new Assembly.")
        .build();
    Option update = Option.builder("u").longOpt("update")
        .desc("Update configurations specified by -f.").build();
    Option status = Option.builder("s").longOpt("status")
        .desc("Get status of deployments specified by -f").build();

    Option config = Option.builder("f").longOpt("config-file").argName("FILE").hasArg()
        .desc("Use specified Boo YAML file").build();

    Option cleanup = Option.builder("r").longOpt("remove")
        .desc("Remove all deployed configurations specified by -f").build();
    Option list = Option.builder("l").longOpt("list").numberOfArgs(1).optionalArg(Boolean.TRUE)
        .desc("Return a list of instances applicable to the identifier provided..").build();

    Option force = Option.builder().longOpt("force").desc("Do not prompt for --remove").build();

    Option nodeploy =
        Option.builder().longOpt("no-deploy").desc("Create assembly without deployments").build();

    Option getIps = Option.builder().longOpt("get-ips").argName("environment> <compute-class")
        .desc("Get IPs of deployed nodes specified by -f; Args are optional.").build();
    getIps.setOptionalArg(true);
    getIps.setArgs(Option.UNLIMITED_VALUES);

    Option retry = Option.builder().longOpt("retry")
        .desc("Retry deployments of configurations specified by -f").build();
    Option quiet = Option.builder().longOpt("quiet").desc("Silence the textual output.").build();
    Option assembly = Option.builder("a").longOpt("assembly").hasArg()
        .desc("Override the assembly name.").build();
    Option action = Option.builder().longOpt("procedure").numberOfArgs(3).optionalArg(Boolean.TRUE)
        .argName("platform> <component> <action")
        .desc("Execute actions. Use 'list' as an action to show available actions.").build();
    Option procedureArguments =
        Option.builder().longOpt("procedure-arguments").argName("arglist").hasArg()
            .desc(
                "Arguments to pass to the procedure call. Example: '{\"backup_type\":\"incremental\"}'")
            .build();
    Option instanceList =
        Option.builder().longOpt("procedure-instances").argName("instanceList").hasArg()
            .desc(
                "Comma-separated list of component instance names. 'list' to show all available component instances.")
            .build();

    Option stepSize = Option.builder().longOpt("procedure-step-size").argName("size").hasArg()
        .desc("Percent of nodes to perform procedure on, default is 100.").build();
    Option comment = Option.builder("m").longOpt("message").argName("description").hasArg()
        .desc("Customize the comment for deployments").build();
    Option view =
        Option.builder("v").longOpt("view").desc("View interpolated Boo YAML template").build();
    Option profile = Option.builder("p").longOpt("profile").argName("PROFILE").hasArg()
        .desc("Choose specific profile from ~/.boo/config").build();

    Option playbook = Option.builder().longOpt("playbook").argName("playbook> <platform> <component").hasArg()
        .desc("Run an Ansible playbook in an environment").build();
    playbook.setOptionalArg(true);
    playbook.setArgs(3);
    
    Option invfile = Option.builder().longOpt("inventory-file").hasArg()
        .desc("Save the inventory in the specified file").build();
    
    options.addOption(help);
    options.addOption(config);
    options.addOption(create);
    options.addOption(update);
    options.addOption(status);
    options.addOption(list);
    options.addOption(cleanup);
    options.addOption(getIps);
    options.addOption(retry);
    options.addOption(quiet);
    options.addOption(force);
    options.addOption(nodeploy);
    options.addOption(assembly);
    options.addOption(action);
    options.addOption(procedureArguments);
    options.addOption(instanceList);
    options.addOption(stepSize);
    options.addOption(comment);
    options.addOption(view);
    options.addOption(profile);
    options.addOption(playbook);
    options.addOption(invfile);
  }

  static {
    RestAssured.useRelaxedHTTPSValidation();
  }

  /**
   * Inits the YAML template.
   *
   * @param template the template
   * @param assembly the assembly
   * @throws BooException the Boo exception
   */
  public void init(File template, String assembly, Map<String, String> variables, String comment) throws BooException {
    if (LOG.isDebugEnabled()) {
      LOG.debug("Loading {}", template);
    }
    LOG.info("Boo variables : " + variables);
    this.configFile = template;
    if (variables != null) {
      injector = Guice.createInjector(new JaywayHttpModule(this.configFile, variables));
    } else {
      injector = Guice.createInjector(new JaywayHttpModule(this.configFile, this.profile));
    }
    config = injector.getInstance(ClientConfig.class);
    booUtils.verifyTemplate(config);
    if (assembly != null) {
      config.getYaml().getAssembly().setName(assembly);
    }
    this.initOo(config, null, comment);
  }

  /**
   * Inits the OO.
   *
   * @param config the config
   * @param assembly the assembly
   */
  public void initOo(ClientConfig config, String assembly, String comment) {
    OOInstance oo = injector.getInstance(OOInstance.class);
    oo.setGzipEnabled(config.getYaml().getBoo().isGzipEnabled());
    try {
      if (assembly != null) {
        config.getYaml().getAssembly().setName(assembly);
      }
      flow = new BuildAllPlatforms(oo, config, comment);
    } catch (OneOpsClientAPIException e) {
      System.err.println("Init failed with " + e.getMessage());
    }
  }

  /**
   * Parse user's input.
   *
   * @param arg the arg
   * @throws ParseException the parse exception
   * @throws BooException the Boo exception
   * @throws OneOpsClientAPIException the one ops client API exception
   */
  public int parse(String[] arg) throws ParseException, BooException, OneOpsClientAPIException {
    CommandLineParser parser = new DefaultParser();
    int exit = 0;
    // CommandLineParser parser = new GnuParser();
    try {

      String assembly = null;
      CommandLine cmd = parser.parse(options, arg);
      /**
       * Handle command without configuration file dependency first.
       */
      if (cmd.hasOption("h")) {
        this.help(null, "");
        return exit;
      }

      if (cmd.hasOption("quiet")) {
        BooCli.setQuiet(Boolean.TRUE);
      }

      if (cmd.hasOption("force")) {
        BooCli.setForced(Boolean.TRUE);
      }
      if (cmd.hasOption("no-deploy")) {
        BooCli.setNoDeploy(Boolean.TRUE);
      }

      if (cmd.hasOption("a")) {
        assembly = cmd.getOptionValue("a");
      }
      /**
       * Get configuration dir or file.
       */
      if (cmd.hasOption("f")) {
        this.configFile = new File(booUtils.getAbsolutePath(cmd.getOptionValue("f")));
        System.out.printf(Constants.CONFIG_FILE, this.configFile);
        System.out.println();
      }

      if (this.configFile == null || !this.configFile.exists()) {
        this.help(null, "No YAML file found.");
        return Constants.EXIT_YAML_NOT_FOUND;
      }

      String yaml = "";

      if (ClientConfig.ONEOPS_CONFIG.exists()) {
        if (cmd.hasOption("profile")) {
          this.profile = cmd.getOptionValue("profile");
        }
        ClientConfigIniReader iniReader = new ClientConfigIniReader();
        if (iniReader.read(ClientConfig.ONEOPS_CONFIG, profile) == null) {
          System.out.format("%n%s is not a valid profile in %s.%n%n", profile,
              ClientConfig.ONEOPS_CONFIG);
          return Constants.EXIT_INVALID_PROFILE;
        }

        ClientConfigInterpolator interpolator = new ClientConfigInterpolator();
        yaml = interpolator.interpolate(this.configFile, ClientConfig.ONEOPS_CONFIG, this.profile);
      }

      if (cmd.hasOption('v')) {
        System.out.println(yaml);
        if (!ClientConfig.ONEOPS_CONFIG.exists()) {
          System.out.format("%nYou do not have a %s file. No interpolation can be performed.%n%n",
              ClientConfig.ONEOPS_CONFIG);
        }
        return exit;
      }

      if (cmd.hasOption("m")) {
        this.comment = cmd.getOptionValue("m");
      }

      this.init(this.configFile, assembly, null, comment);
      if (cmd.hasOption("l")) {
        String prefix = cmd.getOptionValue("l");
        if (prefix == null) {
          this.listFiles(config.getYaml().getAssembly().getName());
        } else {
          this.listFiles(prefix.trim());
        }
        return Constants.EXIT_NORMAL;
      }
      /**
       * Handle other commands.
       */
      if (cmd.hasOption("s")) {
        if (!flow.isAssemblyExist()) {
          System.err.printf(Constants.NOTFOUND_ERROR, config.getYaml().getAssembly().getName());
          return Constants.EXIT_ASSEMBLY_NOT_FOUND;
        } else {
          System.out.println(this.getStatus());
        }
      } else if (cmd.hasOption("c")) {
        if (config.getYaml().getAssembly().getAutoGen()) {
          this.initOo(this.config,
              this.autoGenAssemblyName(config.getYaml().getAssembly().getAutoGen(),
                  config.getYaml().getAssembly().getName()),
              comment);
          LogUtils.info(Constants.CREATING_ASSEMBLY, config.getYaml().getAssembly().getName());
        }
        this.createPacks(Boolean.FALSE, isNoDeploy);
      } else if (cmd.hasOption("u")) {
        if (!config.getYaml().getAssembly().getAutoGen()) {
          if (flow.isAssemblyExist()) {
            this.createPacks(Boolean.TRUE, isNoDeploy);
          } else {
            System.err.printf(Constants.NOTFOUND_ERROR, config.getYaml().getAssembly().getName());
          }
        } else {
          List<String> assemblies = this.listFiles(this.config.getYaml().getAssembly().getName());
          for (String asm : assemblies) {
            this.initOo(config, asm, comment);
            this.createPacks(Boolean.TRUE, isNoDeploy);
          }
        }
      } else if (cmd.hasOption("r")) {
        deleteAssemblies();
      } else if (cmd.hasOption("get-ips")) {
        if (!flow.isAssemblyExist()) {
          System.err.printf(Constants.NOTFOUND_ERROR, config.getYaml().getAssembly().getName());
        } else if (cmd.getOptionValues("get-ips") == null) {
          // if there is no args for get-ips
          getIps0();
        } else if (cmd.getOptionValues("get-ips").length == 1) {
          // if there is one arg for get-ips
          getIps1(cmd.getOptionValues("get-ips")[0]);
        } else if (cmd.getOptionValues("get-ips").length == 2) {
          // if there are two args for get-ips
          getIps2(cmd.getOptionValues("get-ips")[0], cmd.getOptionValues("get-ips")[1]);
        }
      } else if (cmd.hasOption("playbook")) {
        if (!flow.isAssemblyExist()) {
          System.err.printf(Constants.NOTFOUND_ERROR, config.getYaml().getAssembly().getName());
        } else {
          String playbookPath = null;
          String platformName = null;
          String componentName = null;
          String[] optionVals = cmd.getOptionValues("playbook");
          int numArgs = optionVals.length;
          
          playbookPath = optionVals[0];
          platformName = numArgs > 1 ? optionVals[1] : null;
          componentName = numArgs > 2 ? optionVals[2] : null;
          
          String invFilePath = null;
          
          if (cmd.hasOption("inventory-file"))
            invFilePath = cmd.getOptionValue("inventory-file");
            
          runPlaybook(playbookPath, invFilePath, platformName, componentName);
        }
      } else if (cmd.hasOption("retry")) {
        this.retryDeployment();
      } else if (cmd.hasOption("procedure")) {
        if (cmd.getOptionValues("procedure").length != 3) {
          System.err
              .println("Wrong parameters! --prodedure <platformName> <componentName> <actionName>");
          return Constants.EXIT_WRONG_PRAMETER;
        } else {
          String[] args = cmd.getOptionValues("procedure");
          String arglist = "";
          int rollAt = 100;
          if (cmd.hasOption("procedure-arguments")) {
            arglist = cmd.getOptionValue("procedure-arguments");
          }
          if (cmd.hasOption("procedure-step-size")) {
            rollAt = Integer.parseInt(cmd.getOptionValue("procedure-step-size"));
          }
          List<String> instances = null;
          if (cmd.hasOption("procedure-instances")) {
            String ins = cmd.getOptionValue("procedure-instances");
            if (ins != null && ins.trim().length() > 0) {
              if (ins.equalsIgnoreCase("list")) {
                List<String> list = flow.listInstances(args[0], args[1]);
                if (list != null) {
                  for (String instance : list) {
                    System.out.println(instance);
                  }
                }
                return Constants.EXIT_NORMAL;
              }
              instances = Arrays.asList(ins.split(","));
            }
          }
          if ("list".equalsIgnoreCase(args[2])) {
            List<String> list = flow.listActions(args[0], args[1]);
            if (list != null) {
              for (String instance : list) {
                System.out.println(instance);
              }
            }
          } else {
            exit = this.executeAction(args[0], args[1], args[2], arglist, instances, rollAt);
          }

        }
      } else {
        System.err.println("Wrong parameters!");
        return Constants.EXIT_WRONG_PRAMETER;
      }
    } catch (ParseException e) {
      exit = Constants.EXIT_PARSE_ERROR;
    } catch (Exception e) {
      exit = Constants.EXIT_UNKOWN;
      e.printStackTrace(new PrintStream(System.err));
    }
    return exit;
  }

  public List<Deployment> deleteAssemblies() {
    List<String> assemblies;
    if (config.getYaml().getAssembly().getAutoGen()) {
      assemblies = this.listFiles(this.config.getYaml().getAssembly().getName());
    } else {
      assemblies = new ArrayList<String>();
      String asb = this.config.getYaml().getAssembly().getName();
      if (this.flow.isAssemblyExist(asb)) {
        assemblies.add(asb);
      }
    }
    return this.cleanup(assemblies);
  }

  /**
   * Execute action.
   *
   * @param platformName the platform name
   * @param componentName the component name
   * @param actionName the action name
   * @param arglist the arglist
   * @param instanceList the instance list
   * @param rollAt the roll at
   */
  private int executeAction(String platformName, String componentName, String actionName,
      String arglist, List<String> instanceList, int rollAt) {
    int returnCode = 0;
    Long procedureId = null;
    try {
      System.out.println(Constants.PROCEDURE_RUNNING);
      procedureId = flow.executeAction(platformName, componentName, actionName, arglist,
          instanceList, rollAt);

    } catch (OneOpsClientAPIException e) {
      System.err.println(e.getMessage());
      returnCode = Constants.EXIT_CLIENT;
    }
    if (procedureId != null) {
      String procStatus = "active";
      try {
        while (procStatus != null
            && (procStatus.equalsIgnoreCase("active") || procStatus.equalsIgnoreCase("pending"))) {
          procStatus = flow.getProcedureStatus(procedureId);
          try {
            Thread.sleep(3000);
          } catch (InterruptedException e) {
            // Ignore
          }
        }
      } catch (OneOpsClientAPIException e) {
        // Ignore
      }
      if (procStatus.equalsIgnoreCase("complete")) {
        System.out.println(Constants.SUCCEED);
      } else {
        System.err.println(Constants.PROCEDURE_NOT_COMPLETE);
        returnCode = Constants.EXIT_NOT_COMPLETE;
      }
    }
    return returnCode;
  }

  /**
   * User input.
   *
   * @param msg the msg
   * @return the string
   */
  @SuppressWarnings("resource")
  private String userInput(String msg) {
    System.out.println(msg);
    Scanner inputReader = new Scanner(System.in);
    String input = inputReader.nextLine();
    return input;
  }

  /**
   * Gets the ips 0.
   *
   * @return the ips 0
   */
  private void getIps0() {
    Map<String, Object> platforms = flow.getConfig().getYaml().getPlatforms();
    List<String> computes = booUtils.getComponentOfCompute(this.flow);
    System.out.println("Environment name: " + flow.getConfig().getYaml().getBoo().getEnvName());
    for (String pname : platforms.keySet()) {
      System.out.println("Platform name: " + pname);
      for (String cname : computes) {
        System.out.println("Compute name: " + cname);
        System.out.printf(getIps(pname, cname));
      }
    }
  }

  /**
   * Gets the ips 1.
   *
   * @param inputEnv the input env
   * @return the ips 1
   */
  private void getIps1(String inputEnv) {
    String yamlEnv = flow.getConfig().getYaml().getBoo().getEnvName();
    if (yamlEnv.equals(inputEnv)) {
      getIps0();
    } else {
      System.out.println(Constants.NO_ENVIRONMENT);
    }
  }

  /**
   * Gets the ips 2.
   *
   * @param inputEnv the input env
   * @param componentName the component name
   * @return the ips 2
   */
  private void getIps2(String inputEnv, String componentName) {
    String yamlEnv = flow.getConfig().getYaml().getBoo().getEnvName();
    if (inputEnv.equals("*") || yamlEnv.equals(inputEnv)) {
      Map<String, Object> platforms = flow.getConfig().getYaml().getPlatforms();
      List<String> computes = booUtils.getComponentOfCompute(this.flow);
      for (String s : computes) {
        if (s.equals(componentName)) {
          System.out
              .println("Environment name: " + flow.getConfig().getYaml().getBoo().getEnvName());
          for (String pname : platforms.keySet()) {
            System.out.println("Platform name: " + pname);
            System.out.println("Compute name: " + componentName);
            System.out.printf(getIps(pname, componentName));
          }
          return;
        }
      }
      System.out.println("No such component: " + componentName);
    } else {
      System.out.println("No such environment: " + inputEnv);
    }
  }

  /**
   * Gets the ips.
   *
   * @param platformName the platform name
   * @param componentName the component name
   * @return the ips
   */
  private String getIps(String platformName, String componentName) {
    try {
      return flow.printIps(platformName, componentName);
    } catch (OneOpsClientAPIException e) {
      e.printStackTrace();
    }
    return null;
  }

  /**
   * Run an Ansible playbook.
   *
   * @param playbookPath the path to the Ansible playbook.
   * @param invFilePath the path to save the inventory file to
   * @param platformName the name of the platform to run against
   * @param componentName the name of the component to run against
   * @return the status of the operation
   */
  private String runPlaybook(String playbookPath, String invFilePath, String platformName, String componentName) {
    ArrayList<String> ipList = new ArrayList<String>();
    String lineSep = System.getProperty("line.separator");
    
    Map<String, Object> platforms = flow.getConfig().getYaml().getPlatforms();
    List<String> computes = booUtils.getComponentOfCompute(this.flow);

    for (String pname : platforms.keySet()) {
      for (String cname : computes) {
        String ipString = getIps(pname, cname);
        String[] allIps = ipString.split(lineSep);
        if ((allIps != null) && (allIps.length > 0))
          for (String ipItem : allIps)
            if (((platformName == null) || platformName.equals(pname)) && ((componentName == null) || (componentName.equals(cname))))
              ipList.add(ipItem);
      }
    }
    
    try {
      return flow.runPlaybook(playbookPath, ipList, invFilePath);
    } catch (BooException e) {
      e.printStackTrace();
    }
    return null;
  }

  /**
   * Retry deployment.
   *
   * @return true, if successful
   */
  public Deployment retryDeployment() {
    return flow.retryDeployment();
  }

  public Deployment getDeployment(long deploymentId) throws OneOpsClientAPIException {
    return flow.getDeployment(deploymentId);
  }

  /**
   * Help.
   *
   * @param header the header
   * @param footer the footer
   */
  private void help(String header, String footer) {
    HelpFormatter formatter = new HelpFormatter();
    formatter.printHelp(120, "boo", header, options, footer, true);
  }

  /**
   * List files.
   *
   * @param prefix the prefix
   * @return the list
   */
  private List<String> listFiles(String prefix) {
    if (prefix == null || prefix.trim().length() == 0) {
      System.err.println(Constants.ASSEMBLY_PREFIX_ERROR);
      System.exit(1);
    }
    List<String> assemblies = flow.getAllAutoGenAssemblies(prefix);
    for (String assembly : assemblies) {
      if (assembly != null) {
        System.out.println(assembly);
      }

    }
    return assemblies;
  }

  /**
   * Creates the packs.
   *
   * @param isUpdate the is update
   * @param isAssemblyOnly the is assembly only
   * @throws BooException the Boo exception
   * @throws OneOpsClientAPIException the one ops client API exception
   */
  public void createPacks(boolean isUpdate, boolean isAssemblyOnly)
      throws BooException, OneOpsClientAPIException {
    flow.process(isUpdate, isAssemblyOnly);
  }

  /**
   *  Creates platforms if the assembly does not exist. Updates the platform/components if assembly already exists
   * @throws BooException
   * @throws OneOpsClientAPIException
   */
  public Deployment createOrUpdatePlatforms()
          throws BooException, OneOpsClientAPIException, InterruptedException {
    Deployment deployment = null;
    if (flow.isAssemblyExist()) {
      deployment = flow.process(Boolean.TRUE, Boolean.FALSE);
    } else {
      deployment = flow.process(Boolean.FALSE, Boolean.FALSE);
    }

    return deployment;
  }

  /**
   * Limit to 32 characters long.
   *
   * @param isAutoGen the is auto gen
   * @param assemblyName the assembly name
   * @return the string
   */
  private String autoGenAssemblyName(boolean isAutoGen, String assemblyName) {
    if (isAutoGen) {
      assemblyName = (assemblyName == null ? this.randomString("")
          : (assemblyName + Constants.DASH + this.randomString(assemblyName)));
    }
    return assemblyName;
  }

  /**
   * Random string.
   *
   * @param assemblyName the assembly name
   * @return the string
   */
  private String randomString(String assemblyName) {
    StringBuilder name = new StringBuilder();
    int rand = 32 - assemblyName.length() - 1;
    rand = rand > 8 ? 8 : rand;
    name.append(UUID.randomUUID().toString().substring(0, rand));
    return name.toString();
  }

  /**
   * Cleanup.
   *
   * @param assemblies the assemblies
   */
  public List<Deployment> cleanup(List<String> assemblies) {
    if (assemblies.size() == 0) {
      System.out.println("There is no instance to remove");
      return null;
    }
    if (isForced == false) {
      String str =
          String.format(YES_NO, assemblies.size(), this.config.getYaml().getAssembly().getName());
      str = this.userInput(str);
      if (!"y".equalsIgnoreCase(str.trim())) {
        return null;
      }

    }
    List<Deployment> decommissionDeployments = new ArrayList<>();
    boolean isSuc = true;
    for (String assembly : assemblies) {
      LogUtils.info("Destroying OneOps assembly %s \n", assembly);
      this.initOo(config, assembly, comment);
      if (flow.isAssemblyExist(assembly)) {
        boolean isDone;
        try {
          decommissionDeployments.addAll(flow.removeAllEnvs());
          isDone = flow.removeAllPlatforms();
          if (!isDone && isSuc) {
            isSuc = false;
          }
        } catch (OneOpsClientAPIException e) {
          isSuc = false;
        }
      }
    }
    if (!isSuc) {
      LogUtils.error(Constants.NEED_ANOTHER_CLEANUP);
    }
    return decommissionDeployments;
  }

  /**
   * Gets the status.
   *
   * @return the status
   * @throws BooException the Boo exception
   */
  public String getStatus() throws BooException {
    return flow.getStatus();
  }

  /**
   * Checks if is quiet.
   *
   * @return true, if is quiet
   */
  public static boolean isQuiet() {
    return isQuiet;
  }

  /**
   * Sets the quiet.
   *
   * @param isQuiet the new quiet
   */
  public static void setQuiet(boolean isQuiet) {
    BooCli.isQuiet = isQuiet;
  }

  /**
   * Sets the forced.
   *
   * @param isForced the new forced
   */
  public static void setForced(boolean isForced) {
    BooCli.isForced = isForced;
  }

  /**
   * Sets the no deploy.
   *
   * @param isNoDeploy the new no deploy
   */
  public static void setNoDeploy(boolean isNoDeploy) {
    BooCli.isNoDeploy = isNoDeploy;
  }

  /**
   * Checks if is no deploy.
   *
   * @return true, if is no deploy
   */
  public static boolean isNoDeploy() {
    return isNoDeploy;
  }
}
