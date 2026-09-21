package com.coobi.logistics.streamprocessor.processing;

/**
 * Great-circle distance between two geographic points (MVP-3.2).
 *
 * <p>Telemetry carries geographic coordinates, so the distance between two positions is
 * the length of the arc that separates them on the sphere of the Earth rather than the
 * length of the segment that separates them on a plane: a degree of longitude covers only
 * about three quarters of a degree of latitude at the latitude of the simulated fleet. The
 * Haversine formula is used, which stays exact for the antipodal case that a spherical law
 * of cosines formulation loses to rounding.
 *
 * <p>The class is a pure function holder: it has no state and is therefore safe to share
 * between threads.
 */
public final class Haversine {

    /** Mean radius of the Earth in metres (IUGG mean radius, WGS-84 ellipsoid). */
    private static final double EARTH_RADIUS_METERS = 6_371_008.8;

    private Haversine() {
    }

    /**
     * Distance in metres between two positions.
     *
     * @param fromLatitude degrees, positive north
     * @param fromLongitude degrees, positive east
     * @param toLatitude degrees, positive north
     * @param toLongitude degrees, positive east
     * @return the arc length in metres, {@code 0.0} for two identical positions
     */
    public static double distanceMeters(
            double fromLatitude, double fromLongitude, double toLatitude, double toLongitude) {

        double latitudeDelta = Math.toRadians(toLatitude - fromLatitude);
        double longitudeDelta = Math.toRadians(toLongitude - fromLongitude);
        double fromLatitudeRadians = Math.toRadians(fromLatitude);
        double toLatitudeRadians = Math.toRadians(toLatitude);

        double latitudeTerm = Math.sin(latitudeDelta / 2);
        double longitudeTerm = Math.sin(longitudeDelta / 2);
        double a = latitudeTerm * latitudeTerm
                + Math.cos(fromLatitudeRadians) * Math.cos(toLatitudeRadians) * longitudeTerm * longitudeTerm;

        // Clamp before the square root: a rounding error above 1.0 would make asin NaN.
        double arc = 2 * Math.asin(Math.min(1.0, Math.sqrt(a)));
        return EARTH_RADIUS_METERS * arc;
    }
}
