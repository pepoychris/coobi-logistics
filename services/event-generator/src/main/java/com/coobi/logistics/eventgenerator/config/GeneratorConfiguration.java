package com.coobi.logistics.eventgenerator.config;

import com.coobi.logistics.eventgenerator.simulation.VehicleSimulationSettings;
import com.coobi.logistics.eventgenerator.simulation.VehicleTelemetrySimulator;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the simulation domain, which is deliberately free of Spring and Kafka types.
 */
@Configuration(proxyBeanMethods = false)
public class GeneratorConfiguration {

    /** All timestamps of the platform are UTC (Global Rule 12). */
    @Bean
    public Clock telemetryClock() {
        return Clock.systemUTC();
    }

    @Bean
    public VehicleSimulationSettings vehicleSimulationSettings(GeneratorProperties properties) {
        GeneratorProperties.Simulation simulation = properties.getSimulation();
        return new VehicleSimulationSettings(
                simulation.getVehicleIdPrefix(),
                properties.effectiveVehicleCount(),
                simulation.getRandomSeed(),
                simulation.getCenterLatitude(),
                simulation.getCenterLongitude(),
                simulation.getAreaRadiusKilometres(),
                simulation.getMinSpeedKph(),
                simulation.getMaxSpeedKph(),
                simulation.getMaxSpeedDeltaKph(),
                simulation.getMaxHeadingDeltaDegrees(),
                simulation.getMaxElapsedSeconds());
    }

    @Bean
    public VehicleTelemetrySimulator vehicleTelemetrySimulator(
            VehicleSimulationSettings settings, Clock telemetryClock) {
        return new VehicleTelemetrySimulator(settings, telemetryClock);
    }
}
