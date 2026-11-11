package com.unspektrawesome.camera;

import android.hardware.camera2.CameraMetadata;

/** Converts the current Camera2 RAW white-balance gains to correlated color temperature. */
public final class WhiteBalanceTemperature {
    private static final double PCS_X = 0.3457;
    private static final double PCS_Y = 0.3585;
    private static final double[][] TEMPERATURE_TABLE = {
            {0, 0.18006, 0.26352, -0.24341},
            {10, 0.18066, 0.26589, -0.25479},
            {20, 0.18133, 0.26846, -0.26876},
            {30, 0.18208, 0.27119, -0.28539},
            {40, 0.18293, 0.27407, -0.30470},
            {50, 0.18388, 0.27709, -0.32675},
            {60, 0.18494, 0.28021, -0.35156},
            {70, 0.18611, 0.28342, -0.37915},
            {80, 0.18740, 0.28668, -0.40955},
            {90, 0.18880, 0.28997, -0.44278},
            {100, 0.19032, 0.29326, -0.47888},
            {125, 0.19462, 0.30141, -0.58204},
            {150, 0.19962, 0.30921, -0.70471},
            {175, 0.20525, 0.31647, -0.84901},
            {200, 0.21142, 0.32312, -1.0182},
            {225, 0.21807, 0.32909, -1.2168},
            {250, 0.22511, 0.33439, -1.4512},
            {275, 0.23247, 0.33904, -1.7298},
            {300, 0.24010, 0.34308, -2.0637},
            {325, 0.24702, 0.34655, -2.4681},
            {350, 0.25591, 0.34951, -2.9641},
            {375, 0.26400, 0.35200, -3.5814},
            {400, 0.27218, 0.35407, -4.3633},
            {425, 0.28039, 0.35577, -5.3762},
            {450, 0.28863, 0.35714, -6.7262},
            {475, 0.29685, 0.35823, -8.5955},
            {500, 0.30505, 0.35907, -11.324},
            {525, 0.31320, 0.35968, -15.628},
            {550, 0.32129, 0.36011, -23.325},
            {575, 0.32931, 0.36038, -40.770},
            {600, 0.33724, 0.36051, -116.45},
    };

    private WhiteBalanceTemperature() { }

    public static Integer fromCamera2Gains(float red, float green, float blue,
                                           ColorCalibration calibration) {
        if (calibration == null || !positive(red) || !positive(green) || !positive(blue)) {
            return null;
        }
        CalibrationProfile profile = profile(calibration);
        if (profile == null) return null;
        double[] neutral = new double[]{1.0 / red, 1.0 / green, 1.0 / blue};
        double[] xy = cameraNeutralToXy(neutral, profile);
        if (xy == null) return null;
        Double temperature = temperatureFromXy(xy[0], xy[1]);
        if (temperature == null || !Double.isFinite(temperature) || temperature <= 0.0) {
            return null;
        }
        long rounded = Math.round(temperature);
        return rounded < 1L || rounded > Integer.MAX_VALUE ? null : (int) rounded;
    }

    /** Sensor neutral for an absolute temperature on the Robertson zero-tint locus. */
    public static float[] cameraNeutralForTemperature(int kelvin, ColorCalibration calibration) {
        if (calibration == null || kelvin <= 0) return null;
        CalibrationProfile profile = profile(calibration);
        if (profile == null) return null;
        double mired = 1.0e6 / kelvin;
        if (mired > TEMPERATURE_TABLE[TEMPERATURE_TABLE.length - 1][0]) return null;
        for (int index = 1; index < TEMPERATURE_TABLE.length; index++) {
            double[] lower = TEMPERATURE_TABLE[index - 1];
            double[] upper = TEMPERATURE_TABLE[index];
            if (mired > upper[0]) continue;
            double fraction = (mired - lower[0]) / (upper[0] - lower[0]);
            double u = lower[1] + fraction * (upper[1] - lower[1]);
            double v = lower[2] + fraction * (upper[2] - lower[2]);
            double[] xyz = new double[]{1.5 * u / v, 1.0, (2.0 - 0.5 * u - 5.0 * v) / v};
            double[] matrix = matrixForTemperature((double) kelvin, profile);
            if (matrix == null) return null;
            double[] neutral = multiply(matrix, xyz);
            for (double channel : neutral) {
                if (!Double.isFinite(channel) || channel <= 0.0) return null;
            }
            return new float[]{(float) (neutral[0] / neutral[1]), 1f,
                    (float) (neutral[2] / neutral[1])};
        }
        return null;
    }

    private static CalibrationProfile profile(ColorCalibration calibration) {
        float[] matrix1 = calibratedMatrix(calibration.colorMatrix1(), calibration.calibrationMatrix1());
        float[] matrix2 = calibratedMatrix(calibration.colorMatrix2(), calibration.calibrationMatrix2());
        Double temperature1 = illuminantTemperature(calibration.illuminant1);
        Double temperature2 = illuminantTemperature(calibration.illuminant2);

        if (matrix1 == null || temperature1 == null) {
            matrix1 = null;
            temperature1 = null;
        }
        if (matrix2 == null || temperature2 == null) {
            matrix2 = null;
            temperature2 = null;
        }
        if (matrix1 == null && matrix2 == null) return null;
        if (matrix1 == null) return new CalibrationProfile(matrix2, temperature2, null, 0.0);
        if (matrix2 == null) return new CalibrationProfile(matrix1, temperature1, null, 0.0);
        if (temperature1 <= temperature2) {
            return new CalibrationProfile(matrix1, temperature1, matrix2, temperature2);
        }
        return new CalibrationProfile(matrix2, temperature2, matrix1, temperature1);
    }

    private static float[] normalizedMatrix(float[] source) {
        if (source == null || source.length != 9) return null;
        double[] matrix = new double[9];
        for (int index = 0; index < source.length; index++) {
            if (!Float.isFinite(source[index])) return null;
            matrix[index] = source[index];
        }
        double[] pcs = xyToXyz(PCS_X, PCS_Y);
        double[] coordinate = multiply(matrix, pcs);
        double maximum = Math.max(coordinate[0], Math.max(coordinate[1], coordinate[2]));
        if (!Double.isFinite(maximum) || maximum <= 0.0) return null;
        if (maximum < 0.99 || maximum > 1.01) {
            for (int index = 0; index < matrix.length; index++) matrix[index] /= maximum;
        }
        float[] result = new float[9];
        for (int index = 0; index < result.length; index++) result[index] = (float) matrix[index];
        return result;
    }

    public static Double illuminantTemperature(Integer illuminant) {
        if (illuminant == null) return null;
        switch (illuminant) {
            case CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_A:
                return 2850.0;
            case CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_B:
                return 5500.0;
            case CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_C:
                return 6500.0;
            case CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D50:
                return 5000.0;
            case CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D55:
                return 5500.0;
            case CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D65:
                return 6500.0;
            case CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D75:
                return 7500.0;
            default:
                return null;
        }
    }

    static float[] calibratedMatrix(float[] color, float[] calibration) {
        float[] normalized = normalizedMatrix(color);
        if (normalized == null || calibration == null) return normalized;
        float[] result = new float[9];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                for (int k = 0; k < 3; k++) {
                    result[row * 3 + column] += calibration[row * 3 + k] * normalized[k * 3 + column];
                }
            }
        }
        return result;
    }

    private static double[] cameraNeutralToXy(double[] neutral, CalibrationProfile profile) {
        double maximum = Math.max(neutral[0], Math.max(neutral[1], neutral[2]));
        if (!Double.isFinite(maximum) || maximum <= 0.0) return null;
        double[] normalized = new double[]{
                neutral[0] / maximum, neutral[1] / maximum, neutral[2] / maximum};
        double[] last = new double[]{PCS_X, PCS_Y};
        for (int iteration = 0; iteration < 30; iteration++) {
            double[] xyzToCamera = matrixForTemperature(temperatureFromXy(last[0], last[1]), profile);
            if (xyzToCamera == null) return null;
            double[] inverse = invert(xyzToCamera);
            if (inverse == null) return null;
            double[] nextXyz = multiply(inverse, normalized);
            double[] next = xyzToXy(nextXyz);
            if (next == null) return null;
            if (Math.abs(next[0] - last[0]) + Math.abs(next[1] - last[1]) < 0.0000001) {
                return next;
            }
            if (iteration == 29) {
                return new double[]{(last[0] + next[0]) * 0.5, (last[1] + next[1]) * 0.5};
            }
            last = next;
        }
        return last;
    }

    private static double[] matrixForTemperature(Double temperature, CalibrationProfile profile) {
        if (temperature == null) return null;
        if (profile.highMatrix == null) return toDouble(profile.lowMatrix);
        if (temperature <= profile.lowTemperature) return toDouble(profile.lowMatrix);
        if (temperature >= profile.highTemperature) return toDouble(profile.highMatrix);
        double inverseTemperature = 1.0 / temperature;
        double weight = (inverseTemperature - 1.0 / profile.highTemperature)
                / (1.0 / profile.lowTemperature - 1.0 / profile.highTemperature);
        return interpolate(profile.lowMatrix, profile.highMatrix, weight);
    }

    private static Double temperatureFromXy(double x, double y) {
        double denominator = 1.5 - x + 6.0 * y;
        if (!Double.isFinite(denominator) || Math.abs(denominator) <= 1e-12) return null;
        double u = 2.0 * x / denominator;
        double v = 3.0 * y / denominator;
        double lastDistance = 0.0;
        double lastDu = 0.0;
        double lastDv = 0.0;
        for (int index = 1; index <= 30; index++) {
            double du = 1.0;
            double dv = TEMPERATURE_TABLE[index][3];
            double length = Math.sqrt(1.0 + dv * dv);
            du /= length;
            dv /= length;
            double uu = u - TEMPERATURE_TABLE[index][1];
            double vv = v - TEMPERATURE_TABLE[index][2];
            double distance = -uu * dv + vv * du;
            if (distance <= 0.0 || index == 30) {
                if (distance > 0.0) distance = 0.0;
                distance = -distance;
                double weight = index == 1 ? 0.0
                        : distance / (lastDistance + distance);
                double mired = TEMPERATURE_TABLE[index - 1][0] * weight
                        + TEMPERATURE_TABLE[index][0] * (1.0 - weight);
                return mired > 0.0 ? 1.0e6 / mired : null;
            }
            lastDistance = distance;
            lastDu = du;
            lastDv = dv;
        }
        return null;
    }

    private static double[] xyToXyz(double x, double y) {
        return new double[]{x / y, 1.0, (1.0 - x - y) / y};
    }

    private static double[] xyzToXy(double[] xyz) {
        double total = xyz[0] + xyz[1] + xyz[2];
        if (!Double.isFinite(total) || total <= 0.0) return null;
        double x = xyz[0] / total;
        double y = xyz[1] / total;
        return Double.isFinite(x) && Double.isFinite(y) ? new double[]{x, y} : null;
    }

    private static double[] multiply(double[] matrix, double[] vector) {
        return new double[]{
                matrix[0] * vector[0] + matrix[1] * vector[1] + matrix[2] * vector[2],
                matrix[3] * vector[0] + matrix[4] * vector[1] + matrix[5] * vector[2],
                matrix[6] * vector[0] + matrix[7] * vector[1] + matrix[8] * vector[2],
        };
    }

    private static double[] invert(double[] matrix) {
        double determinant = matrix[0] * (matrix[4] * matrix[8] - matrix[5] * matrix[7])
                - matrix[1] * (matrix[3] * matrix[8] - matrix[5] * matrix[6])
                + matrix[2] * (matrix[3] * matrix[7] - matrix[4] * matrix[6]);
        if (!Double.isFinite(determinant) || Math.abs(determinant) <= 1e-12) return null;
        return new double[]{
                (matrix[4] * matrix[8] - matrix[5] * matrix[7]) / determinant,
                (matrix[2] * matrix[7] - matrix[1] * matrix[8]) / determinant,
                (matrix[1] * matrix[5] - matrix[2] * matrix[4]) / determinant,
                (matrix[5] * matrix[6] - matrix[3] * matrix[8]) / determinant,
                (matrix[0] * matrix[8] - matrix[2] * matrix[6]) / determinant,
                (matrix[2] * matrix[3] - matrix[0] * matrix[5]) / determinant,
                (matrix[3] * matrix[7] - matrix[4] * matrix[6]) / determinant,
                (matrix[1] * matrix[6] - matrix[0] * matrix[7]) / determinant,
                (matrix[0] * matrix[4] - matrix[1] * matrix[3]) / determinant,
        };
    }

    private static double[] interpolate(float[] low, float[] high, double weight) {
        double[] result = new double[9];
        for (int index = 0; index < result.length; index++) {
            result[index] = low[index] * weight + high[index] * (1.0 - weight);
        }
        return result;
    }

    private static double[] toDouble(float[] source) {
        double[] result = new double[source.length];
        for (int index = 0; index < source.length; index++) result[index] = source[index];
        return result;
    }

    private static boolean positive(float value) {
        return Float.isFinite(value) && value > 0.0f;
    }

    private static final class CalibrationProfile {
        final float[] lowMatrix;
        final double lowTemperature;
        final float[] highMatrix;
        final double highTemperature;

        CalibrationProfile(float[] lowMatrix, double lowTemperature,
                           float[] highMatrix, double highTemperature) {
            this.lowMatrix = lowMatrix;
            this.lowTemperature = lowTemperature;
            this.highMatrix = highMatrix;
            this.highTemperature = highTemperature;
        }
    }
}
