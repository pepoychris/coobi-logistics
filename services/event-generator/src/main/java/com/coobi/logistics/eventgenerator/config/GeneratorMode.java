package com.coobi.logistics.eventgenerator.config;

/**
 * Operating profile of the generator.
 *
 * <ul>
 *   <li>{@link #NORMAL}: demonstration defaults (1,000 vehicles at 1,000 events/s).
 *   <li>{@link #LOAD_TEST}: higher, independently configurable values.
 * </ul>
 */
public enum GeneratorMode {
    NORMAL,
    LOAD_TEST
}
