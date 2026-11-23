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

import moa.core.ObjectRepository;
import moa.tasks.TaskMonitor;
import moa.core.Utils;

import java.util.Set;
import java.util.TreeSet;
import moa.classifiers.core.AttributeSplitSuggestion;
import moa.classifiers.core.conditionaltests.NumericAttributeBinaryTest;
import moa.classifiers.core.splitcriteria.SplitCriterion;

import moa.core.AutoExpandVector;
import moa.core.DoubleVector;
import moa.options.AbstractOptionHandler;
import com.github.javacliparser.IntOption;

public class GaussianNumericAttributeClassObserver extends AbstractOptionHandler
        implements NumericAttributeClassObserver {

    private static final long serialVersionUID = 1L;

    protected DoubleVector minValueObservedPerClass = new DoubleVector();

    protected DoubleVector maxValueObservedPerClass = new DoubleVector();

    protected AutoExpandVector<GaussianEstimator> attValDistPerClass = new AutoExpandVector<GaussianEstimator>();

    public IntOption numBinsOption = new IntOption("numBins", 'n',
            "The number of bins.", 10, 1, Integer.MAX_VALUE);

    @Override
    public void observeAttributeClass(double attVal, int classVal, double weight) {
        if (Utils.isMissingValue(attVal)) {
        } else {
            GaussianEstimator valDist = this.attValDistPerClass.get(classVal);
            if (valDist == null) {
                valDist = new GaussianEstimator();
                this.attValDistPerClass.set(classVal, valDist);
                this.minValueObservedPerClass.setValue(classVal, attVal);
                this.maxValueObservedPerClass.setValue(classVal, attVal);
            } else {
                if (attVal < this.minValueObservedPerClass.getValue(classVal)) {
                    this.minValueObservedPerClass.setValue(classVal, attVal);
                }
                if (attVal > this.maxValueObservedPerClass.getValue(classVal)) {
                    this.maxValueObservedPerClass.setValue(classVal, attVal);
                }
            }
            valDist.addObservation(attVal, weight);
        }
    }

    @Override
    public double probabilityOfAttributeValueGivenClass(double attVal,
            int classVal) {
        GaussianEstimator obs = this.attValDistPerClass.get(classVal);
        return obs != null ? obs.probabilityDensity(attVal) : 0.0;
    }

    public double totalWeightOfClassObservations() {
        double total = 0.0;
        for (GaussianEstimator est : this.attValDistPerClass) {
            if (est != null) {
                total += est.getTotalWeightObserved();
            }
        }
        return total;
    }

    @Override
    public AttributeSplitSuggestion getBestEvaluatedSplitSuggestion(
            SplitCriterion criterion, double[] preSplitDist, int attIndex,
            boolean binaryOnly) {
        AttributeSplitSuggestion bestSuggestion = null;
        double[] suggestedSplitValues = getSplitPointSuggestions();
        for (double splitValue : suggestedSplitValues) {
            double[][] postSplitDists = getClassDistsResultingFromBinarySplit(splitValue);
            double merit = criterion.getMeritOfSplit(preSplitDist,
                    postSplitDists);
            if ((bestSuggestion == null) || (merit > bestSuggestion.merit)) {
                bestSuggestion = new AttributeSplitSuggestion(
                        new NumericAttributeBinaryTest(attIndex, splitValue,
                                true),
                        postSplitDists, merit);
            }
        }
        return bestSuggestion;
    }

    public double[] getSplitPointSuggestions() {
        Set<Double> suggestedSplitValues = new TreeSet<Double>();
        double minValue = Double.POSITIVE_INFINITY;
        double maxValue = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < this.attValDistPerClass.size(); i++) {
            GaussianEstimator estimator = this.attValDistPerClass.get(i);
            if (estimator != null) {
                if (this.minValueObservedPerClass.getValue(i) < minValue) {
                    minValue = this.minValueObservedPerClass.getValue(i);
                }
                if (this.maxValueObservedPerClass.getValue(i) > maxValue) {
                    maxValue = this.maxValueObservedPerClass.getValue(i);
                }
            }
        }
        if (minValue < Double.POSITIVE_INFINITY) {
            double range = maxValue - minValue;
            for (int i = 0; i < this.numBinsOption.getValue(); i++) {
                double splitValue = range / (this.numBinsOption.getValue() + 1.0) * (i + 1)
                        + minValue;
                if ((splitValue > minValue) && (splitValue < maxValue)) {
                    suggestedSplitValues.add(splitValue);
                }
            }
        }
        double[] suggestions = new double[suggestedSplitValues.size()];
        int i = 0;
        for (double suggestion : suggestedSplitValues) {
            suggestions[i++] = suggestion;
        }
        return suggestions;
    }

    // assume all values equal to splitValue go to lhs
    public double[][] getClassDistsResultingFromBinarySplit(double splitValue) {
        DoubleVector lhsDist = new DoubleVector();
        DoubleVector rhsDist = new DoubleVector();
        for (int i = 0; i < this.attValDistPerClass.size(); i++) {
            GaussianEstimator estimator = this.attValDistPerClass.get(i);
            if (estimator != null) {
                if (splitValue < this.minValueObservedPerClass.getValue(i)) {
                    rhsDist.addToValue(i, estimator.getTotalWeightObserved());
                } else if (splitValue >= this.maxValueObservedPerClass.getValue(i)) {
                    lhsDist.addToValue(i, estimator.getTotalWeightObserved());
                } else {
                    double[] weightDist = estimator.estimatedWeight_LessThan_EqualTo_GreaterThan_Value(splitValue);
                    lhsDist.addToValue(i, weightDist[0] + weightDist[1]);
                    rhsDist.addToValue(i, weightDist[2]);
                }
            }
        }
        return new double[][] { lhsDist.getArrayRef(), rhsDist.getArrayRef() };
    }

    @Override
    public void getDescription(StringBuilder sb, int indent) {
        // TODO Auto-generated method stub
    }

    @Override
    protected void prepareForUseImpl(TaskMonitor monitor, ObjectRepository repository) {
        // TODO Auto-generated method stub
    }

    @Override
    public void observeAttributeTarget(double attVal, double target) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public GaussianNumericAttributeClassObserver copy() {
        GaussianNumericAttributeClassObserver clone = (GaussianNumericAttributeClassObserver) super.copy();

        if (this.attValDistPerClass != null) {
            clone.attValDistPerClass = new AutoExpandVector<>();
            for (int i = 0; i < this.attValDistPerClass.size(); i++) {
                GaussianEstimator originalEstimator = this.attValDistPerClass.get(i);
                if (originalEstimator != null) {
                    clone.attValDistPerClass.set(i, originalEstimator.copy());
                } else {
                    clone.attValDistPerClass.set(i, null);
                }
            }
        } else {
            clone.attValDistPerClass = null;
        }

        if (this.minValueObservedPerClass != null) {
            clone.minValueObservedPerClass = (DoubleVector) this.minValueObservedPerClass.copy();
        } else {
            clone.minValueObservedPerClass = null;
        }

        if (this.maxValueObservedPerClass != null) {
            clone.maxValueObservedPerClass = (DoubleVector) this.maxValueObservedPerClass.copy();
        } else {
            clone.maxValueObservedPerClass = null;
        }

        return clone;
    }

    @Override
    public AttributeClassObserver merge(AttributeClassObserver that) {
        if (!(that instanceof GaussianNumericAttributeClassObserver)) {
            return this;
        }
        GaussianNumericAttributeClassObserver other = (GaussianNumericAttributeClassObserver) that;
        if (this.attValDistPerClass == null) {
            this.attValDistPerClass = new AutoExpandVector<GaussianEstimator>();
        }
        if (this.minValueObservedPerClass == null) {
            this.minValueObservedPerClass = new DoubleVector();
        }
        if (this.maxValueObservedPerClass == null) {
            this.maxValueObservedPerClass = new DoubleVector();
        }
        if (other.attValDistPerClass == null) {
            return this;
        }
        boolean otherHasMinMax = other.minValueObservedPerClass != null
                && other.maxValueObservedPerClass != null;
        for (int i = 0; i < other.attValDistPerClass.size(); i++) {
            GaussianEstimator otherEst = other.attValDistPerClass.get(i);
            if (otherEst == null) {
                continue; // other never saw class i on this attribute: nothing to take
            }
            boolean thisHadClass = this.attValDistPerClass.get(i) != null
                    && i < this.minValueObservedPerClass.numValues()
                    && i < this.maxValueObservedPerClass.numValues();
            if (otherHasMinMax
                    && i < other.minValueObservedPerClass.numValues()
                    && i < other.maxValueObservedPerClass.numValues()) {
                double otherMin = other.minValueObservedPerClass.getValue(i);
                double otherMax = other.maxValueObservedPerClass.getValue(i);
                if (thisHadClass) {
                    this.minValueObservedPerClass.setValue(i,
                            Math.min(this.minValueObservedPerClass.getValue(i), otherMin));
                    this.maxValueObservedPerClass.setValue(i,
                            Math.max(this.maxValueObservedPerClass.getValue(i), otherMax));
                } else {
                    this.minValueObservedPerClass.setValue(i, otherMin);
                    this.maxValueObservedPerClass.setValue(i, otherMax);
                }
            }
            // estimator, only the block carries new information
            if (otherEst.getBlockWeightSum() > 0.0) {
                GaussianEstimator thisEst = this.attValDistPerClass.get(i);
                if (thisEst == null) {
                    thisEst = new GaussianEstimator();
                    this.attValDistPerClass.set(i, thisEst);
                }
                thisEst.merge(otherEst);
            }
        }
        return this;
    }

    /**
     * Absorbs both tracks of 'that': per class the totals and the block of the
     * estimator, plus the observed min/max. Used when a subtree of the same tree
     * is folded back into one leaf, where nothing may be dropped. merge() above
     * stays block only, because that is all a peer tree may contribute.
     */
    public AttributeClassObserver mergeAll(AttributeClassObserver that) {
        if (!(that instanceof GaussianNumericAttributeClassObserver)) {
            return this;
        }
        GaussianNumericAttributeClassObserver other = (GaussianNumericAttributeClassObserver) that;
        if (this.attValDistPerClass == null) {
            this.attValDistPerClass = new AutoExpandVector<GaussianEstimator>();
        }
        if (this.minValueObservedPerClass == null) {
            this.minValueObservedPerClass = new DoubleVector();
        }
        if (this.maxValueObservedPerClass == null) {
            this.maxValueObservedPerClass = new DoubleVector();
        }
        if (other.attValDistPerClass == null) {
            return this;
        }
        boolean otherHasMinMax = other.minValueObservedPerClass != null
                && other.maxValueObservedPerClass != null;
        for (int i = 0; i < other.attValDistPerClass.size(); i++) {
            GaussianEstimator otherEst = other.attValDistPerClass.get(i);
            if (otherEst == null) {
                continue; // other never saw class i on this attribute: nothing to take
            }
            boolean thisHadClass = this.attValDistPerClass.get(i) != null
                    && i < this.minValueObservedPerClass.numValues()
                    && i < this.maxValueObservedPerClass.numValues();
            if (otherHasMinMax
                    && i < other.minValueObservedPerClass.numValues()
                    && i < other.maxValueObservedPerClass.numValues()) {
                double otherMin = other.minValueObservedPerClass.getValue(i);
                double otherMax = other.maxValueObservedPerClass.getValue(i);
                if (thisHadClass) {
                    this.minValueObservedPerClass.setValue(i,
                            Math.min(this.minValueObservedPerClass.getValue(i), otherMin));
                    this.maxValueObservedPerClass.setValue(i,
                            Math.max(this.maxValueObservedPerClass.getValue(i), otherMax));
                } else {
                    this.minValueObservedPerClass.setValue(i, otherMin);
                    this.maxValueObservedPerClass.setValue(i, otherMax);
                }
            }
            // estimator, totals and block both carry information here
            GaussianEstimator thisEst = this.attValDistPerClass.get(i);
            if (thisEst == null) {
                thisEst = new GaussianEstimator();
                this.attValDistPerClass.set(i, thisEst);
            }
            thisEst.mergeAll(otherEst);
        }
        return this;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        String indent = "  ";

        sb.append("GaussianObs {\n");

        // per-class estimators
        sb.append(indent)
                .append("estimators: [\n");
        for (int i = 0; i < attValDistPerClass.size(); i++) {
            GaussianEstimator est = attValDistPerClass.get(i);
            if (est != null) {
                sb.append(indent).append(indent)
                        .append("{ class=")
                        .append(i)
                        .append(", estimator=")
                        .append(est.toString())
                        .append(" },\n");
            }
        }
        int lastComma = sb.lastIndexOf(",");
        if (lastComma > 0) {
            sb.deleteCharAt(lastComma);
        }
        sb.append(indent).append("],\n");
        ;

        sb.append("}");
        return sb.toString();
    }
}