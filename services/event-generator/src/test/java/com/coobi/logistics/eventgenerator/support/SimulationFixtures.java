package com.coobi.logistics.eventgenerator.support;

import com.coobi.logistics.eventgenerator.simulation.VehicleSimulationSettings;

/** Shared simulation settings used by the tests, mirroring the shipped defaults. */
public final class SimulationFixtures {

    private SimulationFixtures() {
    }

    public static VehicleSimulationSettings settings(String prefix, int vehicleCount, double minSpeed, double maxSpeed) {
        return new VehicleSimulationSettings(
                prefix, vehicleCount, 42L, 39.4699, -0.3763, 30.0, minSpeed, maxSpeed, 5.0, 12.0, 10.0);
    }

    public static VehicleSimulationSettings normalModeFleet() {
        return settings("TRUCK-", 1000, 0.0, 110.0);
    }
}
