package com.datastax.astra.shell.cmd.config;

import com.github.rvesse.airline.annotations.Command;

/**
 * Show the list of available configurations.
 * 
 * astra list XXX
 *
 * @author Cedrick LUNVEN (@clunven)
 *
 */
@Command(name = "list", description = "Show the list of available configurations.")
public class ConfigList extends BaseConfigCommand {
    
    /** {@inheritDoc} */
    public void run() {
        OperationsConfig.listConfigurations(getAstraRc());
    }
    
    
}
