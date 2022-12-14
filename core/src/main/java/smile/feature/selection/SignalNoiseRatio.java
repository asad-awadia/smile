/*
 * Copyright (c) 2010-2021 Haifeng Li. All rights reserved.
 *
 * Smile is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Smile is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Smile.  If not, see <https://www.gnu.org/licenses/>.
 */

package smile.feature.selection;

import java.util.Arrays;
import java.util.stream.IntStream;
import smile.classification.ClassLabels;
import smile.data.DataFrame;
import smile.data.type.StructField;
import smile.data.type.StructType;
import smile.data.vector.BaseVector;
import smile.math.MathEx;

/**
 * The signal-to-noise (S2N) metric ratio is a univariate feature ranking metric,
 * which can be used as a feature selection criterion for binary classification
 * problems. S2N is defined as |&mu;<sub>1</sub> - &mu;<sub>2</sub>| / (&sigma;<sub>1</sub> + &sigma;<sub>2</sub>),
 * where &mu;<sub>1</sub> and &mu;<sub>2</sub> are the mean value of the variable
 * in classes 1 and 2, respectively, and &sigma;<sub>1</sub> and &sigma;<sub>2</sub>
 * are the standard deviations of the variable in classes 1 and 2, respectively.
 * Clearly, features with larger S2N ratios are better for classification.
 * 
 * <h2>References</h2>
 * <ol>
 * <li> M. Shipp, et al. Diffuse large B-cell lymphoma outcome prediction by gene-expression profiling and supervised machine learning. Nature Medicine, 2002.</li>
 * </ol>
 * 
 * @author Haifeng Li
 */
public class SignalNoiseRatio implements Comparable<SignalNoiseRatio> {
    /** The feature name. */
    public final String feature;
    /** Signal noise ratio. */
    public final double s2n;

    /**
     * Constructor.
     * @param feature The feature name.
     * @param s2n Signal noise ratio.
     */
    public SignalNoiseRatio(String feature, double s2n) {
        this.feature = feature;
        this.s2n = s2n;
    }

    @Override
    public int compareTo(SignalNoiseRatio other) {
        return Double.compare(s2n, other.s2n);
    }

    @Override
    public String toString() {
        return String.format("SignalNoiseRatio(%s, %.4f)", feature, s2n);
    }

    /**
     * Calculates the signal noise ratio of numeric variables.
     *
     * @param data the data frame of the explanatory and response variables.
     * @param clazz the column name of binary class labels.
     * @return the signal noise ratio.
     */
    public static SignalNoiseRatio[] fit(DataFrame data, String clazz) {
        BaseVector<?, ?, ?> y = data.column(clazz);
        ClassLabels codec = ClassLabels.fit(y);

        if (codec.k != 2) {
            throw new UnsupportedOperationException("Signal Noise Ratio is applicable only to binary classification");
        }

        int n = data.nrow();
        int n1 = 0;
        for (int yi : codec.y) {
            if (yi == 0) {
                n1++;
            }
        }

        int n2 = n - n1;
        double[] x1 = new double[n1];
        double[] x2 = new double[n2];

        StructType schema = data.schema();

        return IntStream.range(0, schema.length()).mapToObj(i -> {
            StructField field = schema.field(i);
            if (field.isNumeric()) {
                Arrays.fill(x1, 0.0);
                Arrays.fill(x2, 0.0);
                BaseVector<?, ?, ?> xi = data.column(i);

                for (int l = 0, j = 0, k = 0; l < n; l++) {
                    if (codec.y[l] == 0) {
                        x1[j++] = xi.getDouble(l);
                    } else {
                        x2[k++] = xi.getDouble(l);
                    }
                }

                double mu1 = MathEx.mean(x1);
                double mu2 = MathEx.mean(x2);
                double sd1 = MathEx.sd(x1);
                double sd2 = MathEx.sd(x2);

                double s2n = Math.abs(mu1 - mu2) / (sd1 + sd2);
                return new SignalNoiseRatio(field.name, s2n);
            } else {
                return null;
            }
        }).filter(s2n -> s2n != null && !s2n.feature.equals(clazz)).toArray(SignalNoiseRatio[]::new);
    }
}