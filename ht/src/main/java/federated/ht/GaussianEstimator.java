/*
 *    Copyright (C) 2026 Cyprus University of Technology, Limassol, Cyprus
 *    @author George Savva (gec.savva@edu.cut.ac.cy)
 *
 *    This program is free software; you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation; either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package federated.ht;

import moa.AbstractMOAObject;

/**
 * Gaussian incremental estimator that uses incremental method that is more
 * resistant to floating point imprecision.
 * for more info see Donald Knuth's "The Art of Computer Programming, Volume 2:
 * Seminumerical Algorithms", section 4.2.2.
 *
 * @author Richard Kirkby (rkirkby@cs.waikato.ac.nz)
 * @version $Revision: 7 $
 */

public class GaussianEstimator extends AbstractMOAObject {

    private static final long serialVersionUID = 1L;

    protected double weightSum;
    protected double blockWeightSum = 0.0;

    protected double mean;
    protected double blockMean = 0.0;

    protected double varianceSum;
    protected double blockVarianceSum = 0.0;

    public static final double NORMAL_CONSTANT = Math.sqrt(2 * Math.PI);

    public void addObservation(double value, double weight) {
        if (Double.isInfinite(value) || Double.isNaN(value)) {
            return;
        }
        if (this.blockWeightSum > 0.0) {
            this.blockWeightSum += weight;
            double lastMean = this.blockMean;
            this.blockMean += weight * (value - lastMean) / this.blockWeightSum;
            this.blockVarianceSum += weight * (value - lastMean) * (value -
                    this.blockMean);
        } else {
            this.blockMean = value;
            this.blockWeightSum = weight;
        }
        if (Double.isInfinite(value) || Double.isNaN(value)) {
            return;
        }
        if (this.weightSum > 0.0) {
            this.weightSum += weight;
            double lastMean = this.mean;
            this.mean += weight * (value - lastMean) / this.weightSum;
            this.varianceSum += weight * (value - lastMean) * (value - this.mean);
        } else {
            this.mean = value;
            this.weightSum = weight;
        }
    }

    public void addObservations(GaussianEstimator obs) {
        // Follows Variance Combination Rule in Section 2 of
        // Brian Babcock, Mayur Datar, Rajeev Motwani, Liadan O'Callaghan:
        // Maintaining variance and k-medians over data stream windows. PODS 2003:
        // 234-243
        //
        if ((this.weightSum > 0.0) && (obs.weightSum > 0.0)) {
            double oldMean = this.mean;
            this.mean = (this.mean * (this.weightSum / (this.weightSum + obs.weightSum)))
                    + (obs.mean * (obs.weightSum / (this.weightSum + obs.weightSum)));
            this.varianceSum += obs.varianceSum + (this.weightSum * obs.weightSum / (this.weightSum + obs.weightSum) *
                    Math.pow(obs.mean - oldMean, 2));
            this.weightSum += obs.weightSum;
        }
    }

    public GaussianEstimator merge(GaussianEstimator that) {
        if (this.weightSum == 0.0) {
            this.weightSum = that.blockWeightSum;
            this.mean = that.blockMean;
            this.varianceSum = that.blockVarianceSum;
        } else {
            double newWeightSum = this.weightSum + that.blockWeightSum;
            double newMean = (this.mean * this.weightSum + that.blockMean * that.blockWeightSum)
                    / newWeightSum;
            double meanDiff = this.mean - that.blockMean;
            double newVarianceSum = this.varianceSum
                    + that.blockVarianceSum
                    + (meanDiff * meanDiff) * this.weightSum * that.blockWeightSum / newWeightSum;
            this.weightSum = newWeightSum;
            this.mean = newMean;
            this.varianceSum = newVarianceSum;
        }

        if (this.blockWeightSum == 0.0) {
            this.blockWeightSum = that.blockWeightSum;
            this.blockMean = that.blockMean;
            this.blockVarianceSum = that.blockVarianceSum;
        } else {
            double newBlkWeightSum = this.blockWeightSum + that.blockWeightSum;
            double newBlkMean = (this.blockMean * this.blockWeightSum + that.blockMean * that.blockWeightSum)
                    / newBlkWeightSum;
            double blkMeanDiff = this.blockMean - that.blockMean;
            double newBlkVarianceSum = this.blockVarianceSum
                    + that.blockVarianceSum
                    + (blkMeanDiff * blkMeanDiff) * this.blockWeightSum * that.blockWeightSum / newBlkWeightSum;
            this.blockWeightSum = newBlkWeightSum;
            this.blockMean = newBlkMean;
            this.blockVarianceSum = newBlkVarianceSum;
        }

        return this;
    }

    /**
     * Absorbs both tracks of 'that': its totals into these totals, its block into
     * this block. Used when a subtree of the same tree is folded back into one
     * leaf, where the whole history has to survive. merge() above stays block
     * only, because that is all a peer tree is allowed to contribute.
     */
    public GaussianEstimator mergeAll(GaussianEstimator that) {
        if (that == null) {
            return this;
        }
        double[] tot = combine(this.weightSum, this.mean, this.varianceSum,
                that.weightSum, that.mean, that.varianceSum);
        this.weightSum = tot[0];
        this.mean = tot[1];
        this.varianceSum = tot[2];
        double[] blk = combine(this.blockWeightSum, this.blockMean, this.blockVarianceSum,
                that.blockWeightSum, that.blockMean, that.blockVarianceSum);
        this.blockWeightSum = blk[0];
        this.blockMean = blk[1];
        this.blockVarianceSum = blk[2];
        return this;
    }

    /**
     * The same variance combination rule merge() uses, written so that either
     * side may be empty. Returns {weightSum, mean, varianceSum}.
     */
    private static double[] combine(double wA, double mA, double vA,
            double wB, double mB, double vB) {
        if (wB <= 0.0) {
            return new double[] { wA, mA, vA };
        }
        if (wA <= 0.0) {
            return new double[] { wB, mB, vB };
        }
        double w = wA + wB;
        double meanDiff = mA - mB;
        return new double[] {
                w,
                (mA * wA + mB * wB) / w,
                vA + vB + (meanDiff * meanDiff) * wA * wB / w };
    }

    public double getBlockWeightSum() {
        return this.blockWeightSum;
    }

    @Override
    public GaussianEstimator copy() {
        GaussianEstimator clone = new GaussianEstimator();

        clone.weightSum = this.weightSum;
        clone.mean = this.mean;
        clone.varianceSum = this.varianceSum;

        clone.blockWeightSum = this.blockWeightSum;
        clone.blockMean = this.blockMean;
        clone.blockVarianceSum = this.blockVarianceSum;

        return clone;
    }

    public double getTotalWeightObserved() {
        return this.weightSum;
    }

    public double getMean() {
        return this.mean;
    }

    public double getStdDev() {
        return Math.sqrt(getVariance());
    }

    public double getVariance() {
        return this.weightSum > 1.0 ? this.varianceSum / (this.weightSum - 1.0)
                : 0.0;
    }

    public double probabilityDensity(double value) {
        if (this.weightSum > 0.0) {
            double stdDev = getStdDev();
            if (stdDev > 0.0) {
                double diff = value - getMean();
                return (1.0 / (NORMAL_CONSTANT * stdDev))
                        * Math.exp(-(diff * diff / (2.0 * stdDev * stdDev)));
            }
            return value == getMean() ? 1.0 : 0.0;
        }
        return 0.0;
    }

    public double[] estimatedWeight_LessThan_EqualTo_GreaterThan_Value(
            double value) {
        double equalToWeight = probabilityDensity(value) * this.weightSum;
        double stdDev = getStdDev();
        double lessThanWeight = stdDev > 0.0 ? moa.core.Statistics.normalProbability((value - getMean()) / stdDev)
                * this.weightSum - equalToWeight
                : (value < getMean() ? this.weightSum - equalToWeight : 0.0);
        double greaterThanWeight = this.weightSum - equalToWeight
                - lessThanWeight;
        if (greaterThanWeight < 0.0) {
            greaterThanWeight = 0.0;
        }
        return new double[] { lessThanWeight, equalToWeight, greaterThanWeight };
    }

    @Override
    public String toString() {
        return String.format(
                "GaussianEstimator["
                        + "weightSum=%.3f, mean=%.3f, varianceSum=%.3f, stdDev=%.3f; "
                        + "blockWeightSum=%.3f, blockMean=%.3f, blockVarianceSum=%.3f"
                        + "]",
                this.weightSum,
                this.mean,
                this.varianceSum,
                getStdDev(),
                this.blockWeightSum,
                this.blockMean,
                this.blockVarianceSum);
    }

    @Override
    public void getDescription(StringBuilder sb, int indent) {
        // TODO Auto-generated method stub
    }
}