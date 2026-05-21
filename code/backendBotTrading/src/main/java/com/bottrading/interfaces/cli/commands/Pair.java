package com.bottrading.interfaces.cli.commands;

/**
 * Generic pair value used by CLI command helpers.
 */
public record Pair<L, R>(L left, R right) {
}
