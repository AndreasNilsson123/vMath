package vmath.geo;

import vmath.annotations.Experimental;

/**
 * The units of navigation as exact constants, and conversions to and from SI (metres, metres per
 * second, radians).
 *
 * <p>The nautical mile (1852 m), the international foot (0.3048 m), the statute mile (1609.344 m),
 * the knot (one nautical mile per hour) and the foot per minute are defined by exact ratios to the
 * metre and the second, so the constants are exact in decimal and the conversions are one
 * multiplication or division (the rounding of a double aside). A flight level is a hundred feet of
 * pressure altitude: the unit conversion is the library's, what the pressure setting means is not.
 *
 * <p><b>Thread safety.</b> Stateless: safe to call from any number of threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * double metres = Units.Length.NAUTICAL_MILES.toMeters(12.5);          // 23 150 m
 * double knots = Units.Speed.KNOTS.fromMetersPerSecond(130.0);          // 252.7 kt
 * double feet = Units.metersToFeet(3048.0);                             // 10 000 ft
 * }</pre>
 */
@Experimental("new in 0.2: the map layer may change")
public final class Units {

    /** Metres in a nautical mile (exact, by definition). */
    public static final double METERS_PER_NAUTICAL_MILE = 1852.0;
    /** Metres in an international foot (exact, by definition). */
    public static final double METERS_PER_FOOT = 0.3048;
    /** Metres in a statute mile (exact: 5280 international feet). */
    public static final double METERS_PER_STATUTE_MILE = 1609.344;
    /** Metres in a kilometre. */
    public static final double METERS_PER_KILOMETER = 1000.0;
    /** Metres in a flight level step: a hundred feet. */
    public static final double METERS_PER_FLIGHT_LEVEL = 100.0 * METERS_PER_FOOT;
    /** Metres per second in a knot (exact: 1852 m in 3600 s). */
    public static final double METERS_PER_SECOND_PER_KNOT = METERS_PER_NAUTICAL_MILE / 3600.0;
    /** Metres per second in a foot per minute (exact: 0.3048 m in 60 s). */
    public static final double METERS_PER_SECOND_PER_FOOT_PER_MINUTE = METERS_PER_FOOT / 60.0;
    /** Metres per second in a kilometre per hour. */
    public static final double METERS_PER_SECOND_PER_KILOMETER_PER_HOUR = 1000.0 / 3600.0;
    /** Metres per second in a statute mile per hour. */
    public static final double METERS_PER_SECOND_PER_MILE_PER_HOUR = METERS_PER_STATUTE_MILE / 3600.0;

    private Units() {
    }

    /** The units of length. */
    public enum Length {
        /** Metres, {@code m}. */
        METERS("m", 1.0),
        /** Kilometres, {@code km}. */
        KILOMETERS("km", METERS_PER_KILOMETER),
        /** Nautical miles, {@code NM}. */
        NAUTICAL_MILES("NM", METERS_PER_NAUTICAL_MILE),
        /** Statute miles, {@code mi}. */
        STATUTE_MILES("mi", METERS_PER_STATUTE_MILE),
        /** International feet, {@code ft}. */
        FEET("ft", METERS_PER_FOOT);

        private final String symbol;
        private final double meters;

        Length(String symbol, double meters) {
            this.symbol = symbol;
            this.meters = meters;
        }

        /**
         * Gives the usual symbol.
         *
         * @return the symbol, such as {@code NM}
         */
        public String symbol() {
            return symbol;
        }

        /**
         * Gives the size of the unit.
         *
         * @return metres in one unit
         */
        public double meters() {
            return meters;
        }

        /**
         * Converts to metres.
         *
         * @param value a length in this unit
         * @return metres
         */
        public double toMeters(double value) {
            return value * meters;
        }

        /**
         * Converts from metres.
         *
         * @param meters a length in metres
         * @return the length in this unit
         */
        public double fromMeters(double meters) {
            return meters / this.meters;
        }
    }

    /** The units of speed, vertical speed included. */
    public enum Speed {
        /** Metres per second, {@code m/s}. */
        METERS_PER_SECOND("m/s", 1.0),
        /** Knots, {@code kt}. */
        KNOTS("kt", METERS_PER_SECOND_PER_KNOT),
        /** Kilometres per hour, {@code km/h}. */
        KILOMETERS_PER_HOUR("km/h", METERS_PER_SECOND_PER_KILOMETER_PER_HOUR),
        /** Statute miles per hour, {@code mph}. */
        MILES_PER_HOUR("mph", METERS_PER_SECOND_PER_MILE_PER_HOUR),
        /** Feet per minute, {@code fpm}. */
        FEET_PER_MINUTE("fpm", METERS_PER_SECOND_PER_FOOT_PER_MINUTE);

        private final String symbol;
        private final double metersPerSecond;

        Speed(String symbol, double metersPerSecond) {
            this.symbol = symbol;
            this.metersPerSecond = metersPerSecond;
        }

        /**
         * Gives the usual symbol.
         *
         * @return the symbol, such as {@code kt}
         */
        public String symbol() {
            return symbol;
        }

        /**
         * Gives the size of the unit.
         *
         * @return metres per second in one unit
         */
        public double metersPerSecond() {
            return metersPerSecond;
        }

        /**
         * Converts to metres per second.
         *
         * @param value a speed in this unit
         * @return metres per second
         */
        public double toMetersPerSecond(double value) {
            return value * metersPerSecond;
        }

        /**
         * Converts from metres per second.
         *
         * @param metersPerSecond a speed in metres per second
         * @return the speed in this unit
         */
        public double fromMetersPerSecond(double metersPerSecond) {
            return metersPerSecond / this.metersPerSecond;
        }
    }

    /**
     * Converts metres to nautical miles.
     *
     * @param meters metres
     * @return nautical miles
     */
    public static double metersToNauticalMiles(double meters) {
        return meters / METERS_PER_NAUTICAL_MILE;
    }

    /**
     * Converts nautical miles to metres.
     *
     * @param nauticalMiles nautical miles
     * @return metres
     */
    public static double nauticalMilesToMeters(double nauticalMiles) {
        return nauticalMiles * METERS_PER_NAUTICAL_MILE;
    }

    /**
     * Converts metres to feet.
     *
     * @param meters metres
     * @return international feet
     */
    public static double metersToFeet(double meters) {
        return meters / METERS_PER_FOOT;
    }

    /**
     * Converts feet to metres.
     *
     * @param feet international feet
     * @return metres
     */
    public static double feetToMeters(double feet) {
        return feet * METERS_PER_FOOT;
    }

    /**
     * Converts metres per second to knots.
     *
     * @param metersPerSecond metres per second
     * @return knots
     */
    public static double metersPerSecondToKnots(double metersPerSecond) {
        return metersPerSecond / METERS_PER_SECOND_PER_KNOT;
    }

    /**
     * Converts knots to metres per second.
     *
     * @param knots knots
     * @return metres per second
     */
    public static double knotsToMetersPerSecond(double knots) {
        return knots * METERS_PER_SECOND_PER_KNOT;
    }

    /**
     * Converts metres per second to feet per minute.
     *
     * @param metersPerSecond metres per second
     * @return feet per minute
     */
    public static double metersPerSecondToFeetPerMinute(double metersPerSecond) {
        return metersPerSecond / METERS_PER_SECOND_PER_FOOT_PER_MINUTE;
    }

    /**
     * Converts feet per minute to metres per second.
     *
     * @param feetPerMinute feet per minute
     * @return metres per second
     */
    public static double feetPerMinuteToMetersPerSecond(double feetPerMinute) {
        return feetPerMinute * METERS_PER_SECOND_PER_FOOT_PER_MINUTE;
    }

    /**
     * Converts a height in metres to a flight level.
     *
     * @param meters a pressure altitude in metres
     * @return the flight level as a number of hundreds of feet, not rounded (FL 100 is 10 000 ft)
     */
    public static double metersToFlightLevel(double meters) {
        return meters / METERS_PER_FLIGHT_LEVEL;
    }
}
