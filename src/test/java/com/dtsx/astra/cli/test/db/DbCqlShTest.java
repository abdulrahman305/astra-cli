package com.dtsx.astra.cli.test.db;

import com.dtsx.astra.cli.db.cqlsh.ServiceCqlShell;
import com.dtsx.astra.cli.db.dsbulk.ServiceDsBulk;
import com.dtsx.astra.cli.test.AbstractCmdTest;
import com.dtsx.astra.cli.utils.AstraCliUtils;
import com.dtsx.astra.cli.utils.FileUtils;
import org.junit.jupiter.api.*;

import java.io.File;

/**
 * Test on cqlsh.
 */
public class DbCqlShTest extends AbstractCmdTest {

    /** dataset. */
    public final static String DB_TEST       = "astra_cli_test";
    public final static String KEYSPACE_TEST = "dsbulk";
    public final static String TABLE_TEST    = "cities_by_country";

    @BeforeAll
    public static void initForCqlsh() {
        assertSuccessCli("db create %s -k %s --if-not-exist".formatted(DB_TEST, DB_TEST));
        assertSuccessCli("db cqlsh %s -f src/test/resources/cdc_dataset.cql".formatted(DB_TEST));
    }

    @Test
    @Order(1)
    @DisplayName("Installing cqlsh")
    public void testShouldInstallCqlSh() {
        if (!disableTools) {
            File cqlshFolder = new File(AstraCliUtils.ASTRA_HOME + File.separator + "cqlsh-astra");
            FileUtils.deleteDirectory(cqlshFolder);
            // install
            ServiceCqlShell.getInstance().install();
            Assertions.assertTrue(ServiceCqlShell.getInstance().isInstalled());
        }
    }

    @Test
    @Order(1)
    @DisplayName("Execute command")
    public void testShouldExecute() {
        assertSuccessCql(DB_TEST, "select * from astra_cli_test.demo LIMIT 20;");
    }
}
