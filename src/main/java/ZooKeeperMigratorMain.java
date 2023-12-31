/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionGroup;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.zookeeper.KeeperException;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.concurrent.ExecutionException;

@Slf4j
public class ZooKeeperMigratorMain {

    private static final String JAVA_HOME = "JAVA_HOME";
    private static final String HEADER = System.lineSeparator() + "A tool for importing and exporting data from ZooKeeper." + System.lineSeparator() + System.lineSeparator();
    private static final String FOOTER = System.lineSeparator() + "Java home: " +
            System.getenv(JAVA_HOME);

    private static final Option OPTION_ZK_MIGRATOR_HELP = Option.builder("h")
            .longOpt("help")
            .desc("display help/usage info")
            .build();
    private static final Option OPTION_ZK_ENDPOINT_SRC = Option.builder("src")
            .longOpt("zookeeper")
            .desc("ZooKeeper src endpoint string (ex. host:port/path)")
            .hasArg()
            .argName("zookeeper-endpoint")
            .required()
            .build();
    private static final Option OPTION_ZK_ENDPOINT_DST = Option.builder("dst")
            .longOpt("zookeeper")
            .desc("ZooKeeper dst endpoint string (ex. host:port/path)")
            .hasArg()
            .argName("zookeeper-endpoint")
            .required()
            .build();
    private static final Option OPTION_ZK_AUTH_INFO = Option.builder("a")
            .longOpt("auth")
            .desc("username and password for the given ZooKeeper path")
            .hasArg()
            .argName("username:password")
            .build();
    private static final Option OPTION_ZK_KRB_CONF_FILE = Option.builder("k")
            .longOpt("krb-conf")
            .desc("JAAS file containing Kerberos config")
            .hasArg()
            .argName("jaas-filename")
            .numberOfArgs(1)
            .build();
    private static final Option OPTION_IGNORE_SOURCE = Option.builder()
            .longOpt("ignore-source")
            .desc("ignores the source ZooKeeper endpoint specified in the exported data")
            .build();
    private static final Option OPTION_USE_EXISTING_ACL = Option.builder()
            .longOpt("use-existing-acl")
            .desc("allow write of existing acl data to destination")
            .build();

    private static Options createOptions() {
        final Options options = new Options();
        options.addOption(OPTION_ZK_MIGRATOR_HELP);
        options.addOption(OPTION_ZK_ENDPOINT_SRC);
        options.addOption(OPTION_ZK_ENDPOINT_DST);
        options.addOption(OPTION_ZK_AUTH_INFO);
        options.addOption(OPTION_IGNORE_SOURCE);
        options.addOption(OPTION_USE_EXISTING_ACL);
        final OptionGroup optionGroupAuth = new OptionGroup().addOption(OPTION_ZK_AUTH_INFO).addOption(OPTION_ZK_KRB_CONF_FILE);
        optionGroupAuth.setRequired(false);
        options.addOptionGroup(optionGroupAuth);

        return options;
    }

    private static void printUsage(String errorMessage, Options options) {
        Preconditions.checkNotNull(options, "command line options were not specified");
        if (errorMessage != null) {
            System.out.println(errorMessage + System.lineSeparator());
        }
        HelpFormatter helpFormatter = new HelpFormatter();
        helpFormatter.setWidth(160);
        helpFormatter.setDescPadding(0);
        helpFormatter.printHelp(ZooKeeperMigratorMain.class.getCanonicalName(), HEADER, options, FOOTER, true);
    }

    public static void main(String[] args) throws IOException {
        PrintStream output = System.out;
        System.setOut(System.err);

        final Options options = createOptions();
        final CommandLine commandLine;
        try {
            commandLine = new DefaultParser().parse(options, args);
            if (commandLine.hasOption(OPTION_ZK_MIGRATOR_HELP.getLongOpt())) {
                printUsage(null, options);
            } else {
                log.info("zookeeper migration has started...");
                final String zookeeperSrc = commandLine.getOptionValue(OPTION_ZK_ENDPOINT_SRC.getOpt());
                final String zookeeperDst = commandLine.getOptionValue(OPTION_ZK_ENDPOINT_DST.getOpt());
                final String filename = "zkData";
                final String auth = commandLine.getOptionValue(OPTION_ZK_AUTH_INFO.getOpt());
                final String jaasFilename = commandLine.getOptionValue(OPTION_ZK_KRB_CONF_FILE.getOpt());
                final boolean ignoreSource = commandLine.hasOption(OPTION_IGNORE_SOURCE.getLongOpt());
                final boolean useExistingACL = commandLine.hasOption(OPTION_USE_EXISTING_ACL.getLongOpt());
                final ZooKeeperMigrator.AuthMode authMode;
                final byte[] authData;
                if (auth != null) {
                    authMode = ZooKeeperMigrator.AuthMode.DIGEST;
                    authData = auth.getBytes(StandardCharsets.UTF_8);
                } else {
                    authData = null;
                    if (!Strings.isNullOrEmpty(jaasFilename)) {
                        authMode = ZooKeeperMigrator.AuthMode.SASL;
                        System.setProperty("java.security.auth.login.config", jaasFilename);
                    } else {
                        authMode = ZooKeeperMigrator.AuthMode.OPEN;
                    }
                }
                final ZooKeeperMigrator zookeeperMigratorReader = new ZooKeeperMigrator(zookeeperSrc);
                final ZooKeeperMigrator zookeeperMigratorWriter = new ZooKeeperMigrator(zookeeperDst);

                File file = Paths.get(filename).toFile();
                if (file.exists()) {
                    boolean deleted = file.delete();
                    if (!deleted) {
                        throw new IOException(String.format("unable to delete file: %s", file.getName()));
                    }
                }

                try (OutputStream zkData = new FileOutputStream(file)) {
                    zookeeperMigratorReader.readZooKeeper(zkData, authMode, authData);
                }

                try (InputStream zkData = new FileInputStream(file)) {
                    zookeeperMigratorWriter.writeZooKeeper(zkData, authMode, authData, ignoreSource, useExistingACL);
                }

                log.info("zookeeper migration has finished...");
            }
        } catch (ParseException e) {
            printUsage(e.getLocalizedMessage(), options);
        } catch (IOException | KeeperException | InterruptedException | ExecutionException e) {
            throw new IOException(String.format("unable to perform operation: %s", e.getLocalizedMessage()), e);
        }
    }
}
