package com.bottrading.interfaces.cli.commands;

import com.bottrading.interfaces.cli.CliCommandContext;

/**
 * Command contract for interactive CLI commands.
 */
public interface CliCommand {

    String name();

    void execute(String[] parts, CliCommandContext context);
}
