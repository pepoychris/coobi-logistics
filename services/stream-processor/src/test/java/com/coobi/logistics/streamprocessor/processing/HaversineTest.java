package com.coobi.logistics.streamprocessor.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/** MVP-3.2: the geographic distance the stopped-vehicle detection is measured with. */
class HaversineTest {

    private static final double VALENCIA_LATITUDE = 39.4699;
    private static final double VALENCIA_LONGITUDE = -0.3763;
    private static final double MADRID_LATITUDE = 40.4168;
    private static final double MADRID_LONGITUDE = -3.7038;

    /** One degree of latitude on the mean radius of the Earth. */
    private static final double ONE_DEGREE_OF_LATITUDE_METERS = 111_195.08;

    /** Published great-circle distance between the two demonstration cities. */
    private static final double VALENCIA_TO_MADRID_METERS = 302_557.94;

    private static final double EARTH_RADIUS_METERS = 6_371_008.8;

    @Test
    void isZeroBetweenIdenticalPositions() {
        assertThat(Haversine.distanceMeters(
                        VALENCIA_LATITUDE, VALENCIA_LONGITUDE, VALENCIA_LATITUDE, VALENCIA_LONGITUDE))
                .isEqualTo(0.0);
    }

    @Test
    void measuresOneDegreeOfLatitudeAsAboutOneHundredAndElevenKilometres() {
        assertThat(Haversine.distanceMeters(0.0, 0.0, 1.0, 0.0))
                .isCloseTo(ONE_DEGREE_OF_LATITUDE_METERS, within(1.0));
    }

    @Test
    void measuresAKnownDistanceBetweenTwoCities() {
        assertThat(Haversine.distanceMeters(
                        VALENCIA_LATITUDE, VALENCIA_LONGITUDE, MADRID_LATITUDE, MADRID_LONGITUDE))
                .isCloseTo(VALENCIA_TO_MADRID_METERS, within(1.0));
    }

    @Test
    void isSymmetric() {
        double there = Haversine.distanceMeters(
                VALENCIA_LATITUDE, VALENCIA_LONGITUDE, MADRID_LATITUDE, MADRID_LONGITUDE);
        double back = Haversine.distanceMeters(
                MADRID_LATITUDE, MADRID_LONGITUDE, VALENCIA_LATITUDE, VALENCIA_LONGITUDE);

        assertThat(back).isEqualTo(there);
    }

    @Test
    void measuresSmallStepsInMetres() {
        // The simulated fleet moves in steps of tens of metres, which a planar approximation
        // of the same coordinates would still agree with; the interesting part is that the
        // scale is metres and not degrees.
        assertThat(Haversine.distanceMeters(VALENCIA_LATITUDE, VALENCIA_LONGITUDE, 39.4709, VALENCIA_LONGITUDE))
                .isCloseTo(111.2, within(0.05));
        assertThat(Haversine.distanceMeters(VALENCIA_LATITUDE, VALENCIA_LONGITUDE, 39.46999, VALENCIA_LONGITUDE))
                .isCloseTo(10.01, within(0.01));
    }

    @Test
    void shrinksADegreeOfLongitudeAwayFromTheEquator() {
        double atEquator = Haversine.distanceMeters(0.0, 0.0, 0.0, 1.0);
        double atValencia = Haversine.distanceMeters(
                VALENCIA_LATITUDE, VALENCIA_LONGITUDE, VALENCIA_LATITUDE, VALENCIA_LONGITUDE + 1.0);

        assertThat(atEquator).isCloseTo(ONE_DEGREE_OF_LATITUDE_METERS, within(1.0));
        assertThat(atValencia)
                .isCloseTo(atEquator * Math.cos(Math.toRadians(VALENCIA_LATITUDE)), within(50.0))
                .isLessThan(atEquator);
    }

    @Test
    void measuresAnAntipodalPairWithoutLosingPrecision() {
        assertThat(Haversine.distanceMeters(0.0, 0.0, 0.0, 180.0))
                .isCloseTo(Math.PI * EARTH_RADIUS_METERS, within(1.0));
    }
}
