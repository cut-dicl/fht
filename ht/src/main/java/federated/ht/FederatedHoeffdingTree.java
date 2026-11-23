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

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.github.javacliparser.FlagOption;
import com.github.javacliparser.FloatOption;
import com.github.javacliparser.IntOption;
import com.github.javacliparser.MultiChoiceOption;
import com.yahoo.labs.samoa.instances.Instance;

import moa.AbstractMOAObject;
import moa.capabilities.Capability;
import moa.capabilities.ImmutableCapabilities;
import moa.classifiers.AbstractClassifier;
import moa.classifiers.MultiClassClassifier;
import moa.classifiers.core.AttributeSplitSuggestion;
import moa.classifiers.core.attributeclassobservers.DiscreteAttributeClassObserver;
import moa.classifiers.core.conditionaltests.InstanceConditionalTest;
import moa.classifiers.core.splitcriteria.SplitCriterion;
import moa.core.AutoExpandVector;
import moa.core.DoubleVector;
import moa.core.Measurement;
import moa.core.SizeOf;
import moa.core.StringUtils;
import moa.core.Utils;
import moa.options.ClassOption;

public class FederatedHoeffdingTree extends AbstractClassifier implements MultiClassClassifier {

    private static final long serialVersionUID = 1L;

    @Override
    public String getPurposeString() {
        return "Hoeffding Tree or VFDT.";
    }

    public IntOption maxByteSizeOption = new IntOption("maxByteSize", 'm',
            "Maximum memory consumed by the tree.", 33554432, 0,
            Integer.MAX_VALUE);

    /*
     * public MultiChoiceOption numericEstimatorOption = new MultiChoiceOption(
     * "numericEstimator", 'n', "Numeric estimator to use.", new String[]{
     * "GAUSS10", "GAUSS100", "GK10", "GK100", "GK1000", "VFML10", "VFML100",
     * "VFML1000", "BINTREE"}, new String[]{ "Gaussian approximation evaluating
     * 10 splitpoints", "Gaussian approximation evaluating 100 splitpoints",
     * "Greenwald-Khanna quantile summary with 10 tuples", "Greenwald-Khanna
     * quantile summary with 100 tuples", "Greenwald-Khanna quantile summary
     * with 1000 tuples", "VFML method with 10 bins", "VFML method with 100
     * bins", "VFML method with 1000 bins", "Exhaustive binary tree"}, 0);
     */
    public ClassOption numericEstimatorOption = new ClassOption("numericEstimator",
            'n', "Numeric estimator to use.", NumericAttributeClassObserver.class,
            "GaussianNumericAttributeClassObserver");

    public ClassOption nominalEstimatorOption = new ClassOption("nominalEstimator",
            'd', "Nominal estimator to use.", DiscreteAttributeClassObserver.class,
            "federated.ht.NominalAttributeClassObserver");

    public IntOption memoryEstimatePeriodOption = new IntOption(
            "memoryEstimatePeriod", 'e',
            "How many instances between memory consumption checks.", 1000000,
            0, Integer.MAX_VALUE);

    public IntOption gracePeriodOption = new IntOption(
            "gracePeriod",
            'g',
            "The number of instances a leaf should observe between split attempts.",
            200, 0, Integer.MAX_VALUE);

    public ClassOption splitCriterionOption = new ClassOption("splitCriterion",
            's', "Split criterion to use.", SplitCriterion.class,
            "InfoGainSplitCriterion");

    public FloatOption splitConfidenceOption = new FloatOption(
            "splitConfidence",
            'c',
            "The allowable error in split decision, values closer to 0 will take longer to decide.",
            0.0000001, 0.0, 1.0);

    public FloatOption tieThresholdOption = new FloatOption("tieThreshold",
            't', "Threshold below which a split will be forced to break ties.",
            0.05, 0.0, 1.0);

    public FlagOption binarySplitsOption = new FlagOption("binarySplits", 'b',
            "Only allow binary splits.");

    public FlagOption stopMemManagementOption = new FlagOption(
            "stopMemManagement", 'z',
            "Stop growing as soon as memory limit is hit.");

    public FlagOption removePoorAttsOption = new FlagOption("removePoorAtts",
            'r', "Disable poor attributes.");

    public FlagOption noPrePruneOption = new FlagOption("noPrePrune", 'p',
            "Disable pre-pruning.");

    public MultiChoiceOption splitMatchPolicyOption = new MultiChoiceOption(
            "splitMatchPolicy",
            'x',
            "How equal two split tests must be before a merge may pour branch i of one into branch i of the other. Anything less equal collapses the region back to a single leaf and re-derives the split from the pooled statistics.",
            new String[] { "ATTRIBUTE", "EXACT" },
            new String[] {
                    "Same attribute, same kind of test and same number of branches",
                    "As ATTRIBUTE, and the same condition on every branch (same threshold or value)" },
            1);

    public static class FoundNode {

        public Node node;

        public SplitNode parent;

        public int parentBranch;

        public FoundNode(Node node, SplitNode parent, int parentBranch) {
            this.node = node;
            this.parent = parent;
            this.parentBranch = parentBranch;
        }
    }

    public static abstract class Node extends AbstractMOAObject {

        private static final long serialVersionUID = 1L;

        protected DoubleVector observedClassDistribution;
        protected DoubleVector blockObservedClassDistribution;

        public Node(double[] classObservations) {
            this.observedClassDistribution = new DoubleVector(classObservations);
            this.blockObservedClassDistribution = new DoubleVector(new double[classObservations.length]);

        }

        public long calcByteSize() {
            return SizeOf.sizeOf(this) + SizeOf.fullSizeOf(this.observedClassDistribution)
                    + SizeOf.fullSizeOf(this.blockObservedClassDistribution);
        }

        public long calcByteSizeIncludingSubtree() {
            return calcByteSize();
        }

        public boolean isLeaf() {
            return true;
        }

        public FoundNode filterInstanceToLeaf(Instance inst, SplitNode parent,
                int parentBranch) {
            return new FoundNode(this, parent, parentBranch);
        }

        public double[] getObservedClassDistribution() {
            return this.observedClassDistribution.getArrayCopy();
        }

        public double[] getObservedClassDistributionAtLeavesReachableThroughThisNode() {
            return this.observedClassDistribution.getArrayCopy();
        }

        /**
         * Class distribution at the leaves below this node as it was at the start of
         * the current round: totals minus the block accumulated since the last reset.
         */
        public double[] getPreRoundClassDistributionAtLeaves() {
            double[] tot = this.observedClassDistribution.getArrayCopy();
            for (int c = 0; c < tot.length; c++) {
                double b = (c < this.blockObservedClassDistribution.numValues())
                        ? this.blockObservedClassDistribution.getValue(c)
                        : 0.0;
                tot[c] = Math.max(0.0, tot[c] - b);
            }
            return tot;
        }

        public double[] getClassVotes(Instance inst, FederatedHoeffdingTree ht) {
            return this.observedClassDistribution.getArrayCopy();
        }

        public boolean observedClassDistributionIsPure() {
            return this.observedClassDistribution.numNonZeroEntries() < 2
                    && this.blockObservedClassDistribution.numNonZeroEntries() < 2;

        }

        public void describeSubtree(FederatedHoeffdingTree ht, StringBuilder out,
                int indent) {
            StringUtils.appendIndented(out, indent, "Leaf ");
            out.append(ht.getClassNameString());
            out.append(" = ");
            out.append(ht.getClassLabelString(this.observedClassDistribution.maxIndex()));
            out.append(" weights: ");
            this.observedClassDistribution.getSingleLineDescription(out,
                    ht.treeRoot.observedClassDistribution.numValues());
            StringUtils.appendNewline(out);
        }

        public int subtreeDepth() {
            return 0;
        }

        public double calculatePromise() {
            double totalSeen = this.observedClassDistribution.sumOfValues();
            return totalSeen > 0.0
                    ? (totalSeen - this.observedClassDistribution.getValue(this.observedClassDistribution.maxIndex()))
                    : 0.0;
        }

        @Override
        public void getDescription(StringBuilder sb, int indent) {
            describeSubtree(null, sb, indent);
        }

        public abstract void resetBlockStats();

        public abstract Node merge(Node that);

        public Node merge(Node that, FederatedHoeffdingTree ht) {
            return merge(that);
        }

        public abstract void printAttributeObservers(String indent);

        protected int depth = 0;

        /** Return depth of this node in the tree */
        public int depth() {
            return this.depth;
        }

        public void setDepth(int depth) {
            this.depth = depth;
            if (this instanceof SplitNode) {
                for (Node child : ((SplitNode) this).getChildren()) {
                    if (child != null) {
                        child.setDepth(depth + 1);
                    }
                }
            }
        }
    }

    public static class SplitNode extends Node {

        private static final long serialVersionUID = 1L;

        protected InstanceConditionalTest splitTest;

        protected AutoExpandVector<Node> children; // = new AutoExpandVector<Node>();
        protected AutoExpandVector<AttributeClassObserver> storedAttributeObservers;

        @Override
        public long calcByteSize() {
            return super.calcByteSize()
                    + SizeOf.sizeOf(this.children) + SizeOf.fullSizeOf(this.splitTest);
        }

        @Override
        public void printAttributeObservers(String indent) {
            for (int i = 0; i < this.children.size(); i++) {
                Node child = this.children.get(i);
                if (child != null) {
                    logger.info(indent + "Branch[" + i + "]:");
                    child.printAttributeObservers(indent + "  ");
                }
            }
        }

        @Override
        public void resetBlockStats() {
            int numClasses = this.observedClassDistribution.numValues();
            this.blockObservedClassDistribution = new DoubleVector(new double[numClasses]);
            // The pre-split observers stored on this node carry block data too
            resetObserverBlockStats(this.storedAttributeObservers);
            // Recursively clear every child
            for (Node child : this.children) {
                if (child != null) {
                    child.resetBlockStats();
                }
            }
        }

        @Override
        public long calcByteSizeIncludingSubtree() {
            long byteSize = calcByteSize();
            for (Node child : this.children) {
                if (child != null) {
                    byteSize += child.calcByteSizeIncludingSubtree();
                }
            }
            return byteSize;
        }

        @Override
        public double[] getObservedClassDistributionAtLeavesReachableThroughThisNode() {
            // Start a new DoubleVector with 0 in all positions.
            DoubleVector sumObservedClassDistributionAtLeaves = new DoubleVector(
                    new double[this.getObservedClassDistribution().length]);

            for (Node childNode : this.children) {
                if (childNode != null) {
                    double[] childDist = childNode.getObservedClassDistributionAtLeavesReachableThroughThisNode();
                    sumObservedClassDistributionAtLeaves.addValues(childDist);
                }
            }
            return sumObservedClassDistributionAtLeaves.getArrayCopy();
        }

        @Override
        public double[] getPreRoundClassDistributionAtLeaves() {
            DoubleVector sum = new DoubleVector(new double[this.getObservedClassDistribution().length]);
            for (Node childNode : this.children) {
                if (childNode != null) {
                    sum.addValues(childNode.getPreRoundClassDistributionAtLeaves());
                }
            }
            return sum.getArrayCopy();
        }

        public AutoExpandVector<Node> getChildren() {
            return children;
        }

        public InstanceConditionalTest getSplitTest() {
            return splitTest;
        }

        public SplitNode(InstanceConditionalTest splitTest,
                double[] classObservations, int size) {
            super(classObservations);
            this.splitTest = splitTest;
            this.children = new AutoExpandVector<Node>(size);
            this.storedAttributeObservers = null;
        }

        public SplitNode(InstanceConditionalTest splitTest,
                double[] classObservations) {
            super(classObservations);
            this.splitTest = splitTest;
            this.children = new AutoExpandVector<Node>();
            this.storedAttributeObservers = null;
        }

        public void setStoredAttributeObservers(AutoExpandVector<AttributeClassObserver> obs) {
            this.storedAttributeObservers = obs;
        }

        public int numChildren() {
            return this.children.size();
        }

        public void setChild(int index, Node child) {
            if ((this.splitTest.maxBranches() >= 0)
                    && (index >= this.splitTest.maxBranches())) {
                throw new IndexOutOfBoundsException();
            }
            this.children.set(index, child);
            if (child != null) {
                child.setDepth(this.depth() + 1);
            }
        }

        public Node getChild(int index) {
            return this.children.get(index);
        }

        public int instanceChildIndex(Instance inst) {
            return this.splitTest.branchForInstance(inst);
        }

        @Override
        public boolean isLeaf() {
            return false;
        }

        @Override
        public FoundNode filterInstanceToLeaf(Instance inst, SplitNode parent,
                int parentBranch) {
            int childIndex = instanceChildIndex(inst);
            if (childIndex >= 0) {
                Node child = getChild(childIndex);
                if (child != null) {
                    return child.filterInstanceToLeaf(inst, this, childIndex);
                }
                return new FoundNode(null, this, childIndex);
            }
            return new FoundNode(this, parent, parentBranch);
        }

        @Override
        public void describeSubtree(FederatedHoeffdingTree ht, StringBuilder out,
                int indent) {
            for (int branch = 0; branch < numChildren(); branch++) {
                Node child = getChild(branch);
                if (child != null) {
                    StringUtils.appendIndented(out, indent, "if ");
                    out.append(this.splitTest.describeConditionForBranch(branch,
                            ht.getModelContext()));
                    out.append(": ");
                    StringUtils.appendNewline(out);
                    child.describeSubtree(ht, out, indent + 2);
                }
            }
        }

        @Override
        public int subtreeDepth() {
            int maxChildDepth = 0;
            for (Node child : this.children) {
                if (child != null) {
                    int depth = child.subtreeDepth();
                    if (depth > maxChildDepth) {
                        maxChildDepth = depth;
                    }
                }
            }
            return maxChildDepth + 1;
        }

        @Override
        public Node merge(Node that) {
            return merge(that, null);
        }

        @Override
        public Node merge(Node that, FederatedHoeffdingTree ht) {
            if (that instanceof SplitNode) {
                SplitNode other = (SplitNode) that;
                // Merging by branch index is only valid if both tests split the region the
                // same way; otherwise collapse the region to one leaf and re-derive the split.
                if (ht != null && !ht.splitTestsCompatible(this.splitTest, other.splitTest)) {
                    return collapseWith(other, ht);
                }
                for (int i = 0; i < other.children.size(); i++) {
                    Node thisChild = this.children.get(i);
                    Node otherChild = other.children.get(i);
                    if (thisChild != null && otherChild != null) {
                        this.children.set(i, thisChild.merge(otherChild, ht));
                    } else if (thisChild == null && otherChild != null) {
                        this.children.set(i, otherChild);
                    }
                    // else: leave thisChild as is
                }
                // The peer's split node keeps the observers it had before it split; they
                // may hold block data from this round, so merge that block into ours.
                if (other.storedAttributeObservers != null) {
                    if (this.storedAttributeObservers == null) {
                        this.storedAttributeObservers = new AutoExpandVector<AttributeClassObserver>();
                    }
                    mergeObserverBlocksInto(this.storedAttributeObservers, other.storedAttributeObservers);
                }
                return this;
            }
            if (that instanceof ActiveLearningNode) {
                distributeLeafBlock((ActiveLearningNode) that, ht);
                return this;
            }
            if (that instanceof InactiveLearningNode) {
                // An inactive leaf has a class block but no observers: wrap it in an active
                // leaf so its block is spread over the children like any other leaf.
                ActiveLearningNode carrier = new ActiveLearningNode(that.getObservedClassDistribution());
                carrier.blockObservedClassDistribution = new DoubleVector(
                        that.blockObservedClassDistribution.getArrayCopy());
                distributeLeafBlock(carrier, ht);
                return this;
            }
            return this;
        }

        /**
         * Split x split with incompatible tests: the region is collapsed into one leaf,
         * which splitPendingLeaves() re-splits from the combined statistics.
         * Our subtree contributes its totals and its block; the peer's contributes its
         * block only, since its totals are already in our tree from earlier rounds.
         * This is the same rule as every other merge path.
         */
        protected Node collapseWith(SplitNode other, FederatedHoeffdingTree ht) {
            int numClasses = this.observedClassDistribution.numValues();
            if (ht.getModelContext() != null
                    && ht.getModelContext().numClasses() > numClasses) {
                numClasses = ht.getModelContext().numClasses();
            }
            ActiveLearningNode collapsed = (ActiveLearningNode) ht.newLearningNode(
                    new double[numClasses]);
            collapsed.depth = this.depth;
            collapsed.absorbOwnSubtree(this); // our side: totals and block
            collapsed.merge(other); // their side: block only, via collapseSplitInto
            collapsed.markPendingRebuild();
            return collapsed;
        }

        /**
         * Split <- leaf. The peer has not split this node, so its block is spread over
         * all children in proportion to the pre-round class mass below each one.
         * Sub-split children recurse through their own merge(), so the block reaches
         * the deepest leaves.
         */
        protected void distributeLeafBlock(ActiveLearningNode leaf) {
            distributeLeafBlock(leaf, null);
        }

        protected void distributeLeafBlock(ActiveLearningNode leaf, FederatedHoeffdingTree ht) {
            int numChildren = this.children.size();
            double[] rawBlock = leaf.blockObservedClassDistribution.getArrayCopy();
            int numClasses = rawBlock.length;
            double totalRawBlock = 0.0;
            for (double v : rawBlock) {
                totalRawBlock += v;
            }
            if (totalRawBlock <= 0.0) {
                return;
            }
            // Per child: the pre-round class mass below it (totals minus the current
            // block, summed over its leaves). Then the same over all children.
            double[][] childClassObserved = new double[numChildren][];
            double[] childTotalObserved = new double[numChildren];
            double[] totalClassObserved = new double[numClasses];
            double totalObserved = 0.0;
            int numReceivingChildren = 0;
            for (int i = 0; i < numChildren; i++) {
                Node child = this.children.get(i);
                if (child == null) {
                    continue;
                }
                childClassObserved[i] = child.getPreRoundClassDistributionAtLeaves();
                numReceivingChildren++;
                for (int c = 0; c < childClassObserved[i].length; c++) {
                    childTotalObserved[i] += childClassObserved[i][c];
                    if (c < numClasses) {
                        totalClassObserved[c] += childClassObserved[i][c];
                    }
                }
                totalObserved += childTotalObserved[i];
            }
            if (numReceivingChildren == 0) {
                return;
            }

            for (int i = 0; i < numChildren; i++) {
                if (childClassObserved[i] == null) {
                    continue;
                }
                double[] incomingBlock = new double[numClasses];
                double sumIncoming = 0.0;
                for (int c = 0; c < numClasses; c++) {
                    if (rawBlock[c] == 0.0) {
                        continue;
                    }
                    double obs = (c < childClassObserved[i].length) ? childClassObserved[i][c] : 0.0;
                    double prop;
                    if (totalClassObserved[c] > 0.0) {
                        prop = obs / totalClassObserved[c];
                    } else if (totalObserved > 0.0) {
                        prop = childTotalObserved[i] / totalObserved; // class unseen below: follow the child's overall
                                                                      // share
                    } else {
                        prop = 1.0 / numReceivingChildren; // nothing observed below: uniform
                    }
                    incomingBlock[c] = rawBlock[c] * prop;
                    sumIncoming += incomingBlock[c];
                }
                if (sumIncoming <= 0.0) {
                    continue;
                }
                ActiveLearningNode leafCopy = (ActiveLearningNode) leaf.copy();
                scaleBlockStats(leafCopy, rawBlock, incomingBlock, sumIncoming / totalRawBlock);
                this.children.set(i, this.children.get(i).merge(leafCopy, ht));
            }
        }

        /**
         * Rescales the block of 'leafCopy' (a copy of the incoming leaf) so it carries
         * exactly 'incomingBlock' per class; totals are untouched, since receivers read
         * only the block. 'frac' is this child's share of the whole block.
         */
        protected static void scaleBlockStats(ActiveLearningNode leafCopy, double[] rawBlock,
                double[] incomingBlock, double frac) {
            int numClasses = rawBlock.length;
            double sumIncoming = 0.0;
            for (double v : incomingBlock) {
                sumIncoming += v;
            }
            leafCopy.blockObservedClassDistribution = new DoubleVector(incomingBlock);
            leafCopy.blockAddonWeight = sumIncoming;
            if (leafCopy instanceof LearningNodeNBAdaptive) {
                LearningNodeNBAdaptive lnna = (LearningNodeNBAdaptive) leafCopy;
                lnna.mcBlockCorrectWeight *= frac;
                lnna.nbBlockCorrectWeight *= frac;
            }
            if (leafCopy.attributeObservers == null) {
                return;
            }
            for (AttributeClassObserver obs : leafCopy.attributeObservers) {
                if (obs instanceof GaussianNumericAttributeClassObserver) {
                    GaussianNumericAttributeClassObserver gObs = (GaussianNumericAttributeClassObserver) obs;
                    if (gObs.attValDistPerClass == null) {
                        continue;
                    }
                    for (int c = 0; c < gObs.attValDistPerClass.size(); c++) {
                        GaussianEstimator est = gObs.attValDistPerClass.get(c);
                        if (est == null) {
                            continue;
                        }
                        double rb = (c < numClasses) ? rawBlock[c] : 0.0;
                        double ib = (c < numClasses) ? incomingBlock[c] : 0.0;
                        double p = (rb > 0.0) ? (ib / rb) : 0.0;
                        est.blockWeightSum *= p;
                        est.blockVarianceSum *= p;
                    }
                } else if (obs instanceof NominalAttributeClassObserver) {
                    NominalAttributeClassObserver nomObs = (NominalAttributeClassObserver) obs;
                    // block-missing = block total - block non-missing, measured BEFORE scaling
                    double blockNonMissing = 0.0;
                    for (DoubleVector vec : nomObs.blockAttValDistPerClass) {
                        if (vec != null) {
                            blockNonMissing += vec.sumOfValues();
                        }
                    }
                    double blockMissing = Math.max(0.0, nomObs.blockWeightObserved - blockNonMissing);
                    double newBlockWeight = 0.0;
                    for (int c = 0; c < nomObs.blockAttValDistPerClass.size(); c++) {
                        DoubleVector vec = nomObs.blockAttValDistPerClass.get(c);
                        if (vec == null) {
                            continue;
                        }
                        double rb = (c < numClasses) ? rawBlock[c] : 0.0;
                        double ib = (c < numClasses) ? incomingBlock[c] : 0.0;
                        double p = (rb > 0.0) ? (ib / rb) : 0.0;
                        for (int v = 0; v < vec.numValues(); v++) {
                            vec.setValue(v, vec.getValue(v) * p);
                        }
                        newBlockWeight += vec.sumOfValues();
                    }
                    nomObs.blockWeightObserved = newBlockWeight + blockMissing * frac;
                }
            }
        }
    }

    public static abstract class LearningNode extends Node {

        private static final long serialVersionUID = 1L;

        public LearningNode(double[] initialClassObservations) {
            super(initialClassObservations);
        }

        public abstract void learnFromInstance(Instance inst, FederatedHoeffdingTree ht);
    }

    public static class InactiveLearningNode extends LearningNode {

        private static final long serialVersionUID = 1L;

        public InactiveLearningNode(double[] initialClassObservations) {
            super(initialClassObservations);
        }

        @Override
        public void learnFromInstance(Instance inst, FederatedHoeffdingTree ht) {
            this.observedClassDistribution.addToValue((int) inst.classValue(), inst.weight());
            this.blockObservedClassDistribution.addToValue((int) inst.classValue(), inst.weight());
        }

        @Override
        public Node merge(Node that) {
            // No observers here, so only the class block can be absorbed
            if (that instanceof SplitNode) {
                promoteBlock(collectLeafBlocks(that, new DoubleVector()));
            } else if (that instanceof LearningNode) {
                promoteBlock(that.blockObservedClassDistribution);
            }
            return this;
        }

        private void promoteBlock(DoubleVector otherBlock) {
            if (otherBlock == null) {
                return;
            }
            double[] blk = otherBlock.getArrayCopy();
            for (int c = 0; c < blk.length; c++) {
                if (blk[c] != 0.0) {
                    this.observedClassDistribution.addToValue(c, blk[c]);
                    this.blockObservedClassDistribution.addToValue(c, blk[c]);
                }
            }
        }

        @Override
        public void resetBlockStats() {
            int numClasses = this.observedClassDistribution.numValues();
            this.blockObservedClassDistribution = new DoubleVector(new double[numClasses]);
        }

        @Override
        public void printAttributeObservers(String indent) {
            throw new UnsupportedOperationException("Unimplemented method 'printAttributeObservers'");
        }

    }

    public static class ActiveLearningNode extends LearningNode {

        private static final long serialVersionUID = 1L;

        protected double weightSeenAtLastSplitEvaluation;

        protected AutoExpandVector<AttributeClassObserver> attributeObservers = new AutoExpandVector<AttributeClassObserver>();

        protected boolean isInitialized;

        protected double addonWeight;

        protected double blockAddonWeight;

        /**
         * Set when this leaf is the collapse of two incompatible splits.
         * splitPendingLeaves()
         * then re-splits it this round regardless of addonWeight and without
         * pre-pruning.
         * The flag is kept when the tree is copied: it must survive the copy() taken
         * before that call.
         */
        protected boolean pendingRebuildAfterCollapse;

        public ActiveLearningNode(double[] initialClassObservations) {
            super(initialClassObservations);
            this.weightSeenAtLastSplitEvaluation = getWeightSeen();
            this.isInitialized = false;
        }

        @Override
        public void printAttributeObservers(String indent) {
            for (int i = 0; i < this.attributeObservers.size(); i++) {
                AttributeClassObserver obs = this.attributeObservers.get(i);
                logger.info(indent
                        + "Observer[" + i + "] this.attributeObserver: "
                        + obs);
            }
        }

        @Override
        public void resetBlockStats() {
            int numClasses = this.observedClassDistribution.numValues();
            this.blockObservedClassDistribution = new DoubleVector(new double[numClasses]);
            this.blockAddonWeight = 0.0;
            resetObserverBlockStats(this.attributeObservers);
        }

        @Override
        public long calcByteSize() {
            return super.calcByteSize()
                    + SizeOf.fullSizeOf(this.attributeObservers);
        }

        @Override
        public void learnFromInstance(Instance inst, FederatedHoeffdingTree ht) {
            if (!this.isInitialized) {
                if (this.attributeObservers == null || this.attributeObservers.isEmpty()) {
                    this.attributeObservers = new AutoExpandVector<AttributeClassObserver>(inst.numAttributes());
                }
                this.isInitialized = true;
            }
            blockAddonWeight += inst.weight();
            addonWeight += inst.weight();
            this.blockObservedClassDistribution.addToValue((int) inst.classValue(), inst.weight());
            this.observedClassDistribution.addToValue((int) inst.classValue(), inst.weight());
            for (int i = 0; i < inst.numAttributes() - 1; i++) {
                int instAttIndex = modelAttIndexToInstanceAttIndex(i, inst);
                AttributeClassObserver obs = this.attributeObservers.get(i);
                if (obs == null) {
                    obs = inst.attribute(instAttIndex).isNominal() ? ht.newNominalClassObserver()
                            : ht.newNumericClassObserver();
                    this.attributeObservers.set(i, obs);
                }
                obs.observeAttributeClass(inst.value(instAttIndex), (int) inst.classValue(), inst.weight());
            }
        }

        public double getWeightSeen() {
            return this.observedClassDistribution.sumOfValues();
        }

        public double getWeightSeenAtLastSplitEvaluation() {
            return this.weightSeenAtLastSplitEvaluation;
        }

        public void setWeightSeenAtLastSplitEvaluation(double weight) {
            this.weightSeenAtLastSplitEvaluation = weight;
        }

        public AttributeSplitSuggestion[] getBestSplitSuggestions(
                SplitCriterion criterion, FederatedHoeffdingTree ht) {
            List<AttributeSplitSuggestion> bestSuggestions = new LinkedList<AttributeSplitSuggestion>();
            double[] preSplitDist = this.observedClassDistribution.getArrayCopy();
            if (!ht.noPrePruneOption.isSet()) {
                // add null split as an option
                bestSuggestions.add(new AttributeSplitSuggestion(null,
                        new double[0][], criterion.getMeritOfSplit(
                                preSplitDist,
                                new double[][] { preSplitDist })));
            }
            for (int i = 0; i < this.attributeObservers.size(); i++) {
                AttributeClassObserver obs = this.attributeObservers.get(i);
                if (obs != null) {
                    AttributeSplitSuggestion bestSuggestion = obs.getBestEvaluatedSplitSuggestion(criterion,
                            preSplitDist, i, ht.binarySplitsOption.isSet());
                    if (bestSuggestion != null) {
                        bestSuggestions.add(bestSuggestion);
                    }
                }
            }
            return bestSuggestions.toArray(new AttributeSplitSuggestion[bestSuggestions.size()]);
        }

        public void disableAttribute(int attIndex) {
            this.attributeObservers.set(attIndex,
                    new NullAttributeClassObserver());
        }

        public double addOnWeight() {
            return this.addonWeight;
        }

        public double getObserverWeightSeen() {
            double maxWeight = 0.0;
            if (this.attributeObservers == null) {
                return 0.0;
            }
            for (AttributeClassObserver obs : this.attributeObservers) {
                double w = 0.0;
                if (obs instanceof GaussianNumericAttributeClassObserver) {
                    w = ((GaussianNumericAttributeClassObserver) obs).totalWeightOfClassObservations();
                } else if (obs instanceof NominalAttributeClassObserver) {
                    w = ((NominalAttributeClassObserver) obs).totalWeightOfClassObservations();
                }
                if (w > maxWeight) {
                    maxWeight = w;
                }
            }
            return maxWeight;
        }

        @Override
        public Node merge(Node that) {
            if (that instanceof ActiveLearningNode) {
                ActiveLearningNode other = (ActiveLearningNode) that;
                promoteBlockClassDistribution(other.blockObservedClassDistribution);
                mergeObserverBlocks(other.attributeObservers);
                return this;
            }
            if (that instanceof SplitNode) {
                collapseSplitInto((SplitNode) that);
                return this;
            }
            if (that instanceof InactiveLearningNode) {
                promoteBlockClassDistribution(that.blockObservedClassDistribution);
                return this;
            }
            return this;
        }

        /**
         * Adds the 'otherBlock' to both this node's total and block class
         * distributions. Returns the promoted weight.
         */
        protected double promoteBlockClassDistribution(DoubleVector otherBlock) {
            if (otherBlock == null) {
                return 0.0;
            }
            double[] blk = otherBlock.getArrayCopy();
            double sum = 0.0;
            for (int c = 0; c < blk.length; c++) {
                double v = blk[c];
                if (v == 0.0) {
                    continue;
                }
                this.observedClassDistribution.addToValue(c, v);
                this.blockObservedClassDistribution.addToValue(c, v);
                sum += v;
            }
            this.addonWeight += sum;
            this.blockAddonWeight += sum;
            return sum;
        }

        protected void mergeObserverBlocks(AutoExpandVector<AttributeClassObserver> thatObservers) {
            this.isInitialized = true;
            if (this.attributeObservers == null) {
                this.attributeObservers = new AutoExpandVector<AttributeClassObserver>();
            }
            mergeObserverBlocksInto(this.attributeObservers, thatObservers);
        }

        protected void collapseSplitInto(SplitNode thatSplit) {
            Queue<Node> queue = new ArrayDeque<>();
            for (Node child : thatSplit.children) {
                if (child != null) {
                    queue.add(child);
                }
            }
            while (!queue.isEmpty()) {
                Node candidate = queue.poll();
                if (candidate instanceof SplitNode) {
                    SplitNode sn = (SplitNode) candidate;
                    for (Node subChild : sn.children) {
                        if (subChild != null) {
                            queue.add(subChild);
                        }
                    }
                    mergeObserverBlocks(sn.storedAttributeObservers);
                } else if (candidate instanceof ActiveLearningNode) {
                    ActiveLearningNode leaf = (ActiveLearningNode) candidate;
                    promoteBlockClassDistribution(leaf.blockObservedClassDistribution);
                    mergeObserverBlocks(leaf.attributeObservers);
                    onCollapsedLeaf(leaf);
                } else if (candidate instanceof InactiveLearningNode) {
                    promoteBlockClassDistribution(candidate.blockObservedClassDistribution);
                }
            }
            mergeObserverBlocks(thatSplit.storedAttributeObservers);
        }

        protected void onCollapsedLeaf(ActiveLearningNode leaf) {
            // nothing for a plain ActiveLearningNode
        }

        protected void absorbOwnSubtree(Node subtree) {
            if (subtree == null) {
                return;
            }
            this.isInitialized = true;
            if (this.attributeObservers == null) {
                this.attributeObservers = new AutoExpandVector<AttributeClassObserver>();
            }
            Queue<Node> queue = new ArrayDeque<>();
            queue.add(subtree);
            while (!queue.isEmpty()) {
                Node candidate = queue.poll();
                if (candidate instanceof SplitNode) {
                    SplitNode sn = (SplitNode) candidate;
                    for (Node child : sn.children) {
                        if (child != null) {
                            queue.add(child);
                        }
                    }
                    mergeObserverTotalsInto(this.attributeObservers, sn.storedAttributeObservers);
                } else if (candidate instanceof ActiveLearningNode) {
                    ActiveLearningNode leaf = (ActiveLearningNode) candidate;
                    absorbOwnClassStats(leaf);
                    this.addonWeight += leaf.addonWeight;
                    this.blockAddonWeight += leaf.blockAddonWeight;
                    mergeObserverTotalsInto(this.attributeObservers, leaf.attributeObservers);
                    onAbsorbedOwnLeaf(leaf);
                } else if (candidate instanceof LearningNode) {
                    absorbOwnClassStats(candidate);
                }
            }
        }

        /** Adds a node of this same tree. */
        private void absorbOwnClassStats(Node node) {
            double[] tot = node.observedClassDistribution.getArrayCopy();
            for (int c = 0; c < tot.length; c++) {
                if (tot[c] != 0.0) {
                    this.observedClassDistribution.addToValue(c, tot[c]);
                }
            }
            double[] blk = node.blockObservedClassDistribution.getArrayCopy();
            for (int c = 0; c < blk.length; c++) {
                if (blk[c] != 0.0) {
                    this.blockObservedClassDistribution.addToValue(c, blk[c]);
                }
            }
        }

        protected void onAbsorbedOwnLeaf(ActiveLearningNode leaf) {
            // nothing for a plain ActiveLearningNode
        }

        protected void markPendingRebuild() {
            this.pendingRebuildAfterCollapse = true;
            this.weightSeenAtLastSplitEvaluation = getWeightSeen();
        }
    }

    public Node treeRoot;

    public transient boolean deferSplitsOnMerge = false;

    protected int decisionNodeCount;

    protected int activeLeafNodeCount;

    protected int inactiveLeafNodeCount;

    protected double inactiveLeafByteSizeEstimate;

    protected double activeLeafByteSizeEstimate;

    protected double byteSizeEstimateOverheadFraction;

    protected boolean growthAllowed;

    public long calcByteSize() {
        long size = SizeOf.sizeOf(this);
        if (this.treeRoot != null) {
            size += this.treeRoot.calcByteSizeIncludingSubtree();
        }
        return size;
    }

    public int getNodeCount() {
        return this.decisionNodeCount + this.activeLeafNodeCount + this.inactiveLeafNodeCount;
    }

    public Node getTreeRoot() {
        return this.treeRoot;
    }

    @Override
    public long measureByteSize() {
        return calcByteSize();
    }

    @Override
    public void resetLearningImpl() {
        this.treeRoot = null;
        this.decisionNodeCount = 0;
        this.activeLeafNodeCount = 0;
        this.inactiveLeafNodeCount = 0;
        this.inactiveLeafByteSizeEstimate = 0.0;
        this.activeLeafByteSizeEstimate = 0.0;
        this.byteSizeEstimateOverheadFraction = 1.0;
        this.growthAllowed = true;
        if (this.leafpredictionOption.getChosenIndex() > 0) {
            this.removePoorAttsOption = null;
        }
    }

    @Override
    public void trainOnInstanceImpl(Instance inst) {
        if (this.treeRoot == null) {
            this.treeRoot = newLearningNode();
            this.activeLeafNodeCount = 1;
        }
        FoundNode foundNode = this.treeRoot.filterInstanceToLeaf(inst, null, -1);
        Node leafNode = foundNode.node;
        if (leafNode == null) {
            leafNode = newLearningNode();
            foundNode.parent.setChild(foundNode.parentBranch, leafNode);
            this.activeLeafNodeCount++;
        }
        if (leafNode instanceof LearningNode) {
            LearningNode learningNode = (LearningNode) leafNode;
            learningNode.learnFromInstance(inst, this);
            if (this.growthAllowed
                    && (learningNode instanceof ActiveLearningNode)) {
                ActiveLearningNode activeLearningNode = (ActiveLearningNode) learningNode;
                double weightSeen = activeLearningNode.getWeightSeen();
                if (weightSeen
                        - activeLearningNode.getWeightSeenAtLastSplitEvaluation() >= this.gracePeriodOption
                                .getValue()) {
                    attemptToSplit(activeLearningNode, foundNode.parent,
                            foundNode.parentBranch);
                    activeLearningNode.setWeightSeenAtLastSplitEvaluation(weightSeen);
                }
            }
        }
        if (this.trainingWeightSeenByModel
                % this.memoryEstimatePeriodOption.getValue() == 0) {
            estimateModelByteSizes();
        }
    }

    @Override
    public double[] getVotesForInstance(Instance inst) {
        int numClasses = inst.dataset().numClasses();
        if (this.treeRoot != null) {
            FoundNode foundNode = this.treeRoot.filterInstanceToLeaf(inst,
                    null, -1);
            Node leafNode = foundNode.node;
            if (leafNode == null) {
                leafNode = foundNode.parent;
            }
            return leafNode.getClassVotes(inst, this);
        } else {
            return new double[numClasses];
        }
    }

    @Override
    protected Measurement[] getModelMeasurementsImpl() {
        return new Measurement[] {
                new Measurement("tree size (nodes)", this.decisionNodeCount
                        + this.activeLeafNodeCount + this.inactiveLeafNodeCount),
                new Measurement("tree size (leaves)", this.activeLeafNodeCount
                        + this.inactiveLeafNodeCount),
                new Measurement("active learning leaves",
                        this.activeLeafNodeCount),
                new Measurement("tree depth", measureTreeDepth()),
                new Measurement("active leaf byte size estimate",
                        this.activeLeafByteSizeEstimate),
                new Measurement("inactive leaf byte size estimate",
                        this.inactiveLeafByteSizeEstimate),
                new Measurement("byte size estimate overhead",
                        this.byteSizeEstimateOverheadFraction) };
    }

    public int measureTreeDepth() {
        if (this.treeRoot != null) {
            return this.treeRoot.subtreeDepth();
        }
        return 0;
    }

    @Override
    public void getModelDescription(StringBuilder out, int indent) {
        this.treeRoot.describeSubtree(this, out, indent);
    }

    @Override
    public boolean isRandomizable() {
        return false;
    }

    public static double computeHoeffdingBound(double range, double confidence,
            double n) {
        return Math.sqrt(((range * range) * Math.log(1.0 / confidence))
                / (2.0 * n));
    }

    // Procedure added for Hoeffding Adaptive Trees (ADWIN)
    protected SplitNode newSplitNode(InstanceConditionalTest splitTest,
            double[] classObservations, int size) {
        return new SplitNode(splitTest, classObservations, size);
    }

    protected SplitNode newSplitNode(InstanceConditionalTest splitTest,
            double[] classObservations) {
        return new SplitNode(splitTest, classObservations);
    }

    protected AttributeClassObserver newNominalClassObserver() {
        AttributeClassObserver nominalClassObserver = (AttributeClassObserver) getPreparedClassOption(
                this.nominalEstimatorOption);
        return (AttributeClassObserver) nominalClassObserver.copy();
    }

    protected AttributeClassObserver newNumericClassObserver() {
        AttributeClassObserver numericClassObserver = (AttributeClassObserver) getPreparedClassOption(
                this.numericEstimatorOption);
        return (AttributeClassObserver) numericClassObserver.copy();
    }

    protected void attemptToSplit(ActiveLearningNode node, SplitNode parent,
            int parentIndex) {
        attemptToSplit(node, parent, parentIndex, true);
    }

    protected void attemptToSplit(ActiveLearningNode node, SplitNode parent,
            int parentIndex, boolean allowPrePrune) {
        if (!node.observedClassDistributionIsPure()) {

            if (!hasObserverData(node)) {
                return;
            }

            SplitCriterion splitCriterion = (SplitCriterion) getPreparedClassOption(this.splitCriterionOption);
            AttributeSplitSuggestion[] bestSplitSuggestions = node.getBestSplitSuggestions(splitCriterion, this);
            Arrays.sort(bestSplitSuggestions);
            boolean shouldSplit = false;
            if (bestSplitSuggestions.length < 2) {
                shouldSplit = bestSplitSuggestions.length > 0;
            } else {
                double hoeffdingBound = computeHoeffdingBound(
                        splitCriterion.getRangeOfMerit(node.getObservedClassDistribution()),
                        this.splitConfidenceOption.getValue(), node.getWeightSeen());
                AttributeSplitSuggestion bestSuggestion = bestSplitSuggestions[bestSplitSuggestions.length - 1];
                AttributeSplitSuggestion secondBestSuggestion = bestSplitSuggestions[bestSplitSuggestions.length - 2];
                if ((bestSuggestion.merit - secondBestSuggestion.merit > hoeffdingBound)
                        || (hoeffdingBound < this.tieThresholdOption.getValue())) {
                    shouldSplit = true;
                }

                if ((this.removePoorAttsOption != null)
                        && this.removePoorAttsOption.isSet()) {
                    Set<Integer> poorAtts = new HashSet<Integer>();
                    // scan 1 - add any poor to set
                    for (int i = 0; i < bestSplitSuggestions.length; i++) {
                        if (bestSplitSuggestions[i].splitTest != null) {
                            int[] splitAtts = bestSplitSuggestions[i].splitTest.getAttsTestDependsOn();
                            if (splitAtts.length == 1) {
                                if (bestSuggestion.merit
                                        - bestSplitSuggestions[i].merit > hoeffdingBound) {
                                    poorAtts.add(Integer.valueOf(splitAtts[0]));
                                }
                            }
                        }
                    }
                    // scan 2 - remove good ones from set
                    for (int i = 0; i < bestSplitSuggestions.length; i++) {
                        if (bestSplitSuggestions[i].splitTest != null) {
                            int[] splitAtts = bestSplitSuggestions[i].splitTest.getAttsTestDependsOn();
                            if (splitAtts.length == 1) {
                                if (bestSuggestion.merit
                                        - bestSplitSuggestions[i].merit < hoeffdingBound) {
                                    poorAtts.remove(Integer.valueOf(splitAtts[0]));
                                }
                            }
                        }
                    }
                    for (int poorAtt : poorAtts) {
                        node.disableAttribute(poorAtt);
                    }
                }
            }
            if (shouldSplit) {

                AutoExpandVector<AttributeClassObserver> origObs = new AutoExpandVector<AttributeClassObserver>();
                for (int i = 0; i < node.attributeObservers.size(); i++) {
                    AttributeClassObserver o = node.attributeObservers.get(i);
                    if (o != null) {
                        origObs.set(i, (AttributeClassObserver) o.copy());
                    }
                }
                AttributeSplitSuggestion splitDecision = bestSplitSuggestions[bestSplitSuggestions.length - 1];
                if (splitDecision.splitTest == null) {
                    // preprune - null wins
                    if (allowPrePrune) {
                        deactivateLearningNode(node, parent, parentIndex);
                    }

                } else {
                    int numSplits = splitDecision.numSplits();
                    double[] parentObsArr = node.getObservedClassDistribution();
                    double[] parentBlockArr = node.blockObservedClassDistribution.getArrayCopy();
                    int numClasses = parentObsArr.length;

                    double[][] allChildObs = new double[numSplits][numClasses];
                    for (int i = 0; i < numSplits; i++) {
                        double[] raw = splitDecision.resultingClassDistributionFromSplit(i);

                        for (int c = 0; c < numClasses; c++) {
                            allChildObs[i][c] = (c < raw.length ? raw[c] : 0.0);
                        }
                    }

                    // Compute total observed per class across children
                    double[] totalChildObs = new double[numClasses];

                    for (int c = 0; c < numClasses; c++) {
                        for (int i = 0; i < numSplits; i++) {
                            totalChildObs[c] += allChildObs[i][c];
                        }
                    }

                    double[] childTotalObs = new double[numSplits];
                    double totalObs = 0.0;
                    for (int i = 0; i < numSplits; i++) {
                        for (int c = 0; c < numClasses; c++) {
                            childTotalObs[i] += allChildObs[i][c];
                        }
                        totalObs += childTotalObs[i];
                    }
                    double[][] allChildBlock = new double[numSplits][numClasses];
                    for (int i = 0; i < numSplits; i++) {
                        for (int c = 0; c < numClasses; c++) {
                            double parentBlock = (c < parentBlockArr.length) ? parentBlockArr[c] : 0.0;
                            if (parentBlock == 0.0) {
                                allChildBlock[i][c] = 0.0;
                            } else if (totalChildObs[c] > 0.0) {
                                allChildBlock[i][c] = parentBlock * (allChildObs[i][c] / totalChildObs[c]);
                            } else if (totalObs > 0.0) {
                                allChildBlock[i][c] = parentBlock * (childTotalObs[i] / totalObs);
                            } else {
                                allChildBlock[i][c] = parentBlock / numSplits;
                            }
                        }
                    }

                    // Create new SplitNode and its children
                    SplitNode newSplit = newSplitNode(
                            splitDecision.splitTest,
                            parentObsArr,
                            numSplits);
                    newSplit.setStoredAttributeObservers(origObs); // we store the attributeObservers to SplitNode
                    newSplit.blockObservedClassDistribution = new DoubleVector(
                            Arrays.copyOf(parentBlockArr, numClasses));
                    for (int i = 0; i < numSplits; i++) {
                        Node newChild = newLearningNode(allChildObs[i]);
                        newChild.blockObservedClassDistribution = new DoubleVector(allChildBlock[i]);
                        newSplit.setChild(i, newChild);
                    }
                    this.activeLeafNodeCount--;
                    this.decisionNodeCount++;
                    this.activeLeafNodeCount += splitDecision.numSplits();
                    if (parent == null) {
                        this.treeRoot = newSplit;
                    } else {
                        parent.setChild(parentIndex, newSplit);
                    }
                }
                // manage memory
                enforceTrackerLimit();
            }
        }
    }

    public void enforceTrackerLimit() {
        if ((this.inactiveLeafNodeCount > 0)
                || ((this.activeLeafNodeCount * this.activeLeafByteSizeEstimate + this.inactiveLeafNodeCount
                        * this.inactiveLeafByteSizeEstimate)
                        * this.byteSizeEstimateOverheadFraction > this.maxByteSizeOption.getValue())) {
            if (this.stopMemManagementOption.isSet()) {
                this.growthAllowed = false;
                return;
            }
            FoundNode[] learningNodes = findLearningNodes();
            Arrays.sort(learningNodes, new Comparator<FoundNode>() {

                @Override
                public int compare(FoundNode fn1, FoundNode fn2) {
                    return Double.compare(fn1.node.calculatePromise(), fn2.node.calculatePromise());
                }
            });
            int maxActive = 0;
            while (maxActive < learningNodes.length) {
                maxActive++;
                if ((maxActive * this.activeLeafByteSizeEstimate + (learningNodes.length - maxActive)
                        * this.inactiveLeafByteSizeEstimate)
                        * this.byteSizeEstimateOverheadFraction > this.maxByteSizeOption.getValue()) {
                    maxActive--;
                    break;
                }
            }
            int cutoff = learningNodes.length - maxActive;
            for (int i = 0; i < cutoff; i++) {
                if (learningNodes[i].node instanceof ActiveLearningNode) {
                    deactivateLearningNode(
                            (ActiveLearningNode) learningNodes[i].node,
                            learningNodes[i].parent,
                            learningNodes[i].parentBranch);
                }
            }
            for (int i = cutoff; i < learningNodes.length; i++) {
                if (learningNodes[i].node instanceof InactiveLearningNode) {
                    activateLearningNode(
                            (InactiveLearningNode) learningNodes[i].node,
                            learningNodes[i].parent,
                            learningNodes[i].parentBranch);
                }
            }
        }
    }

    public void estimateModelByteSizes() {
        FoundNode[] learningNodes = findLearningNodes();
        long totalActiveSize = 0;
        long totalInactiveSize = 0;
        for (FoundNode foundNode : learningNodes) {
            if (foundNode.node instanceof ActiveLearningNode) {
                totalActiveSize += SizeOf.fullSizeOf(foundNode.node);
            } else {
                totalInactiveSize += SizeOf.fullSizeOf(foundNode.node);
            }
        }
        if (totalActiveSize > 0) {
            this.activeLeafByteSizeEstimate = (double) totalActiveSize
                    / this.activeLeafNodeCount;
        }
        if (totalInactiveSize > 0) {
            this.inactiveLeafByteSizeEstimate = (double) totalInactiveSize
                    / this.inactiveLeafNodeCount;
        }
        long actualModelSize = this.measureByteSize();
        double estimatedModelSize = (this.activeLeafNodeCount
                * this.activeLeafByteSizeEstimate
                + this.inactiveLeafNodeCount
                        * this.inactiveLeafByteSizeEstimate);
        this.byteSizeEstimateOverheadFraction = actualModelSize
                / estimatedModelSize;
        if (actualModelSize > this.maxByteSizeOption.getValue()) {
            enforceTrackerLimit();
        }
    }

    public void deactivateAllLeaves() {
        FoundNode[] learningNodes = findLearningNodes();
        for (int i = 0; i < learningNodes.length; i++) {
            if (learningNodes[i].node instanceof ActiveLearningNode) {
                deactivateLearningNode(
                        (ActiveLearningNode) learningNodes[i].node,
                        learningNodes[i].parent, learningNodes[i].parentBranch);
            }
        }
    }

    protected void deactivateLearningNode(ActiveLearningNode toDeactivate,
            SplitNode parent, int parentBranch) {
        Node newLeaf = new InactiveLearningNode(toDeactivate.getObservedClassDistribution());
        newLeaf.blockObservedClassDistribution = new DoubleVector(
                toDeactivate.blockObservedClassDistribution.getArrayCopy());
        newLeaf.blockObservedClassDistribution = new DoubleVector(
                toDeactivate.blockObservedClassDistribution.getArrayCopy());
        if (parent == null) {
            this.treeRoot = newLeaf;
        } else {
            parent.setChild(parentBranch, newLeaf);
        }
        this.activeLeafNodeCount--;
        this.inactiveLeafNodeCount++;
    }

    protected void activateLearningNode(InactiveLearningNode toActivate,
            SplitNode parent, int parentBranch) {
        Node newLeaf = newLearningNode(toActivate.getObservedClassDistribution());
        newLeaf.blockObservedClassDistribution = new DoubleVector(
                toActivate.blockObservedClassDistribution.getArrayCopy());
        if (parent == null) {
            this.treeRoot = newLeaf;
        } else {
            parent.setChild(parentBranch, newLeaf);
        }
        this.activeLeafNodeCount++;
        this.inactiveLeafNodeCount--;
    }

    protected FoundNode[] findLearningNodes() {
        List<FoundNode> foundList = new LinkedList<FoundNode>();
        findLearningNodes(this.treeRoot, null, -1, foundList);
        return foundList.toArray(new FoundNode[foundList.size()]);
    }

    protected void findLearningNodes(Node node, SplitNode parent,
            int parentBranch, List<FoundNode> found) {
        if (node != null) {
            if (node instanceof LearningNode) {
                found.add(new FoundNode(node, parent, parentBranch));
            }
            if (node instanceof SplitNode) {
                SplitNode splitNode = (SplitNode) node;
                for (int i = 0; i < splitNode.numChildren(); i++) {
                    findLearningNodes(splitNode.getChild(i), splitNode, i,
                            found);
                }
            }
        }
    }

    public MultiChoiceOption leafpredictionOption = new MultiChoiceOption(
            "leafprediction", 'l', "Leaf prediction to use.", new String[] {
                    "MC", "NB", "NBAdaptive" },
            new String[] {
                    "Majority class",
                    "Naive Bayes",
                    "Naive Bayes Adaptive" },
            2);

    public IntOption nbThresholdOption = new IntOption(
            "nbThreshold",
            'q',
            "The number of instances a leaf should observe before permitting Naive Bayes.",
            0, 0, Integer.MAX_VALUE);
    private static final Logger logger = LogManager.getLogger(FederatedHoeffdingTree.class);

    public void printAllNodeWeights() {
        printWeightsRecursive(treeRoot, 0);
    }

    private void printWeightsRecursive(Node node, int depth) {
        String indent = "  ".repeat(depth);

        if (node instanceof ActiveLearningNode) {
            ActiveLearningNode aln = (ActiveLearningNode) node;
            logger.info(String.format("%s[d=%d] addonWeight=%.4f, blockAddonWeight=%.4f",
                    indent, node.depth(), aln.addOnWeight(), aln.blockAddonWeight));
        }

        if (node instanceof LearningNodeNBAdaptive) {
            LearningNodeNBAdaptive ana = (LearningNodeNBAdaptive) node;
            logger.info(String.format(
                    "%s[d=%d] mcCorrect=%.4f, nbCorrect=%.4f, mcBlockCorrect=%.4f, nbBlockCorrect=%.4f",
                    indent, node.depth(),
                    ana.mcCorrectWeight, ana.nbCorrectWeight,
                    ana.mcBlockCorrectWeight, ana.nbBlockCorrectWeight));
        }

        if (node instanceof SplitNode) {
            for (Node child : ((SplitNode) node).getChildren()) {
                if (child != null) {
                    printWeightsRecursive(child, depth + 1);
                }
            }
        }
    }

    public static class LearningNodeNB extends ActiveLearningNode {

        private static final long serialVersionUID = 1L;

        public LearningNodeNB(double[] initialClassObservations) {
            super(initialClassObservations);
        }

        @Override
        public double[] getClassVotes(Instance inst, FederatedHoeffdingTree ht) {
            if (getWeightSeen() >= ht.nbThresholdOption.getValue()) {
                return NaiveBayes.doNaiveBayesPrediction(inst,
                        this.observedClassDistribution,
                        this.attributeObservers);
            }
            return super.getClassVotes(inst, ht);
        }

        @Override
        public void disableAttribute(int attIndex) {
            // should not disable poor atts - they are used in NB calc
        }
    }

    public static class LearningNodeNBAdaptive extends LearningNodeNB {

        private static final long serialVersionUID = 1L;

        protected double mcCorrectWeight = 0.0;

        protected double nbCorrectWeight = 0.0;

        protected double mcBlockCorrectWeight = 0.0;

        protected double nbBlockCorrectWeight = 0.0;

        public LearningNodeNBAdaptive(double[] initialClassObservations) {
            super(initialClassObservations);
        }

        @Override
        public void printAttributeObservers(String indent) {
            for (int i = 0; i < this.attributeObservers.size(); i++) {
                AttributeClassObserver obs = this.attributeObservers.get(i);
                logger.info(indent
                        + "Observer[" + i + "] this.attributeObserver: "
                        + obs);
            }
        }

        @Override
        public void learnFromInstance(Instance inst, FederatedHoeffdingTree ht) {
            int trueClass = (int) inst.classValue();
            if (this.observedClassDistribution.maxIndex() == trueClass) {
                this.mcBlockCorrectWeight += inst.weight();
                this.mcCorrectWeight += inst.weight();
            }
            if (Utils.maxIndex(NaiveBayes.doNaiveBayesPrediction(inst,
                    this.observedClassDistribution, this.attributeObservers)) == trueClass) {
                this.nbBlockCorrectWeight += inst.weight();
                this.nbCorrectWeight += inst.weight();
            }
            super.learnFromInstance(inst, ht);
        }

        @Override
        public double[] getClassVotes(Instance inst, FederatedHoeffdingTree ht) {
            if (this.mcCorrectWeight > this.nbCorrectWeight) {
                return this.observedClassDistribution.getArrayCopy();
            }
            return NaiveBayes.doNaiveBayesPrediction(inst,
                    this.observedClassDistribution, this.attributeObservers);
        }

        @Override
        public void resetBlockStats() {
            super.resetBlockStats();
            this.mcBlockCorrectWeight = 0.0;
            this.nbBlockCorrectWeight = 0.0;
        }

        @Override
        public Node merge(Node that) {
            if (that instanceof LearningNodeNBAdaptive) {
                LearningNodeNBAdaptive other = (LearningNodeNBAdaptive) that;
                promoteBlockClassDistribution(other.blockObservedClassDistribution);
                promoteBlockCorrectWeights(other);
                mergeObserverBlocks(other.attributeObservers);
                return this;
            }
            return super.merge(that);
        }

        @Override
        protected void onCollapsedLeaf(ActiveLearningNode leaf) {
            if (leaf instanceof LearningNodeNBAdaptive) {
                promoteBlockCorrectWeights((LearningNodeNBAdaptive) leaf);
            }
        }

        @Override
        protected void onAbsorbedOwnLeaf(ActiveLearningNode leaf) {
            if (leaf instanceof LearningNodeNBAdaptive) {
                LearningNodeNBAdaptive other = (LearningNodeNBAdaptive) leaf;
                this.mcCorrectWeight += other.mcCorrectWeight;
                this.nbCorrectWeight += other.nbCorrectWeight;
                this.mcBlockCorrectWeight += other.mcBlockCorrectWeight;
                this.nbBlockCorrectWeight += other.nbBlockCorrectWeight;
            }
        }

        private void promoteBlockCorrectWeights(LearningNodeNBAdaptive other) {
            this.mcBlockCorrectWeight += other.mcBlockCorrectWeight;
            this.nbBlockCorrectWeight += other.nbBlockCorrectWeight;
            this.mcCorrectWeight += other.mcBlockCorrectWeight;
            this.nbCorrectWeight += other.nbBlockCorrectWeight;
        }
    }

    protected LearningNode newLearningNode() {
        int numClassesToInit = 0;
        if (this.modelContext != null && this.modelContext.numClasses() > 0) {
            numClassesToInit = this.modelContext.numClasses();
        }

        double[] initialObservations = new double[numClassesToInit];

        int predictionMode = this.leafpredictionOption.getChosenIndex();
        switch (predictionMode) {
            case 1: // NB
                return new LearningNodeNB(initialObservations);
            case 2: // NBAdaptive
                return new LearningNodeNBAdaptive(initialObservations);
            case 0: // ActiveLearningNode
            default:
                return new ActiveLearningNode(initialObservations);
        }
    }

    protected LearningNode newLearningNode(double[] initialClassObservations) {
        LearningNode ret;
        int predictionOption = this.leafpredictionOption.getChosenIndex();
        if (predictionOption == 0) { // MC
            ret = new ActiveLearningNode(initialClassObservations);
        } else if (predictionOption == 1) { // NB
            ret = new LearningNodeNB(initialClassObservations);
        } else { // NBAdaptive
            ret = new LearningNodeNBAdaptive(initialClassObservations);
        }
        return ret;
    }

    @Override
    public ImmutableCapabilities defineImmutableCapabilities() {
        if (this.getClass() == FederatedHoeffdingTree.class)
            return new ImmutableCapabilities(Capability.VIEW_STANDARD, Capability.VIEW_LITE);
        else
            return new ImmutableCapabilities(Capability.VIEW_STANDARD);
    }

    double totalAddOnWeight = 0.0;

    public FederatedHoeffdingTree emptyCopy() {
        FederatedHoeffdingTree t = (FederatedHoeffdingTree) this.copy();
        t.treeRoot = t.newLearningNode();
        t.decisionNodeCount = 0;
        t.activeLeafNodeCount = 1;
        t.inactiveLeafNodeCount = 0;
        t.trainingWeightSeenByModel = 0.0;
        return t;
    }

    public FederatedHoeffdingTree merge(FederatedHoeffdingTree that) {

        if (that == null || that.treeRoot == null) {
            return this; // nothing to merge
        }
        if (this.treeRoot == null) {
            this.treeRoot = (Node) that.treeRoot.copy();
            this.trainingWeightSeenByModel = that.trainingWeightSeenByModel;
            recountNodes();
            return this;
        }
        double deltaWeight = calculateTotalBlockWeight(that.treeRoot);
        this.treeRoot = this.treeRoot.merge((Node) that.treeRoot.copy(), this);

        this.treeRoot.setDepth(0);
        recountNodes();
        if (!this.deferSplitsOnMerge) {
            splitPendingLeaves();
        }
        this.trainingWeightSeenByModel += deltaWeight;
        return this;
    }

    /**
     * One pass over the tree: split every leaf that gained a grace period since its
     * last evaluation.
     */
    public void splitPendingLeaves() {
        LinkedList<FoundNode> queue = new LinkedList<>();
        queue.add(new FoundNode(this.treeRoot, null, -1));
        while (!queue.isEmpty()) {
            FoundNode fn = queue.removeFirst();
            if (fn.node instanceof SplitNode) {
                SplitNode split = (SplitNode) fn.node;
                for (int i = 0; i < split.numChildren(); i++) {
                    queue.add(new FoundNode(split.getChild(i), split, i));
                }
            } else if (fn.node instanceof ActiveLearningNode) {
                ActiveLearningNode aln = (ActiveLearningNode) fn.node;
                double weightSeen = aln.getWeightSeen();
                boolean rebuild = aln.pendingRebuildAfterCollapse;

                if (rebuild || aln.addonWeight > this.gracePeriodOption.getValue()) {
                    if (fn.parent != null) {
                        int max = fn.parent.splitTest.maxBranches();
                        if (max >= 0 && fn.parentBranch >= max) {
                            continue;
                        }
                    }
                    aln.pendingRebuildAfterCollapse = false;
                    attemptToSplit(aln, fn.parent, fn.parentBranch, !rebuild);
                    aln.setWeightSeenAtLastSplitEvaluation(weightSeen);
                }
            }
        }

    }

    protected void recountNodes() {
        int decision = 0;
        int active = 0;
        int inactive = 0;
        if (this.treeRoot != null) {
            Queue<Node> queue = new ArrayDeque<>();
            queue.add(this.treeRoot);
            while (!queue.isEmpty()) {
                Node n = queue.poll();
                if (n instanceof SplitNode) {
                    decision++;
                    for (Node child : ((SplitNode) n).children) {
                        if (child != null) {
                            queue.add(child);
                        }
                    }
                } else if (n instanceof InactiveLearningNode) {
                    inactive++;
                } else {
                    active++;
                }
            }
        }
        this.decisionNodeCount = decision;
        this.activeLeafNodeCount = active;
        this.inactiveLeafNodeCount = inactive;
    }

    protected static DoubleVector collectLeafBlocks(Node node, DoubleVector acc) {
        if (node == null) {
            return acc;
        }
        if (node instanceof SplitNode) {
            for (Node child : ((SplitNode) node).children) {
                collectLeafBlocks(child, acc);
            }
        } else if (node.blockObservedClassDistribution != null) {
            acc.addValues(node.blockObservedClassDistribution.getArrayRef());
        }
        return acc;
    }

    /**
     * Calculates the total weight of the data currently in the leaf blocks
     * This represents the Delta or New Data seen since the last reset.
     */
    private double calculateTotalBlockWeight(Node node) {
        if (node == null) {
            return 0.0;
        }

        if (node instanceof SplitNode) {
            double sum = 0.0;
            for (Node child : ((SplitNode) node).getChildren()) {
                sum += calculateTotalBlockWeight(child);
            }
            return sum;
        }

        // For LearningNodes (Active or Inactive), we sum the block stats.
        if (node.blockObservedClassDistribution != null) {
            return node.blockObservedClassDistribution.sumOfValues();
        }

        return 0.0;
    }

    /**
     * Zeroes the block statistics of every observer in the vector.
     * Totals are untouched.
     */
    protected static void resetObserverBlockStats(AutoExpandVector<AttributeClassObserver> observers) {
        if (observers == null) {
            return;
        }
        for (AttributeClassObserver obs : observers) {
            if (obs instanceof GaussianNumericAttributeClassObserver) {
                GaussianNumericAttributeClassObserver gObs = (GaussianNumericAttributeClassObserver) obs;
                if (gObs.attValDistPerClass != null) {
                    for (GaussianEstimator est : gObs.attValDistPerClass) {
                        if (est != null) {
                            est.blockWeightSum = 0.0;
                            est.blockMean = 0.0;
                            est.blockVarianceSum = 0.0;
                        }
                    }
                }
            } else if (obs instanceof NominalAttributeClassObserver) {
                ((NominalAttributeClassObserver) obs).resetBlockStats();
            }
        }
    }

    protected static AttributeClassObserver newEmptyObserverLike(AttributeClassObserver template) {
        if (template instanceof GaussianNumericAttributeClassObserver) {
            GaussianNumericAttributeClassObserver fresh = new GaussianNumericAttributeClassObserver();
            fresh.numBinsOption.setValue(
                    ((GaussianNumericAttributeClassObserver) template).numBinsOption.getValue());
            return fresh;
        }
        if (template instanceof NominalAttributeClassObserver) {
            return new NominalAttributeClassObserver();
        }
        return null;
    }

    protected static void mergeObserverBlocksInto(AutoExpandVector<AttributeClassObserver> target,
            AutoExpandVector<AttributeClassObserver> source) {
        if (source == null || target == null) {
            return;
        }
        for (int i = 0; i < source.size(); i++) {
            AttributeClassObserver srcObs = source.get(i);
            if (srcObs == null) {
                continue;
            }
            AttributeClassObserver dstObs = target.get(i);
            if (dstObs == null) {
                dstObs = newEmptyObserverLike(srcObs);
                if (dstObs == null) {
                    continue;
                }
            }
            target.set(i, dstObs.merge(srcObs));
        }
    }

    protected static void mergeObserverTotalsInto(AutoExpandVector<AttributeClassObserver> target,
            AutoExpandVector<AttributeClassObserver> source) {
        if (source == null || target == null) {
            return;
        }
        for (int i = 0; i < source.size(); i++) {
            AttributeClassObserver srcObs = source.get(i);
            if (srcObs == null) {
                continue;
            }
            AttributeClassObserver dstObs = target.get(i);
            if (dstObs == null) {
                dstObs = newEmptyObserverLike(srcObs);
                if (dstObs == null) {
                    continue;
                }
                target.set(i, dstObs);
            }
            if (dstObs instanceof GaussianNumericAttributeClassObserver) {
                ((GaussianNumericAttributeClassObserver) dstObs).mergeAll(srcObs);
            } else if (dstObs instanceof NominalAttributeClassObserver) {
                ((NominalAttributeClassObserver) dstObs).mergeAll(srcObs);
            }
        }
    }

    /**
     * True when the two tests split the region the same way, so branch i on one
     * side holds the same population as branch i on the other and merging by index
     * is sound; false sends the region through a collapse.
     *
     * Regardless of the policy, the tests must be of the same kind and have the
     * same number of branches: a multiway and a binary test on the same attribute
     * number their branches differently.
     */
    protected boolean splitTestsCompatible(InstanceConditionalTest a, InstanceConditionalTest b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a == b) {
            return true;
        }
        if (a.getClass() != b.getClass()) {
            return false;
        }
        if (a.maxBranches() != b.maxBranches()) {
            return false;
        }
        if (!Arrays.equals(a.getAttsTestDependsOn(), b.getAttsTestDependsOn())) {
            return false;
        }
        if (this.splitMatchPolicyOption.getChosenIndex() == 0) {
            return true;
        }
        return sameConditions(a, b);
    }

    /**
     * Compares the condition each test prints for each branch.
     * InstanceConditionalTest
     * has no equals() and its threshold accessors differ across MOA versions, so
     * the
     * printed condition is the one portable handle on a split point. A pair whose
     * conditions cannot be read is treated as different. Since MOA may round a
     * numeric
     * threshold when printing, EXACT means "the same printed condition".
     */
    private boolean sameConditions(InstanceConditionalTest a, InstanceConditionalTest b) {
        int branches = a.maxBranches();
        if (branches < 0) {
            // Multiway: the branch index is the attribute value, so two tests on the
            // same attribute already agree on every branch.
            return true;
        }
        try {
            for (int branch = 0; branch < branches; branch++) {
                String condA = a.describeConditionForBranch(branch, getModelContext());
                String condB = b.describeConditionForBranch(branch, getModelContext());
                if (condA == null ? condB != null : !condA.equals(condB)) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * True if the node has at least one attribute observer to evaluate splits on.
     */
    protected static boolean hasObserverData(ActiveLearningNode node) {
        if (node.attributeObservers == null) {
            return false;
        }
        for (AttributeClassObserver obs : node.attributeObservers) {
            if (obs != null) {
                return true;
            }
        }
        return false;
    }
}