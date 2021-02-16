package org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.opensha.commons.data.ShortNamed;
import org.opensha.commons.util.ExceptionUtils;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.ClusterRupture;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.FaultSubsectionCluster;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.Jump;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.PlausibilityConfiguration;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.PlausibilityFilter;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.ScalarValuePlausibiltyFilter;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl.CumulativeProbabilityFilter.RuptureProbabilityCalc;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.strategies.ClusterConnectionStrategy;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.util.FilterDataClusterRupture;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.util.RuptureTreeNavigator;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.util.SectionDistanceAzimuthCalculator;
import org.opensha.sha.faultSurface.FaultSection;
import org.opensha.sha.simulators.stiffness.AggregatedStiffnessCalculator;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Range;
import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import scratch.UCERF3.inversion.laughTest.PlausibilityResult;

/**
 * This is a filter that tests various plausibility rules as paths through a rupture. Each cluster in the rupture is
 * considered as a potential nucleation point, and then the rupture is tested as is grows outward from that nucleation
 * cluster (bi/unilaterally for ruptures with more than 2 clusters). If fractPassThreshold == 0, then a rupture passes
 * if any nucleation clusters pass. Otherwise, at least fractPassThreshold fraction of the nucleation clusters must pass.
 * 
 * @author kevin
 *
 */
public class PathPlausibilityFilter implements PlausibilityFilter {

	public static interface NucleationClusterEvaluator extends ShortNamed {
		
		public PlausibilityResult testNucleationCluster(ClusterRupture rupture,
				FaultSubsectionCluster nucleationCluster, boolean verbose);
		
		public PlausibilityResult getFailureType();
		
		public default List<FaultSubsectionCluster> getDestinations(RuptureTreeNavigator nav,
				FaultSubsectionCluster from, HashSet<FaultSubsectionCluster> strandClusters) {
			List<FaultSubsectionCluster> ret = new ArrayList<>();
			
			FaultSubsectionCluster predecessor = nav.getPredecessor(from);
			if (predecessor != null && (strandClusters == null || !strandClusters.contains(predecessor)))
				ret.add(predecessor);
			
			for (FaultSubsectionCluster descendant : nav.getDescendants(from))
				if (strandClusters == null || !strandClusters.contains(descendant))
					ret.add(descendant);
			
			return ret;
		}
		
		public default void init(ClusterConnectionStrategy connStrat, SectionDistanceAzimuthCalculator distAzCalc) {
			// do nothing
		}
	}
	
	public static interface ScalarNucleationClusterEvaluator<E extends Number & Comparable<E>> extends NucleationClusterEvaluator {
		
		public E getNucleationClusterValue(ClusterRupture rupture,
				FaultSubsectionCluster nucleationCluster, boolean verbose);
		
		/**
		 * @return acceptable range of values
		 */
		public Range<E> getAcceptableRange();
		
		/**
		 * @return name of this scalar value
		 */
		public String getScalarName();
		
		/**
		 * @return units of this scalar value
		 */
		public String getScalarUnits();
		
	}
	
	public static abstract class PathEvaluator implements NucleationClusterEvaluator {

		public abstract PlausibilityResult testAddition(Collection<FaultSection> curSects,
				PathAddition addition, boolean verbose);
		
		protected abstract PathNavigator getPathNav(ClusterRupture rupture, FaultSubsectionCluster nucleationCluster);
//		protected SectionPathNavigator getSectPathNav(ClusterRupture rupture, FaultSubsectionCluster nucleationCluster) {
//			return new SectionPathNavigator(nucleationCluster.subSects, rupture.getTreeNavigator());
//		}
		
		@Override
		public PlausibilityResult testNucleationCluster(ClusterRupture rupture,
				FaultSubsectionCluster nucleationCluster, boolean verbose) {
			PathNavigator nav = getPathNav(rupture, nucleationCluster);
			nav.setVerbose(verbose);

			if (verbose)
				System.out.println(getName()+": testing strand(s) with start="+nucleationCluster);
			
			List<FaultSection> curSects = nav.getCurrentSects();
			Set<PathAddition> nextAdds = nav.getNextAdditions();
			if (verbose)
				System.out.println("Have "+nextAdds.size()+" nextAdds");
			
			PlausibilityResult result = PlausibilityResult.PASS;
			while (!nextAdds.isEmpty()) {
				for (PathAddition add : nextAdds) {
					PlausibilityResult myResult = testAddition(curSects, add, verbose);
					if (verbose)
						System.out.println("\taddition="+add+" w/ "+curSects.size()+" sources: "+myResult);
					result = result.logicalAnd(myResult);
					if (!verbose && !result.isPass())
						return result;
				}
				
				curSects = nav.getCurrentSects();
				nextAdds = nav.getNextAdditions();
				if (verbose)
					System.out.println("Have "+nextAdds.size()+" nextAdds");
			}
			Preconditions.checkState(nav.getCurrentSects().size() == rupture.getTotalNumSects(),
					"Processed %s sects but rupture has %s:\n\t%s", nav.getCurrentSects().size(), rupture.getTotalNumSects(), rupture);
			return result;
		}
	}
	
	public static abstract class ScalarPathEvaluator<E extends Number & Comparable<E>>
	extends PathEvaluator implements ScalarNucleationClusterEvaluator<E> {
		
		protected final Range<E> acceptableRange;
		protected final PlausibilityResult failureType;

		public ScalarPathEvaluator(Range<E> acceptableRange, PlausibilityResult failureType) {
			this.acceptableRange = acceptableRange;
			Preconditions.checkState(!failureType.isPass());
			this.failureType = failureType;
		}
		
		public abstract E getAdditionValue(Collection<FaultSection> curSects,
				PathAddition addition, boolean verbose);

		public PlausibilityResult testAddition(Collection<FaultSection> curSects,
				PathAddition addition, boolean verbose) {
			if (acceptableRange.contains(getAdditionValue(curSects, addition, verbose)))
				return PlausibilityResult.PASS;
			return failureType;
		}
		
		@Override
		public E getNucleationClusterValue(ClusterRupture rupture,
				FaultSubsectionCluster nucleationCluster, boolean verbose) {
			PathNavigator nav = getPathNav(rupture, nucleationCluster);
			nav.setVerbose(verbose);

			if (verbose)
				System.out.println(getName()+": testing strand(s) with start="+nucleationCluster);
			
			List<FaultSection> curSects = nav.getCurrentSects();
			Set<PathAddition> nextAdds = nav.getNextAdditions();
			if (verbose)
				System.out.println("Have "+nextAdds.size()+" nextAdds");
			
			E worstVal = null;
			while (!nextAdds.isEmpty()) {
				for (PathAddition add : nextAdds) {
					E val = getAdditionValue(curSects, add, verbose);
					if (worstVal == null || !ScalarValuePlausibiltyFilter.isValueBetter(val, worstVal, acceptableRange))
						worstVal = val;
					if (verbose)
						System.out.println("\taddition="+add+": "+val+" (worst="+worstVal+")");
				}
				
				curSects = nav.getCurrentSects();
				nextAdds = nav.getNextAdditions();
				if (verbose)
					System.out.println("Have "+nextAdds.size()+" nextAdds");
			}
			Preconditions.checkState(nav.getCurrentSects().size() == rupture.getTotalNumSects(),
					"Processed %s sects but rupture has %s:\n\t%s", nav.getCurrentSects().size(), rupture.getTotalNumSects(), rupture);
			return worstVal;
		}

		@Override
		public Range<E> getAcceptableRange() {
			return acceptableRange;
		}

		@Override
		public PlausibilityResult getFailureType() {
			return failureType;
		}
		
	}
	
	public static class ClusterCoulombPathEvaluator extends ScalarPathEvaluator<Float> {

		private AggregatedStiffnessCalculator aggCalc;

		public ClusterCoulombPathEvaluator(AggregatedStiffnessCalculator aggCalc, Range<Float> acceptableRange,
				PlausibilityResult failureType) {
			super(acceptableRange, failureType);
			this.aggCalc = aggCalc;
		}

		@Override
		public Float getAdditionValue(Collection<FaultSection> curSects, PathAddition addition, boolean verbose) {
			return (float)aggCalc.calc(curSects, addition.toSects);
//			return (float)aggCalc.calc(addition.fromCluster.subSects, addition.toSects);
		}

		@Override
		protected PathNavigator getPathNav(ClusterRupture rupture, FaultSubsectionCluster nucleationCluster) {
			return new ClusterPathNavigator(nucleationCluster, rupture.getTreeNavigator());
		}

		@Override
		public String getScalarName() {
			return aggCalc.getScalarName();
		}

		@Override
		public String getScalarUnits() {
			if (aggCalc.hasUnits())
				return aggCalc.getType().getUnits();
			return null;
		}

		@Override
		public String getShortName() {
			return "Cl "+aggCalc.getScalarShortName()+"]"+ScalarValuePlausibiltyFilter.getRangeStr(getAcceptableRange());
		}

		@Override
		public String getName() {
			return "Cluster ["+aggCalc.getScalarName()+"] "+ScalarValuePlausibiltyFilter.getRangeStr(getAcceptableRange());
		}
		
	}
	
	public static class SectCoulombPathEvaluator extends ScalarPathEvaluator<Float> {

		private AggregatedStiffnessCalculator aggCalc;
		private boolean jumpToMostFavorable;
		private float maxJumpDist;
		private transient SectionDistanceAzimuthCalculator distAzCalc;
		
		public SectCoulombPathEvaluator(AggregatedStiffnessCalculator aggCalc, Range<Float> acceptableRange,
				PlausibilityResult failureType) {
			this(aggCalc, acceptableRange, failureType, false, 0f, null);
		}

		public SectCoulombPathEvaluator(AggregatedStiffnessCalculator aggCalc, Range<Float> acceptableRange,
				PlausibilityResult failureType, boolean jumpToMostFavorable, float maxJumpDist,
				SectionDistanceAzimuthCalculator distAzCalc) {
			super(acceptableRange, failureType);
			this.aggCalc = aggCalc;
			this.jumpToMostFavorable = jumpToMostFavorable;
			if (jumpToMostFavorable) {
				Preconditions.checkState(maxJumpDist > 0d);
				Preconditions.checkNotNull(distAzCalc);
				this.maxJumpDist = maxJumpDist;
				this.distAzCalc = distAzCalc;
			} else {
				this.maxJumpDist = 0f;
				this.distAzCalc = null;
			}
		}
		
		public void init(ClusterConnectionStrategy connStrat, SectionDistanceAzimuthCalculator distAzCalc) {
			this.distAzCalc = distAzCalc;
		}
		
		@Override
		protected SectionPathNavigator getPathNav(ClusterRupture rupture,
				FaultSubsectionCluster nucleationCluster) {
			if (jumpToMostFavorable)
				return new CoulombFavorableSectionPathNavigator(nucleationCluster.subSects,
						rupture.getTreeNavigator(), aggCalc, acceptableRange, distAzCalc, maxJumpDist);
			return new SectionPathNavigator(nucleationCluster.subSects, rupture.getTreeNavigator());
		}

		@Override
		public Float getAdditionValue(Collection<FaultSection> curSects, PathAddition addition, boolean verbose) {
			Preconditions.checkState(addition.toSects.size() == 1);
			FaultSection destSect = addition.toSects.iterator().next();
			double val = aggCalc.calc(curSects, destSect);
			if (verbose)
				System.out.println("\t"+curSects.size()+" sources to "+destSect.getSectionId()+": "+val);
			return (float)val;
		}

		@Override
		public String getScalarName() {
			return aggCalc.getScalarName();
		}

		@Override
		public String getScalarUnits() {
			if (aggCalc.hasUnits())
				return aggCalc.getType().getUnits();
			return null;
		}

		@Override
		public String getShortName() {
			String sectStr = jumpToMostFavorable ? "SectFav"+new DecimalFormat("0.#").format(maxJumpDist) : "Sect";
			return sectStr+"["+aggCalc.getScalarShortName()+"]"+ScalarValuePlausibiltyFilter.getRangeStr(getAcceptableRange());
		}

		@Override
		public String getName() {
			String sectStr = jumpToMostFavorable ? "Sect Favorable ("+new DecimalFormat("0.#").format(maxJumpDist)+"km)" : "Sect";
			return sectStr+" ["+aggCalc.getScalarName()+"] "+ScalarValuePlausibiltyFilter.getRangeStr(getAcceptableRange());
		}
		
	}
	
	public static class CumulativeProbPathEvaluator implements ScalarNucleationClusterEvaluator<Float> {
		
		private RuptureProbabilityCalc[] calcs;
		private float minProbability;
		private PlausibilityResult failureType;

		public CumulativeProbPathEvaluator(float minProbability, PlausibilityResult failureType, RuptureProbabilityCalc... calcs) {
			Preconditions.checkState(calcs.length > 0, "Must supply at least one calculator");
			this.calcs = calcs;
			Preconditions.checkState(minProbability >= 0f && minProbability <= 1f);
			this.minProbability = minProbability;
			Preconditions.checkState(!failureType.isPass());
			this.failureType = failureType;
		}
		
		public void init(ClusterConnectionStrategy connStrat, SectionDistanceAzimuthCalculator distAzCalc) {
			for (RuptureProbabilityCalc calc : calcs)
				calc.init(connStrat, distAzCalc);
		}

		@Override
		public Range<Float> getAcceptableRange() {
			return Range.atLeast(minProbability);
		}

		@Override
		public String getScalarName() {
			return "Conditional Probability";
		}

		@Override
		public String getScalarUnits() {
			return null;
		}
		
		@Override
		public PlausibilityResult getFailureType() {
			return failureType;
		}
		
		private ClusterRupture buildRuptureForwards(ClusterRupture curRup, RuptureTreeNavigator nav,
				FaultSubsectionCluster fromCluster) {
			for (FaultSubsectionCluster toCluster : nav.getDescendants(fromCluster)) {
				Jump jump = nav.getJump(fromCluster, toCluster);
				// take that jump
				curRup = curRup.take(jump);
				// continue down strand
				curRup = buildRuptureForwards(curRup, nav, toCluster);
			}
			return curRup;
		}
		
		private ClusterRupture getNucleationRupture(ClusterRupture rupture, FaultSubsectionCluster nucleationCluster,
				boolean verbose) {
			ClusterRupture nucleationRupture;
			if (rupture.clusters[0] == nucleationCluster) {
				nucleationRupture = rupture;
			} else if (rupture.singleStrand && rupture.clusters[rupture.clusters.length-1] == nucleationCluster) {
				nucleationRupture = rupture.reversed();
			} else {
				// complex case, build out in each direction
				RuptureTreeNavigator nav = rupture.getTreeNavigator();
				// start at nucleation cluster
				nucleationRupture = new ClusterRupture(nucleationCluster);
				// build it out forwards
				nucleationRupture = buildRuptureForwards(nucleationRupture, nav, nucleationCluster);
				// build it out backwards, flipping each cluster
				FaultSubsectionCluster predecessor = nav.getPredecessor(nucleationCluster);
				FaultSubsectionCluster prevOrig = nucleationCluster;
				FaultSubsectionCluster prevReversed;
				if (nucleationRupture.getTotalNumClusters() == 1) {
					// flip it, this was at an endpoint
					prevReversed = nucleationCluster.reversed();
					nucleationRupture = new ClusterRupture(prevReversed);
				} else {
					// don't flip the first one, it's in the middle somewhere
					prevReversed = nucleationCluster;
				}
				while (predecessor != null) {
					Jump origJump = nav.getJump(predecessor, prevOrig);
					FaultSubsectionCluster reversed = predecessor.reversed();
					Jump reverseJump = new Jump(origJump.toSection, prevReversed,
							origJump.fromSection, reversed, origJump.distance);
					nucleationRupture = nucleationRupture.take(reverseJump);
					// see if we need to follow any splays from this cluster
					for (FaultSubsectionCluster descendant : nav.getDescendants(predecessor)) {
						if (!nucleationRupture.contains(descendant.startSect)) {
							// this is forward splay off of the predecessor, we need to follow it
							Jump origSplayJump = nav.getJump(predecessor, descendant);
							Jump newSplayJump = new Jump(origSplayJump.fromSection, reversed,
									origSplayJump.toSection, origSplayJump.toCluster, origSplayJump.distance);
							nucleationRupture = nucleationRupture.take(newSplayJump);
							// go down further if needed
							nucleationRupture = buildRuptureForwards(nucleationRupture, nav, descendant);
						}
					}
					prevOrig = predecessor;
					prevReversed = reversed;
					predecessor = nav.getPredecessor(predecessor);
				}
				Preconditions.checkState(nucleationRupture.getTotalNumSects() == rupture.getTotalNumSects(),
						"Nucleation view of rupture is incomplete!\n\tOriginal: %s\n\tNucleation cluster: %s"
						+ "\n\tNucleation rupture: %s", rupture, nucleationCluster, nucleationRupture);
			}
			if (verbose)
				System.out.println("Nucleation rupture: "+nucleationRupture);
			return nucleationRupture;
		}

		@Override
		public PlausibilityResult testNucleationCluster(ClusterRupture rupture,
				FaultSubsectionCluster nucleationCluster, boolean verbose) {
			// build the rupture such that it starts at the given nucleation cluster
			ClusterRupture nucleationRupture = getNucleationRupture(rupture, nucleationCluster, verbose);
			
			double prob = 1d;
			for (RuptureProbabilityCalc calc : calcs) {
				double myProb = calc.calcRuptureProb(nucleationRupture, verbose);
				prob *= myProb;
				if (verbose)
					System.out.println("\t"+calc.getName()+": P="+myProb);
				else if ((float)prob < minProbability)
					return failureType;
			}
			
			if ((float)prob >= minProbability)
				return PlausibilityResult.PASS;
			return failureType;
		}

		@Override
		public Float getNucleationClusterValue(ClusterRupture rupture,
				FaultSubsectionCluster nucleationCluster, boolean verbose) {
			// build the rupture such that it starts at the given nucleation cluster
			ClusterRupture nucleationRupture = getNucleationRupture(rupture, nucleationCluster, verbose);

			double prob = 1d;
			for (RuptureProbabilityCalc calc : calcs) {
				double myProb = calc.calcRuptureProb(nucleationRupture, verbose);
				prob *= myProb;
				if (verbose)
					System.out.println("\t"+calc.getName()+": P="+myProb);
			}

			return (float)prob;
		}
		
		@Override
		public String getShortName() {
			return "P("
//					+calc.getName().replaceAll(" ", "")
					+Arrays.stream(calcs).map(E -> E.getName().replaceAll(" ", "")).collect(Collectors.joining(", "))
					+")≥"+minProbability;
		}

		@Override
		public String getName() {
			return "P("
//					+calc.getName()
					+Arrays.stream(calcs).map(E -> E.getName()).collect(Collectors.joining(", "))
					+") ≥"+minProbability;
		}
		
	}
	
	public static class ScalarPathPlausibilityFilter<E extends Number & Comparable<E>>
	extends PathPlausibilityFilter implements ScalarValuePlausibiltyFilter<E> {
		
		private ScalarNucleationClusterEvaluator<E> evaluator;
		
		public ScalarPathPlausibilityFilter(ScalarNucleationClusterEvaluator<E> evaluator) {
			this(0f, evaluator);
		}

		public ScalarPathPlausibilityFilter(float fractPassThreshold, ScalarNucleationClusterEvaluator<E> evaluator) {
			super(fractPassThreshold, false, evaluator);
			this.evaluator = evaluator;
		}

		@Override
		public E getValue(ClusterRupture rupture) {
			if (rupture.getTotalNumJumps()  == 0)
				return null;
			List<E> vals = new ArrayList<>();
			for (FaultSubsectionCluster nucleationCluster : rupture.getClustersIterable()) {
//				float val = testNucleationPoint(navigator, nucleationCluster, false, false);
				E val = evaluator.getNucleationClusterValue(rupture, nucleationCluster, false);
				vals.add(val);
			}
			if (fractPassThreshold > 0f) {
				// if we need N paths to pass, return the Nth largest value outside
				// (such that if and only if that value passes, the rupture passes)
				int numPaths = vals.size();
				int numNeeded = Integer.max(1, (int)Math.ceil(fractPassThreshold*numPaths));
				Collections.sort(vals, worstToBestComparator());
				return vals.get(vals.size()-numNeeded);
			}
//			Float bestVal = getWorseValue(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY);
			E bestVal = null;
			for (E val : vals) {
				if (bestVal == null || isValueBetter(val, bestVal))
					bestVal = val;
			}
			return bestVal;
		}

		@Override
		public Range<E> getAcceptableRange() {
			return evaluator.getAcceptableRange();
		}

		@Override
		public String getScalarName() {
			return evaluator.getScalarName();
		}

		@Override
		public String getScalarUnits() {
			return evaluator.getScalarUnits();
		}
		
	}
	
	/**
	 * This interface defines how a rupture spreads. Typically, this is either outward one cluster at a time
	 * or one section at a time.
	 * @author kevin
	 *
	 */
	public static interface PathNavigator {
		
		/**
		 * @return the sections in the current path of this rupture, at the moment that this method is called
		 */
		public List<FaultSection> getCurrentSects();
		
		/**
		 * This locates and returns all of the next additions to this rupture. Those sections will be then consumed
		 * into the current path, so future calls to getCurrentSects() will contain the sections represented by these
		 * additions.
		 * 
		 * @return the next additions to this rupture (be they within clusters or to new clusters)
		 */
		public Set<PathAddition> getNextAdditions();
		
		public default void setVerbose(boolean verbose) {
			// to nothing
		}
	}
	
	/**
	 * This represents an addition to a growing rupture
	 * @author kevin
	 *
	 */
	public static class PathAddition {
		/**
		 * Section that was the jumping point to this addition
		 */
		public final FaultSection  fromSect;
		/**
		 * Cluster that contains fromSect
		 */
		public final FaultSubsectionCluster fromCluster;
		/**
		 * Collection of sections to be added
		 */
		public final Collection<? extends FaultSection> toSects;
		/**
		 * Cluster that contains toSect (may be equal to fromCluster)
		 */
		public final FaultSubsectionCluster toCluster;
		
		public PathAddition(FaultSection fromSect, FaultSubsectionCluster fromCluster,
				Collection<? extends FaultSection> toSects, FaultSubsectionCluster toCluster) {
			this.fromSect = fromSect;
			this.fromCluster = fromCluster;
			this.toSects = toSects;
			this.toCluster = toCluster;
		}
		
		public PathAddition(FaultSection fromSect, FaultSubsectionCluster fromCluster,
				FaultSection toSect, FaultSubsectionCluster toCluster) {
			this.fromSect = fromSect;
			this.fromCluster = fromCluster;
			this.toSects = Collections.singleton(toSect);
			this.toCluster = toCluster;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((toSects == null) ? 0 : toSects.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			PathAddition other = (PathAddition) obj;
			if (toSects == null) {
				if (other.toSects != null)
					return false;
			} else if (!toSects.equals(other.toSects))
				return false;
			return true;
		}
		
		public String toString() {
			String ret;
			if (fromSect != null)
				ret = fromSect.getSectionId()+"->[";
			else
				ret = "?->[";
			return ret+toSects.stream().map(S -> S.getSectionId()).map(S -> S.toString())
					.collect(Collectors.joining(","))+"]";
		}
	}
	
	/**
	 * Path navigator where rupture grow outward, cluster by cluster
	 * 
	 * @author kevin
	 *
	 */
	public static class ClusterPathNavigator implements PathNavigator {
		
		protected HashSet<FaultSubsectionCluster> currentClusters;
		protected HashSet<FaultSection> currentSects;
		protected RuptureTreeNavigator rupNav;
		
		private List<FaultSubsectionCluster> growthPoints;
		
		protected boolean verbose = true;
		
		public ClusterPathNavigator(FaultSubsectionCluster startCluster, RuptureTreeNavigator nav) {
			currentSects = new HashSet<>(startCluster.subSects);
			currentClusters = new HashSet<>();
			currentClusters.add(startCluster);
			this.rupNav = nav;
			this.growthPoints = new ArrayList<>();
			this.growthPoints.add(startCluster);
		}
		
		public void setVerbose(boolean verbose) {
			this.verbose = verbose;
		}

		@Override
		public List<FaultSection> getCurrentSects() {
			return new ArrayList<>(currentSects);
		}
		
		protected List<FaultSubsectionCluster> getNeighbors(FaultSubsectionCluster cluster) {
			List<FaultSubsectionCluster> neighbors = new ArrayList<>();
			FaultSubsectionCluster predecessor = rupNav.getPredecessor(cluster);
			if (predecessor != null)
				neighbors.add(predecessor);
			neighbors.addAll(rupNav.getDescendants(cluster));
			return neighbors;
		}

		@Override
		public Set<PathAddition> getNextAdditions() {
			HashSet<PathAddition> nextAdds = new HashSet<>();
			if (verbose)
				System.out.println("getNextAdditions with "+growthPoints.size()+" growth points, "+currentSects.size()+" curSects");
			for (FaultSubsectionCluster cluster : growthPoints)
				for (FaultSubsectionCluster neighbor : getNeighbors(cluster))
					if (!currentClusters.contains(neighbor))
						nextAdds.add(new PathAddition(rupNav.getJump(cluster, neighbor).fromSection, cluster, neighbor.subSects, neighbor));
			if (verbose)
				System.out.println("\tFound "+nextAdds.size()+" nextAdds: "+
						nextAdds.stream().map(S -> S.toString()).collect(Collectors.joining(";")));
			List<FaultSubsectionCluster> newGrowthPoints = new ArrayList<>(nextAdds.size());
			for (PathAddition add : nextAdds) {
				currentSects.addAll(add.toSects);
				newGrowthPoints.add(add.toCluster);
				currentClusters.add(add.toCluster);
			}
			growthPoints = newGrowthPoints;
			return nextAdds;
		}
		
	}
	
	/**
	 * Path navigator where rupture grow outward, section by section
	 * 
	 * @author kevin
	 *
	 */
	public static class SectionPathNavigator implements PathNavigator {
		
		protected HashSet<FaultSection> currentSects;
		protected RuptureTreeNavigator rupNav;
		
		private Set<FaultSection> growthPoints;
		
		protected boolean verbose = true;
		
		public SectionPathNavigator(Collection<? extends FaultSection> startSects, RuptureTreeNavigator nav) {
			currentSects = new HashSet<>(startSects);
			Preconditions.checkState(currentSects.size() == startSects.size());
//			System.out.println("Initializing SectionPathNav with "+startSects.size()+" startSects");
			this.rupNav = nav;
			this.growthPoints = currentSects;
		}
		
		public void setVerbose(boolean verbose) {
			this.verbose = verbose;
		}
		
		protected List<FaultSection> getNeighbors(FaultSection sect) {
			List<FaultSection> neighbors = new ArrayList<>();
			FaultSection predecessor = rupNav.getPredecessor(sect);
			if (predecessor != null)
				neighbors.add(predecessor);
			neighbors.addAll(rupNav.getDescendants(sect));
			return neighbors;
		}
		
		@Override
		public List<FaultSection> getCurrentSects() {
			return Lists.newArrayList(currentSects);
		}
		
		@Override
		public Set<PathAddition> getNextAdditions() {
			HashSet<PathAddition> nextAdds = new HashSet<>();
			if (verbose)
				System.out.println("getNextAdditions with "+growthPoints.size()+" growth points, "+currentSects.size()+" curSects");
			for (FaultSection sect : growthPoints) {
				FaultSubsectionCluster fromCluster = rupNav.locateCluster(sect);
				for (FaultSection neighbor : getNeighbors(sect))
					if (!currentSects.contains(neighbor))
						nextAdds.add(new PathAddition(sect, fromCluster, neighbor, rupNav.locateCluster(neighbor)));
			}
			if (verbose)
				System.out.println("\tFound "+nextAdds.size()+" nextAdds: "+
						nextAdds.stream().map(S -> S.toString()).collect(Collectors.joining(";")));
			HashSet<FaultSection> newGrowthPoints = new HashSet<>(nextAdds.size());
			for (PathAddition add : nextAdds) {
				currentSects.addAll(add.toSects);
				newGrowthPoints.addAll(add.toSects);
			}
			growthPoints = newGrowthPoints;
			return nextAdds;
		}
	}
	
	static FaultSection findMostFavorableJumpSect(Collection<? extends FaultSection> sources, Jump jump, float maxSearchDist,
			Range<Float> acceptableRange, AggregatedStiffnessCalculator aggCalc, SectionDistanceAzimuthCalculator distAzCalc,
			boolean verbose) {
		if (verbose)
			System.out.println("Finding most favorable jump to "+jump.toCluster+", origJump="+jump);
		List<FaultSection> allowedJumps = new ArrayList<>();
		for (FaultSection sect : jump.toCluster.subSects) {
			for (FaultSection source : jump.fromCluster.subSects) {
				if (sect == jump.toSection || (float)distAzCalc.getDistance(sect, source) <= maxSearchDist) {
					allowedJumps.add(sect);
					break;
				}
			}
		}
		Preconditions.checkState(!allowedJumps.isEmpty(), "No jumps within %s km found between %s and %s",
				maxSearchDist, jump.fromCluster, jump.toCluster);
		if (allowedJumps.size() == 1) {
			if (verbose)
				System.out.println("Only 1 possible jump: "+allowedJumps.get(0));
			return allowedJumps.get(0);
		}
		// find the most favorable one
		float bestVal = Float.NaN;
		FaultSection bestSect = null;
		for (FaultSection sect : allowedJumps) {
			float myVal = (float)aggCalc.calc(sources, sect);
			if (verbose)
				System.out.println("CFF to "+sect.getSectionId()+": "+myVal);
			if (Double.isNaN(bestVal) || ScalarValuePlausibiltyFilter.isValueBetter(myVal, bestVal, acceptableRange)) {
				bestVal = myVal;
				bestSect = sect;
			}
		}
		Preconditions.checkNotNull(bestSect);
		return bestSect;
	}
	
	public static class CoulombFavorableSectionPathNavigator extends SectionPathNavigator {

		private final AggregatedStiffnessCalculator aggCalc;
		private final SectionDistanceAzimuthCalculator distAzCalc;
		private final float maxSearchDist;
		private Range<Float> acceptableRange;

		public CoulombFavorableSectionPathNavigator(Collection<FaultSection> startSects, RuptureTreeNavigator nav,
				AggregatedStiffnessCalculator aggCalc, Range<Float> acceptableRange,
				SectionDistanceAzimuthCalculator distAzCalc, float maxSearchDist) {
			super(startSects, nav);
			this.aggCalc = aggCalc;
			this.acceptableRange = acceptableRange;
			this.distAzCalc = distAzCalc;
			this.maxSearchDist = maxSearchDist;
		}

		@Override
		protected List<FaultSection> getNeighbors(FaultSection fromSect) {
			List<FaultSection> neighbors = new ArrayList<>();
			for (FaultSection neighbor : super.getNeighbors(fromSect)) {
				if (currentSects.contains(neighbor))
					continue;
				if (neighbor.getParentSectionId() == fromSect.getParentSectionId()) {
					// not a jump
					if (verbose)
						System.out.println("\tneighbor of "+fromSect.getSectionId()+" is on same parent: "+neighbor.getSectionId());
					neighbors.add(neighbor);
				} else {
					// it's a jump, find most favorable
					Jump jump = rupNav.getJump(fromSect, neighbor);
					neighbors.add(findMostFavorableJumpSect(currentSects, jump, maxSearchDist, acceptableRange,
							aggCalc, distAzCalc, verbose));
				}
			}
			return neighbors;
		}
		
	}
	
	protected final float fractPassThreshold;
	private final NucleationClusterEvaluator[] evaluators;
	private final boolean logicalOr;
	
	private transient final PlausibilityResult failureType;
	
	public PathPlausibilityFilter(NucleationClusterEvaluator... evaluators) {
		this(0f, evaluators);
	}

	public PathPlausibilityFilter(float fractPassThreshold, NucleationClusterEvaluator... evaluators) {
		this(fractPassThreshold, false, evaluators);
	}

	public PathPlausibilityFilter(float fractPassThreshold, boolean logicalOr, NucleationClusterEvaluator... evaluators) {
		Preconditions.checkState(fractPassThreshold <= 1f);
		this.fractPassThreshold = fractPassThreshold;
		this.logicalOr = logicalOr;
		Preconditions.checkArgument(evaluators.length > 0, "must supply at least one path evaluator");
		this.evaluators = evaluators;
		PlausibilityResult failureType = null;
		for (NucleationClusterEvaluator eval : evaluators) {
			if (failureType == null)
				failureType = eval.getFailureType();
			else
				failureType = failureType.logicalAnd(eval.getFailureType());
		}
		this.failureType = failureType;
		Preconditions.checkState(!failureType.isPass());
	}

	@Override
	public PlausibilityResult apply(ClusterRupture rupture, boolean verbose) {
		if (rupture.getTotalNumJumps() == 0)
			return PlausibilityResult.PASS;
		List<FaultSubsectionCluster> clusters = Lists.newArrayList(rupture.getClustersIterable());
		int numPaths = clusters.size();
		int numPasses = 0;
		int numNeeded = 1;
		if (fractPassThreshold > 0f)
			numNeeded = Integer.max(1, (int)Math.ceil(fractPassThreshold*numPaths));
		HashSet<FaultSubsectionCluster> skipClusters = null;
		if (rupture instanceof FilterDataClusterRupture) {
			FilterDataClusterRupture fdRupture = (FilterDataClusterRupture)rupture;
			Object filterData = fdRupture.getFilterData(this);
			if (filterData != null && filterData instanceof HashSet<?>)
				skipClusters = new HashSet<>((HashSet<FaultSubsectionCluster>)filterData); 
			else
				skipClusters = new HashSet<>();
			fdRupture.addFilterData(this, skipClusters);
		}
		for (FaultSubsectionCluster nucleationCluster : clusters) {
			if (skipClusters != null && skipClusters.contains(nucleationCluster)) {
				// we can skip this one because it already failed in a subset of this rupture so it will
				// never pass here
				if (verbose)
					System.out.println("Skipping known cluster that won't work: "+nucleationCluster);
				continue;
			}
			
			PlausibilityResult result = PlausibilityResult.PASS;
			if (verbose)
				System.out.println(getShortName()+": Nucleation point "+nucleationCluster);
			for (NucleationClusterEvaluator eval : evaluators) {
				if (verbose)
					System.out.println("Testing "+eval.getName()+"...");
				PlausibilityResult subResult = eval.testNucleationCluster(rupture, nucleationCluster, verbose);
				if (verbose)
					System.out.println("\t"+eval.getName()+": "+subResult);
				if (logicalOr)
					result = result.logicalOr(subResult);
				else
					result = result.logicalAnd(subResult);
			}
			if (result.isPass())
				numPasses++;
			else if (skipClusters != null)
				skipClusters.add(nucleationCluster);
			if (!verbose && numPasses >= numNeeded)
				return PlausibilityResult.PASS;
		}
		if (verbose)
			System.out.println(getShortName()+": "+numPasses+"/"+numPaths+" pass, "+numNeeded+" needed");
		if (numPasses >= numNeeded)
			return PlausibilityResult.PASS;
		return failureType;
	}
	
	private String getPathString() {
		if (fractPassThreshold > 0f) {
			if (fractPassThreshold == 0.5f)
				return "Half Paths";
			if (fractPassThreshold == 1f/3f)
				return "1/3 Paths";
			if (fractPassThreshold == 2f/3f)
				return "2/3 Paths";
			if (fractPassThreshold == 0.25f)
				return "1/4 Paths";
			if (fractPassThreshold == 0.75f)
				return "3/4 Paths";
			return fractPassThreshold+"x Paths ";
		}
		return "Path";
	}

	@Override
	public String getShortName() {
		String paths = getPathString().replaceAll(" ", "");
		if (evaluators.length > 1)
			return paths+"["+evaluators.length+" criteria]";
		return paths+evaluators[0].getShortName();
	}

	@Override
	public String getName() {
		if (evaluators.length == 1)
			return getPathString()+" "+evaluators[0].getName();
		return getPathString()+" ["+Arrays.stream(evaluators).map(E -> E.getName()).collect(Collectors.joining(", "))+"]";
	}
	
	@Override
	public boolean isDirectional(boolean splayed) {
		return splayed;
	}

	@Override
	public TypeAdapter<PlausibilityFilter> getTypeAdapter() {
		return new Adapter();
	}
	
	public static class Adapter extends PlausibilityFilterTypeAdapter {

		private Gson gson;
		private ClusterConnectionStrategy connStrategy;
		private SectionDistanceAzimuthCalculator distAzCalc;

		@Override
		public void init(ClusterConnectionStrategy connStrategy, SectionDistanceAzimuthCalculator distAzCalc,
				Gson gson) {
			this.connStrategy = connStrategy;
			this.distAzCalc = distAzCalc;
			this.gson = gson;
		}

		@Override
		public void write(JsonWriter out, PlausibilityFilter value) throws IOException {
			Preconditions.checkState(value instanceof PathPlausibilityFilter);
			PathPlausibilityFilter filter = (PathPlausibilityFilter)value;
			out.beginObject();

			out.name("fractPassThreshold").value(filter.fractPassThreshold);
			out.name("logicalOr").value(filter.logicalOr);
			out.name("evaluators").beginArray();
			for (NucleationClusterEvaluator eval : filter.evaluators) {
				out.beginObject();
				out.name("class").value(eval.getClass().getName());
				out.name("value");
				if (eval instanceof CumulativeProbPathEvaluator) {
					out.beginObject();
					CumulativeProbPathEvaluator pathEval = (CumulativeProbPathEvaluator)eval;
					out.name("minProbability").value(pathEval.minProbability);
					out.name("failureType").value(pathEval.failureType.name());
					out.name("calcs").beginArray();
					for (RuptureProbabilityCalc calc : pathEval.calcs) {
						out.beginObject();
						out.name("class").value(calc.getClass().getName());
						out.name("value");
						gson.toJson(calc, calc.getClass(), out);
						out.endObject();
					}
					out.endArray();
					out.endObject();
				} else {
					gson.toJson(eval, eval.getClass(), out);
				}
				out.endObject();
			}
			out.endArray();
			
			out.endObject();
		}

		@Override
		public PlausibilityFilter read(JsonReader in) throws IOException {
			in.beginObject();
			
			Float fractPassThreshold = null;
			Boolean logicalOr = null;
			NucleationClusterEvaluator[] evaluators = null;
			
			while (in.hasNext()) {
				switch (in.nextName()) {
				case "fractPassThreshold":
					fractPassThreshold = (float)in.nextDouble();
					break;
				case "logicalOr":
					logicalOr = in.nextBoolean();
					break;
				case "evaluators":
					ArrayList<NucleationClusterEvaluator> list = new ArrayList<>();
					in.beginArray();
					while (in.hasNext()) {
						in.beginObject();
						
						Class<NucleationClusterEvaluator> type = null;
						NucleationClusterEvaluator eval = null;
						
						while (in.hasNext()) {
							switch (in.nextName()) {
							case "class":
								try {
									type = PlausibilityConfiguration.getDeclaredTypeClass(in.nextString());
								} catch (ClassNotFoundException e) {
									throw ExceptionUtils.asRuntimeException(e);
								}
								break;
							case "value":
								Preconditions.checkNotNull(type, "Class must preceed value in PathPlausibility JSON");
								if (type.equals(CumulativeProbPathEvaluator.class)) {
									in.beginObject();
									Float minProbability = null;
									PlausibilityResult failureType = null;
									RuptureProbabilityCalc[] calcs = null;
									while (in.hasNext()) {
										switch (in.nextName()) {
										case "minProbability":
											minProbability = (float)in.nextDouble();
											break;
										case "failureType":
											failureType = PlausibilityResult.valueOf(in.nextString());
											break;
										case "calcs":
											in.beginArray();
											List<RuptureProbabilityCalc> calcList = new ArrayList<>();
											while (in.hasNext()) {
												in.beginObject();
												Class<RuptureProbabilityCalc> calcType = null;
												RuptureProbabilityCalc calc = null;
												while (in.hasNext()) {
													switch (in.nextName()) {
													case "class":
														try {
															calcType = PlausibilityConfiguration.getDeclaredTypeClass(in.nextString());
														} catch (ClassNotFoundException e) {
															throw ExceptionUtils.asRuntimeException(e);
														}
														break;
													case "value":
														Preconditions.checkNotNull(calcType, "Class must preceed value in PathPlausibility JSON");
														calc = gson.fromJson(in, calcType);
														break;

													default:
														throw new IllegalStateException("Unexpected JSON field");
													}
												}
												Preconditions.checkNotNull(calc, "Calculator is null?");
												calcList.add(calc);
												in.endObject();
											}
											in.endArray();
											calcs = calcList.toArray(new RuptureProbabilityCalc[0]);
											break;

										default:
											throw new IllegalStateException("Unexpected JSON field");
										}
									}
									in.endObject();
									eval = new CumulativeProbPathEvaluator(minProbability, failureType, calcs);
								} else {
									eval = gson.fromJson(in, type);
								}
								break;

							default:
								throw new IllegalStateException("Unexpected JSON field");
							}
						}
						Preconditions.checkNotNull(eval, "Evaluator is null?");
						eval.init(connStrategy, distAzCalc);
						list.add(eval);
						
						in.endObject();
					}
					in.endArray();
					Preconditions.checkState(!list.isEmpty(), "No prob calcs?");
					evaluators = list.toArray(new NucleationClusterEvaluator[0]);
					break;

				default:
					throw new IllegalStateException("Unexpected JSON field");
				}
			}
			in.endObject();

			Preconditions.checkNotNull(fractPassThreshold, "fractPassThreshold not supplied");
			Preconditions.checkNotNull(logicalOr, "logicalOr not supplied");
			Preconditions.checkNotNull(evaluators, "evaluators not supplied");
			if (evaluators.length == 1 && evaluators[0] instanceof ScalarNucleationClusterEvaluator<?>)
				return new ScalarPathPlausibilityFilter<>(fractPassThreshold, (ScalarNucleationClusterEvaluator<?>)evaluators[0]);
			return new PathPlausibilityFilter(fractPassThreshold, logicalOr, evaluators);
		}
		
	}
	
//	public static void main(String[] args) throws ZipException, IOException, DocumentException {
//		// for profiling
//		File rupSetsDir = new File("/home/kevin/OpenSHA/UCERF4/rup_sets");
//		FaultSystemRupSet rupSet = FaultSystemIO.loadRupSet(
//				new File(rupSetsDir, "fm3_1_cmlAz_cffClusterPathPositive.zip"));
//		
//		SubSectStiffnessCalculator stiffnessCalc = new SubSectStiffnessCalculator(
//				rupSet.getFaultSectionDataList(), 2d, 3e4, 3e4, 0.5);
//		stiffnessCalc.setPatchAlignment(PatchAlignment.FILL_OVERLAP);
//		AggregatedStiffnessCache stiffnessCache = stiffnessCalc.getAggregationCache(StiffnessType.CFF);
//		File stiffnessCacheFile = new File(rupSetsDir, stiffnessCache.getCacheFileName());
//		if (stiffnessCacheFile.exists())
//			stiffnessCache.loadCacheFile(stiffnessCacheFile);
//		
//		AggregatedStiffnessCalculator aggCalc =
////				AggregatedStiffnessCalculator.buildMedianPatchSumSects(StiffnessType.CFF, stiffnessCalc);
//				AggregatedStiffnessCalculator.builder(StiffnessType.CFF, stiffnessCalc)
//				.flatten()
//				.process(AggregationMethod.MEDIAN)
//				.process(AggregationMethod.SUM)
////				.passthrough()
//				.process(AggregationMethod.SUM).get();
//		System.out.println("Aggregator: "+aggCalc);
//		PathPlausibilityFilter filter = new PathPlausibilityFilter(aggCalc, 0f);
//		
//		ClusterRupture largest = null;
//		for (ClusterRupture rup : rupSet.getClusterRuptures())
//			if (largest == null || rup.getTotalNumSects() > largest.getTotalNumSects())
//				largest = rup;
//		System.out.println("Benchmarking with a largest rupture ("+largest.getTotalNumSects()+" sects):\n\t"+largest);
////		int num = 1000000;
//		int num = 1;
//		boolean verbose = true;
//		Stopwatch watch = Stopwatch.createStarted();
//		for (int i=0; i<num; i++) {
//			if (i % 1000 == 0) {
//				double secs = watch.elapsed(TimeUnit.MILLISECONDS)/1000d;
//				double rate = i/secs;
//				System.out.println("processed "+i+" in "+(float)secs+" s:\t"+(float)rate+" per second");
//			}
//			filter.apply(largest, verbose);
//		}
//		watch.stop();
//		double secs = watch.elapsed(TimeUnit.MILLISECONDS)/1000d;
//		double rate = num/secs;
//		System.out.println("processed "+num+" in "+(float)secs+" s: "+(float)rate+" per second");
//	}

}
