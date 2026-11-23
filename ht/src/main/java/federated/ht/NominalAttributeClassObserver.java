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

import moa.classifiers.core.AttributeSplitSuggestion;
import moa.classifiers.core.attributeclassobservers.DiscreteAttributeClassObserver;
import moa.classifiers.core.conditionaltests.NominalAttributeBinaryTest;
import moa.classifiers.core.conditionaltests.NominalAttributeMultiwayTest;
import moa.classifiers.core.splitcriteria.SplitCriterion;
import moa.core.ObjectRepository;
import moa.tasks.TaskMonitor;
import moa.core.Utils;

import moa.core.AutoExpandVector;
import moa.core.DoubleVector;
import moa.options.AbstractOptionHandler;

public class NominalAttributeClassObserver extends AbstractOptionHandler implements
        DiscreteAttributeClassObserver, AttributeClassObserver {

    private static final long serialVersionUID = 1L;

    protected double totalWeightObserved = 0.0;

    protected double blockWeightObserved = 0.0;

    protected double missingWeightObserved = 0.0;

    public AutoExpandVector<DoubleVector> attValDistPerClass = new AutoExpandVector<DoubleVector>();

    public AutoExpandVector<DoubleVector> blockAttValDistPerClass = new AutoExpandVector<DoubleVector>();

    @Override
    public void observeAttributeClass(double attVal, int classVal, double weight) {
        if (Utils.isMissingValue(attVal)) {
            this.missingWeightObserved += weight;
        } else {
            int attValInt = (int) attVal;
            // total
            DoubleVector totalDist = this.attValDistPerClass.get(classVal);
            if (totalDist == null) {
                totalDist = new DoubleVector();
                this.attValDistPerClass.set(classVal, totalDist);
            }
            totalDist.addToValue(attValInt, weight);
            // block
            DoubleVector blockDist = this.blockAttValDistPerClass.get(classVal);
            if (blockDist == null) {
                blockDist = new DoubleVector();
                this.blockAttValDistPerClass.set(classVal, blockDist);
            }
            blockDist.addToValue(attValInt, weight);
        }
        this.totalWeightObserved += weight;
        this.blockWeightObserved += weight;
    }

    @Override
    public double probabilityOfAttributeValueGivenClass(double attVal,
            int classVal) {
        DoubleVector obs = this.attValDistPerClass.get(classVal);
        return obs != null ? (obs.getValue((int) attVal) + 1.0)
                / (obs.sumOfValues() + obs.numValues()) : 0.0;
    }

    public double totalWeightOfClassObservations() {
        return this.totalWeightObserved;
    }

    public double weightOfObservedMissingValues() {
        return this.missingWeightObserved;
    }

    @Override
    public AttributeSplitSuggestion getBestEvaluatedSplitSuggestion(
            SplitCriterion criterion, double[] preSplitDist, int attIndex,
            boolean binaryOnly) {
        AttributeSplitSuggestion bestSuggestion = null;
        int maxAttValsObserved = getMaxAttValsObserved();
        if (!binaryOnly) {
            double[][] postSplitDists = getClassDistsResultingFromMultiwaySplit(maxAttValsObserved);
            double merit = criterion.getMeritOfSplit(preSplitDist,
                    postSplitDists);
            bestSuggestion = new AttributeSplitSuggestion(
                    new NominalAttributeMultiwayTest(attIndex), postSplitDists,
                    merit);
        }
        for (int valIndex = 0; valIndex < maxAttValsObserved; valIndex++) {
            double[][] postSplitDists = getClassDistsResultingFromBinarySplit(valIndex);
            double merit = criterion.getMeritOfSplit(preSplitDist,
                    postSplitDists);
            if ((bestSuggestion == null) || (merit > bestSuggestion.merit)) {
                bestSuggestion = new AttributeSplitSuggestion(
                        new NominalAttributeBinaryTest(attIndex, valIndex),
                        postSplitDists, merit);
            }
        }
        return bestSuggestion;
    }

    public int getMaxAttValsObserved() {
        int maxAttValsObserved = 0;
        for (DoubleVector attValDist : this.attValDistPerClass) {
            if ((attValDist != null)
                    && (attValDist.numValues() > maxAttValsObserved)) {
                maxAttValsObserved = attValDist.numValues();
            }
        }
        return maxAttValsObserved;
    }

    public double[][] getClassDistsResultingFromMultiwaySplit(
            int maxAttValsObserved) {
        DoubleVector[] resultingDists = new DoubleVector[maxAttValsObserved];
        for (int i = 0; i < resultingDists.length; i++) {
            resultingDists[i] = new DoubleVector();
        }
        for (int i = 0; i < this.attValDistPerClass.size(); i++) {
            DoubleVector attValDist = this.attValDistPerClass.get(i);
            if (attValDist != null) {
                for (int j = 0; j < attValDist.numValues(); j++) {
                    resultingDists[j].addToValue(i, attValDist.getValue(j));
                }
            }
        }
        double[][] distributions = new double[maxAttValsObserved][];
        for (int i = 0; i < distributions.length; i++) {
            distributions[i] = resultingDists[i].getArrayRef();
        }
        return distributions;
    }

    public double[][] getClassDistsResultingFromBinarySplit(int valIndex) {
        DoubleVector equalsDist = new DoubleVector();
        DoubleVector notEqualDist = new DoubleVector();
        for (int i = 0; i < this.attValDistPerClass.size(); i++) {
            DoubleVector attValDist = this.attValDistPerClass.get(i);
            if (attValDist != null) {
                for (int j = 0; j < attValDist.numValues(); j++) {
                    if (j == valIndex) {
                        equalsDist.addToValue(i, attValDist.getValue(j));
                    } else {
                        notEqualDist.addToValue(i, attValDist.getValue(j));
                    }
                }
            }
        }
        return new double[][] { equalsDist.getArrayRef(),
                notEqualDist.getArrayRef() };
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
    public AttributeClassObserver merge(AttributeClassObserver that) {
        if (!(that instanceof NominalAttributeClassObserver)) {
            return this;
        }
        NominalAttributeClassObserver other = (NominalAttributeClassObserver) that;

        int maxC = (other.blockAttValDistPerClass == null) ? 0 : other.blockAttValDistPerClass.size();
        double otherBlockNonMissing = 0.0;

        for (int c = 0; c < maxC; c++) {
            DoubleVector otherBlk = other.blockAttValDistPerClass.get(c);
            if (otherBlk == null)
                continue;

            DoubleVector thisTot = this.attValDistPerClass.get(c);
            if (thisTot == null) {
                thisTot = new DoubleVector();
                this.attValDistPerClass.set(c, thisTot);
            }

            DoubleVector thisBlk = this.blockAttValDistPerClass.get(c);
            if (thisBlk == null) {
                thisBlk = new DoubleVector();
                this.blockAttValDistPerClass.set(c, thisBlk);
            }

            int nVals = otherBlk.numValues();
            for (int v = 0; v < nVals; v++) {
                double w = otherBlk.getValue(v);
                if (w != 0.0) {
                    thisTot.addToValue(v, w);
                    thisBlk.addToValue(v, w);
                    otherBlockNonMissing += w;
                }
            }
        }

        double otherBlockMissing = Math.max(0.0, other.blockWeightObserved - otherBlockNonMissing);
        this.totalWeightObserved += other.blockWeightObserved;
        this.blockWeightObserved += other.blockWeightObserved;
        this.missingWeightObserved += otherBlockMissing;

        return this;
    }

    /**
     * Absorbs both tracks of 'that': the per class value counts and their block,
     * the observed weights and the missing value weight. Used when a subtree of
     * the same tree is folded back into one leaf, where nothing may be dropped.
     * merge() above stays block only, because that is all a peer may contribute.
     */
    public AttributeClassObserver mergeAll(AttributeClassObserver that) {
        if (!(that instanceof NominalAttributeClassObserver)) {
            return this;
        }
        NominalAttributeClassObserver other = (NominalAttributeClassObserver) that;
        if (this.attValDistPerClass == null) {
            this.attValDistPerClass = new AutoExpandVector<DoubleVector>();
        }
        if (this.blockAttValDistPerClass == null) {
            this.blockAttValDistPerClass = new AutoExpandVector<DoubleVector>();
        }
        addInto(this.attValDistPerClass, other.attValDistPerClass);
        addInto(this.blockAttValDistPerClass, other.blockAttValDistPerClass);
        this.totalWeightObserved += other.totalWeightObserved;
        this.blockWeightObserved += other.blockWeightObserved;
        this.missingWeightObserved += other.missingWeightObserved;
        return this;
    }

    /** Adds every per class value count of 'source' into 'target'. */
    private static void addInto(AutoExpandVector<DoubleVector> target,
            AutoExpandVector<DoubleVector> source) {
        if (target == null || source == null) {
            return;
        }
        for (int c = 0; c < source.size(); c++) {
            DoubleVector src = source.get(c);
            if (src == null) {
                continue;
            }
            DoubleVector dst = target.get(c);
            if (dst == null) {
                dst = new DoubleVector();
                target.set(c, dst);
            }
            for (int v = 0; v < src.numValues(); v++) {
                double w = src.getValue(v);
                if (w != 0.0) {
                    dst.addToValue(v, w);
                }
            }
        }
    }

    public void resetBlockStats() {
        this.blockWeightObserved = 0.0;
        this.blockAttValDistPerClass = new AutoExpandVector<>();
    }
}