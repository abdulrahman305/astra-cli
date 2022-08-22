package com.datastax.astra.shell.cmd.db;

import static com.datastax.astra.shell.utils.CqlShellUtils.installCqlShellAstra;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import com.datastax.astra.sdk.databases.DatabaseClient;
import com.datastax.astra.sdk.databases.DatabasesClient;
import com.datastax.astra.sdk.databases.domain.CloudProviderType;
import com.datastax.astra.sdk.databases.domain.Database;
import com.datastax.astra.sdk.databases.domain.DatabaseCreationRequest;
import com.datastax.astra.sdk.databases.domain.DatabaseRegionServerless;
import com.datastax.astra.sdk.databases.domain.Datacenter;
import com.datastax.astra.shell.AstraCli;
import com.datastax.astra.shell.ExitCode;
import com.datastax.astra.shell.ShellContext;
import com.datastax.astra.shell.cmd.BaseCommand;
import com.datastax.astra.shell.out.JsonOutput;
import com.datastax.astra.shell.out.LoggerShell;
import com.datastax.astra.shell.out.ShellPrinter;
import com.datastax.astra.shell.out.ShellTable;
import com.datastax.astra.shell.utils.CqlShellOptions;
import com.datastax.astra.shell.utils.CqlShellUtils;
import com.datastax.astra.shell.utils.DsBulkUtils;

/**
 * Utility class for command `db`
 *
 * @author Cedrick LUNVEN (@clunven)
 */
public class OperationsDb {

    /** Command constants. */
    public static final String DB                    = "db";
    
    /** Command constants. */
    public static final String CMD_KEYSPACE          = "keyspace";
    
    /** Command constants. */
    public static final String CMD_INFO              = "info";
    
    /** Command constants. */
    public static final String CMD_CREATE_KEYSPACE   = "create-keyspace";
    
    /** Command constants. */
    public static final String CMD_DELETE_KEYSPACE   = "delete-keyspace";
    
    /** Command constants. */
    public static final String CMD_CREATE_REGION     = "create-region";
    
    /** Command constants. */
    public static final String CMD_DELETE_REGION     = "delete-region";
    
    /** Command constants. */
    public static final String CMD_DOWNLOAD_SCB      = "download-scb";
    
    /** Default region. **/
    public static final String DEFAULT_REGION        = "us-east-1";
    
    /** Default tier. **/
    public static final String DEFAULT_TIER          = "serverless";
    
    /** Allow Snake case. */
    public static final String KEYSPACE_NAME_PATTERN = "^[_a-z0-9]+$";
    
    /** column names. */
    public static final String COLUMN_ID                = "id";
    /** column names. */
    public static final String COLUMN_NAME              = "Name";
    /** column names. */
    public static final String COLUMN_DEFAULT_REGION    = "Default Region";
    /** column names. */
    public static final String COLUMN_REGIONS           = "Regions";
    /** column names. */
    public static final String COLUMN_DEFAULT_CLOUD     = "Default Cloud Provider";
    /** column names. */
    public static final String COLUMN_STATUS            = "Status";
    /** column names. */
    public static final String COLUMN_DEFAULT_KEYSPACE  = "Default Keyspace";
    /** column names. */
    public static final String COLUMN_KEYSPACES         = "Keyspaces";
    /** column names. */
    public static final String COLUMN_CREATION_TIME     = "Creation Time";
    
    /**
     * Hide default constructor.
     */
    private OperationsDb() {}
    
    /**
     * Load the databaseClient by user input.
     * 
     * @param db
     *      database name or identifier
     * @return
     *      db id
     */
    public static Optional<DatabaseClient> getDatabaseClient(String db) {
        DatabasesClient dbsClient = ShellContext.getInstance().getApiDevopsDatabases();
        
        // Try with the id (fastest)
        DatabaseClient dbClient = dbsClient.database(db);
        if (dbClient.exist()) {
            return Optional.ofNullable(dbClient);
        }
        
        // Not found, try with the name
        List<Database> dbs = dbsClient
                .databasesNonTerminatedByName(db)
                .collect(Collectors.toList());
        
        // Multiple db with this name
        if (dbs.size() > 1) {
            ShellPrinter.outputError(ExitCode.INVALID_PARAMETER, "There are '" + dbs.size() + "' dbs with this name, try with id.");
            return Optional.empty();
        }
        
        // Db Found
        if (1 == dbs.size()) {
            return Optional.ofNullable(dbsClient.database(dbs.get(0).getId()));
        }
        
        LoggerShell.warning("Database " + db + " has not been found");
        return Optional.empty();
    }
    
    /**
     * Mutualization of create db code (shell and cli)
     * 
     * @param databaseName
     *      db name
     * @param databaseRegion
     *      db region
     * @param keyspace
     *      db ks
     * @param ifNotExist
     *      will create if needed
     * @return
     *      exit code
     */
    public static ExitCode createDb(String databaseName, String databaseRegion, String keyspace, boolean ifNotExist) {
        
        // Lookup for available serverless regions
        Map<String, DatabaseRegionServerless> regionMap = ShellContext.getInstance().getApiDevopsOrganizations()
                .regionsServerless()
                .collect(Collectors
                .toMap(DatabaseRegionServerless::getName, Function.identity()));
        
        // Validate region
        if (!regionMap.containsKey(databaseRegion)) {
            ShellPrinter.outputError(ExitCode.NOT_FOUND, "Database region '" + databaseRegion + "' has not been found");
            return ExitCode.NOT_FOUND;
        } else {
            LoggerShell.info("Region '" + databaseRegion + "' is valid");
        }
        
        // Defaulting keyspace (if needed)
        if (StringUtils.isEmpty(keyspace)) {
            keyspace = databaseName.toLowerCase().replaceAll(" ", "_");
        }
        
        // Validate keyspace
        if (!keyspace.matches(OperationsDb.KEYSPACE_NAME_PATTERN)) {
            ShellPrinter.outputError(ExitCode.INVALID_PARAMETER, "The keyspace name is not valid, please use snake_case: [a-z0-9_]");
            return ExitCode.INVALID_PARAMETER;
        } else {
            LoggerShell.info("Using keyspace '" + keyspace + "'");
        }
        
        Optional<DatabaseClient> dbClient = getDatabaseClient(databaseName);
        
        if (!ifNotExist || !dbClient.isPresent()) {
            // We are ok to proceed
            String dbId = ShellContext.getInstance().getApiDevopsDatabases()
                    .createDatabase(DatabaseCreationRequest.builder()
                            .name(databaseName)
                            .tier(OperationsDb.DEFAULT_TIER)
                            .cloudProvider(CloudProviderType.valueOf(regionMap
                                    .get(databaseRegion)
                                    .getCloudProvider()
                                    .toUpperCase()))
                            .cloudRegion(databaseRegion)
                            .keyspace(keyspace)
                            .build());
            ShellPrinter.outputSuccess("Database [" + dbId + "] created.");
        } else {
            LoggerShell.info("Database already exist id is [" + dbClient.get().getDatabaseId() + "]");
            Set<String> existingkeyspace = dbClient.get().find().get().getInfo().getKeyspaces();
            if (!existingkeyspace.contains(keyspace)) {
                LoggerShell.info("Keyspace '"+ keyspace + "' does not exist ... creating");
                dbClient.get().createKeyspace(keyspace);
            } else {
                LoggerShell.info("Keyspace '" + keyspace + "' already exists, no actions");
            }
        }
        return ExitCode.SUCCESS;
    }
    
    /**
     * List Databases.
     * 
     * @return
     *      returned code
     */
    public static ExitCode listDb() {
        ShellTable sht = new ShellTable();
        sht.addColumn(COLUMN_NAME,    20);
        sht.addColumn(COLUMN_ID,      37);
        sht.addColumn(COLUMN_DEFAULT_REGION, 20);
        sht.addColumn(COLUMN_STATUS,  15);
        ShellContext.getInstance()
           .getApiDevopsDatabases()
           .databasesNonTerminated()
           .forEach(db -> {
                Map <String, String> rf = new HashMap<>();
                rf.put(COLUMN_NAME,    db.getInfo().getName());
                rf.put(COLUMN_ID,      db.getId());
                rf.put(COLUMN_DEFAULT_REGION, db.getInfo().getRegion());
                rf.put(COLUMN_STATUS,  db.getStatus().name());
                sht.getCellValues().add(rf);
        });
        ShellPrinter.printShellTable(sht);
        return ExitCode.SUCCESS;
    }
    
    /**
     * Delete a dabatase if exist.
     * 
     * @param databaseName
     *      db name or db id
     * @return
     *      status
     */
    public static ExitCode deleteDb(String databaseName) {
        Optional<DatabaseClient> dbClient = getDatabaseClient(databaseName);
        if (dbClient.isPresent()) {
            dbClient.get().delete();
            ShellPrinter.outputSuccess("Deleting Database '" + databaseName + "' (async operation)");
            return ExitCode.SUCCESS;
        }
        return ExitCode.NOT_FOUND;
    }
    
    /**
     * Show database details.
     *
     * @param databaseName
     *      database name and id
     * @return
     *      status code
     */
    public static ExitCode showDb(String databaseName) {
        Optional<DatabaseClient> dbClient = getDatabaseClient(databaseName);
        if (dbClient.isPresent()) {
            Database db = dbClient.get().find().get();
            ShellTable sht = ShellTable.propertyTable(15, 40);
            sht.addPropertyRow(COLUMN_NAME, db.getInfo().getName());
            sht.addPropertyRow(COLUMN_ID, db.getId());
            sht.addPropertyRow(COLUMN_STATUS, db.getStatus().toString());
            sht.addPropertyRow(COLUMN_DEFAULT_CLOUD, db.getInfo().getCloudProvider().name());
            sht.addPropertyRow(COLUMN_DEFAULT_REGION, db.getInfo().getRegion());
            sht.addPropertyRow(COLUMN_DEFAULT_KEYSPACE, db.getInfo().getKeyspace());
            sht.addPropertyRow("Creation Time", db.getCreationTime());
            List<String> regions   = db.getInfo().getDatacenters().stream().map(Datacenter::getRegion).collect(Collectors.toList());
            List<String> keyspaces = new ArrayList<>(db.getInfo().getKeyspaces());
            switch(ShellContext.getInstance().getOutputFormat()) {
                case csv:
                    sht.addPropertyRow(COLUMN_REGIONS, regions.toString());
                    sht.addPropertyRow(COLUMN_KEYSPACES, keyspaces.toString());
                    ShellPrinter.printShellTable(sht);
                break;
                case json:
                    ShellPrinter.printJson(new JsonOutput(ExitCode.SUCCESS, 
                            OperationsDb.DB + " " + BaseCommand.GET + " " + databaseName, db));
                break;
                case human:
                default:
                    sht.addPropertyListRows(COLUMN_KEYSPACES, keyspaces);
                    sht.addPropertyListRows(COLUMN_REGIONS, regions);
                    ShellPrinter.printShellTable(sht);
                break;
            }
            return ExitCode.SUCCESS;
        }
        return ExitCode.NOT_FOUND;
    }
    
    
    /**
     * Download the cloud secure bundles.
     * 
     * @param databaseName
     *      database name and id
     * @return
     *      status code
     */
    public static ExitCode downloadCloudSecureBundles(String databaseName) {
        Optional<DatabaseClient> dbClient = getDatabaseClient(databaseName);
        if (dbClient.isPresent()) {
            dbClient
                .get()
                .downloadAllSecureConnectBundles(AstraCli.ASTRA_HOME + File.separator + AstraCli.SCB_FOLDER);
            LoggerShell.success("Secure connect bundles have been downloaded.");
            return ExitCode.SUCCESS;
        }
        return ExitCode.NOT_FOUND;
    }
    
    /**
     * Create a keyspace if not exist.
     * 
     * @param ifNotExist
     *      flag to disable error if already exists
     * @param databaseName
     *      db name
     * @param keyspaceName
     *      ks name
     * @return
     *      exit code
     */
    public static ExitCode createKeyspace(String databaseName, String keyspaceName, boolean ifNotExist) {
        
        // Validate keyspace
        if (!keyspaceName.matches(OperationsDb.KEYSPACE_NAME_PATTERN)) {
            ShellPrinter.outputError(ExitCode.INVALID_PARAMETER, "The keyspace name is not valid, please use snake_case: [a-z0-9_]");
            return ExitCode.INVALID_PARAMETER;
        }
        
        // Validate db Name
        Optional<DatabaseClient> dbClient = getDatabaseClient(databaseName);
        if (dbClient.isPresent()) {
            Set<String> existingkeyspaces = dbClient.get().find().get().getInfo().getKeyspaces();
            if (existingkeyspaces.contains(keyspaceName)) {
                if (ifNotExist) {
                    LoggerShell.info("Keyspace '" + keyspaceName + "' already exists.");
                    return ExitCode.SUCCESS;
                } else {
                    LoggerShell.error("Keyspace '" + keyspaceName + "' already exists");
                    return ExitCode.ALREADY_EXIST;
                }
            } else {
                dbClient.get().createKeyspace(keyspaceName);
                LoggerShell.info("Keyspace '"+ keyspaceName + "' created.");
            }
            return ExitCode.SUCCESS;
        }
        
        // Db not found bummer !
        return ExitCode.NOT_FOUND;
    }
    
    
    /**
     * Start CqlShell when needed.
     * 
     * @param options
     *      shell options
     * @param database
     *      current db
     * @return
     */
    public static ExitCode startCqlShell(CqlShellOptions options, String database) {
        
        // Install Cqlsh for Astra and set permissions
        installCqlShellAstra();
        
        // Download SCB for target db if needed
        downloadCloudSecureBundles(database);    
        
        try {
            Optional<DatabaseClient> dbClient = OperationsDb.getDatabaseClient(database);
            if (dbClient.isPresent()) {
                Database db = dbClient.get().find().get();
                System.out.println("\nCqlsh is starting please wait for connection establishment...");
                Process cqlShProc = CqlShellUtils.runCqlShellAstra(options, db);
                if (cqlShProc == null) ExitCode.INTERNAL_ERROR.exit();
                cqlShProc.waitFor();
            } else {
                return ExitCode.NOT_FOUND;
            }
        } catch (IOException e) {
            LoggerShell.error("Cannot start CQLSH");
            ExitCode.INTERNAL_ERROR.exit();
        } catch (InterruptedException e) {}
        return ExitCode.SUCCESS;
    }
    
    /**
     * Start DsBulk when needed.
     * 
     * @param options
     *      dsbulks options, database name is the first argument
     * @return
     *      exit code
     */
    public static ExitCode runDsBulk(List<String> options) {
        // Install dsbulk for Astra and set permissions
        DsBulkUtils.installDsBulk();
        
        try {
            Optional<DatabaseClient> dbClient = OperationsDb.getDatabaseClient(options.get(0));
            if (dbClient.isPresent()) {
                Database db = dbClient.get().find().get();
                System.out.println("\nDSBulk is starting please wait ...");
                Process dsbulkProc = DsBulkUtils.runDsBulk(db, options.subList(1, options.size()));
                if (dsbulkProc == null) ExitCode.INTERNAL_ERROR.exit();
                dsbulkProc.waitFor();
            } else {
                return ExitCode.NOT_FOUND;
            }
        } catch (IOException e) {
            LoggerShell.error("Cannot start DSBULK");
            ExitCode.INTERNAL_ERROR.exit();
        } catch (InterruptedException e) {}
        return ExitCode.SUCCESS;
    }
}
