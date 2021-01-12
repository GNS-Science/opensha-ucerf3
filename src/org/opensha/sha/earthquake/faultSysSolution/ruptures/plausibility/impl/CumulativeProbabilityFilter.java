package org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.math3.stat.StatUtils;
import org.opensha.commons.data.Named;
import org.opensha.commons.data.function.EvenlyDiscretizedFunc;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.ClusterRupture;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.FaultSubsectionCluster;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.Jump;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.PlausibilityConfiguration;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.PlausibilityFilter;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.ScalarValuePlausibiltyFilter;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.strategies.ClusterConnectionStrategy;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.util.RupSetDiagnosticsPageGen.RakeType;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.util.RuptureTreeNavigator;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.util.SectionDistanceAzimuthCalculator;
import org.opensha.sha.faultSurface.FaultSection;
import org.opensha.sha.simulators.stiffness.AggregatedStiffnessCalculator;

import com.google.common.base.Preconditions;
import com.google.common.collect.Range;
import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import scratch.UCERF3.inversion.laughTest.PlausibilityResult;

public class CumulativeProbabilityFilter implements ScalarValuePlausibiltyFilter<Float> {
	
	public static interface RuptureProbabilityCalc extends Named {
		
		/**
		 * This computes the probability of this rupture occurring as defined, conditioned on
		 * it beginning at the first cluster in this rupture
		 * 
		 * @param rupture
		 * @return conditional probability of this rupture
		 */
		public double calcRuptureProb(ClusterRupture rupture);
		
		public default void init(ClusterConnectionStrategy connStrat, SectionDistanceAzimuthCalculator distAzCalc) {
			// do nothing
		}
	}
	
	public static abstract class JumpProbabilityCalc implements RuptureProbabilityCalc {
		
		/**
		 * This computes the probability of this jump occurring conditioned on the rupture
		 * up to that point having occurred, and relative to the rupture arresting rather
		 * than taking that jump.
		 * 
		 * @param fullRupture
		 * @param jump
		 * @return conditional jump probability
		 */
		public abstract double calcJumpProbability(ClusterRupture fullRupture, Jump jump);

		@Override
		public double calcRuptureProb(ClusterRupture rupture) {
			double prob = 1d;
			for (Jump jump : rupture.getJumpsIterable())
				prob *= calcJumpProbability(rupture, jump);
			return prob;
		}
		
	}
	
	public static class BiasiWesnousky2016CombJumpDistProb extends JumpProbabilityCalc {

		private double minJumpDist;
		private BiasiWesnousky2016SSJumpProb ssJumpProb;

		public BiasiWesnousky2016CombJumpDistProb() {
			this(1d);
		}
		
		public BiasiWesnousky2016CombJumpDistProb(double minJumpDist) {
			this.minJumpDist = minJumpDist;
			ssJumpProb = new BiasiWesnousky2016SSJumpProb(minJumpDist);
		}
		
		private boolean isStrikeSlip(FaultSection sect) {
			return RakeType.LEFT_LATERAL.isMatch(sect.getAveRake())
					|| RakeType.RIGHT_LATERAL.isMatch(sect.getAveRake());
		}
		
		public double getDistanceIndepentProb(RakeType type) {
			// Table 4 of Biasi & Wesnousky (2016)
			if (type == RakeType.REVERSE)
				return 0.62;
			if (type == RakeType.NORMAL)
				return 0.37;
			// generic dip slip or SS
			return 0.46;
		}

		@Override
		public double calcJumpProbability(ClusterRupture fullRupture, Jump jump) {
			if (jump.distance < minJumpDist)
				return 1d;
			RakeType type1 = RakeType.getType(jump.fromSection.getAveRake());
			RakeType type2 = RakeType.getType(jump.toSection.getAveRake());
			if (type1 == type2 && (type1 == RakeType.LEFT_LATERAL || type1 == RakeType.RIGHT_LATERAL))
				// only use distance-dependent model if both are SS
				return ssJumpProb.calcJumpProbability(fullRupture, jump);
			// average probabilities from each mechanism
			// TODO is this right? should we take the minimum or maximum? check with Biasi 
			return 0.5*(getDistanceIndepentProb(type1)+getDistanceIndepentProb(type2));
		}

		@Override
		public String getName() {
			return "BW16 JumpDist";
		}
		
	}
	
	public static double passingRatioToProb(double passingRatio) {
		return passingRatio/(passingRatio + 1);
	}
	
	public static double probToPassingRatio(double prob) {
		return -prob/(prob-1d);
	}
	
	public static class BiasiWesnousky2016SSJumpProb extends JumpProbabilityCalc {
		
		private double minJumpDist;
		
		public BiasiWesnousky2016SSJumpProb() {
			this(1d);
		}
		
		public BiasiWesnousky2016SSJumpProb(double minJumpDist) {
			this.minJumpDist = minJumpDist;
		}
		
		public double calcPassingRatio(double distance) {
			// this is the ratio of of times the rupture passes through a jump of this size
			// relative to the number of times it stops there
			return Math.max(0, 1.89 - 0.31*distance);
		}
		
		public double calcPassingProb(double distance) {
			return passingRatioToProb(calcPassingRatio(distance));
		}

		@Override
		public double calcJumpProbability(ClusterRupture fullRupture, Jump jump) {
			if (jump.distance < minJumpDist)
				return 1d;
			return calcPassingProb(jump.distance);
		}

		@Override
		public String getName() {
			return "BW16 SS JumpDist";
		}
		
	}
	
	public static final EvenlyDiscretizedFunc bw2017_ss_passRatio;
	static {
		// tabulated by eye from Figure 8c (10km bin size)
		// TODO: get exact values from Biasi
		bw2017_ss_passRatio = new EvenlyDiscretizedFunc(5d, 45d, 5);
		bw2017_ss_passRatio.set(5d,		2.7d);
		bw2017_ss_passRatio.set(15d,	1.35d);
		bw2017_ss_passRatio.set(25d,	1.3d);
		bw2017_ss_passRatio.set(35d,	0.1d);
		bw2017_ss_passRatio.set(45d,	0.08d);
	}
	
	public static class BiasiWesnousky2017JumpAzChangeProb extends JumpProbabilityCalc {
		
		private SectionDistanceAzimuthCalculator distAzCalc;

		public BiasiWesnousky2017JumpAzChangeProb(SectionDistanceAzimuthCalculator distAzCalc) {
			this.distAzCalc = distAzCalc;
		}

		@Override
		public double calcJumpProbability(ClusterRupture fullRupture, Jump jump) {
			RuptureTreeNavigator nav = fullRupture.getTreeNavigator();
			
			RakeType type1 = RakeType.getType(jump.fromSection.getAveRake());
			RakeType type2 = RakeType.getType(jump.toSection.getAveRake());
			if (type1 != type2)
				// only evaluate within mechanism
				// rely on other models for mechanism change probabilities
				return 1d;
			
			FaultSection before2 = jump.fromSection;
			FaultSection before1 = nav.getPredecessor(before2);
			if (before1 == null)
				return 1d;
			double beforeAz = distAzCalc.getAzimuth(before1, before2);
			FaultSection after1 = jump.toSection;
			double prob = 1d;
			for (FaultSection after2 : nav.getDescendants(after1)) {
				double afterAz = distAzCalc.getAzimuth(after1, after2);
				double diff = Math.abs(JumpAzimuthChangeFilter.getAzimuthDifference(beforeAz, afterAz));
				double passingRatio;
				if (type1 == RakeType.LEFT_LATERAL || type1 == RakeType.RIGHT_LATERAL) {
					// strike-slip case
					// will just grab the closest binned value
					// this extends the last bin uniformly to 180 degree differences, TODO confer with Biasi
					passingRatio = bw2017_ss_passRatio.getY(bw2017_ss_passRatio.getClosestXIndex(diff));
				} else {
					// not well defined, arbitrary choice here based loosely on Figure 8d
					// TODO: confer with Biasi
					if (diff < 60d)
						passingRatio = 2d;
					else
						passingRatio = 0.5d;
				}
				prob = Math.min(prob, passingRatioToProb(passingRatio));
			}
			return prob;
		}

		@Override
		public String getName() {
			return "BW17 AzChange";
		}
		
	}
	
	public static class BiasiWesnousky2017_SSJumpAzChangeProb extends JumpProbabilityCalc {
		
		private SectionDistanceAzimuthCalculator distAzCalc;

		public BiasiWesnousky2017_SSJumpAzChangeProb(SectionDistanceAzimuthCalculator distAzCalc) {
			this.distAzCalc = distAzCalc;
		}

		@Override
		public double calcJumpProbability(ClusterRupture fullRupture, Jump jump) {
			RuptureTreeNavigator nav = fullRupture.getTreeNavigator();
			
			RakeType type1 = RakeType.getType(jump.fromSection.getAveRake());
			RakeType type2 = RakeType.getType(jump.toSection.getAveRake());
			if (type1 != type2)
				// only evaluate within mechanism
				// rely on other models for mechanism change probabilities
				return 1d;
			if (type1 != RakeType.RIGHT_LATERAL && type1 != RakeType.LEFT_LATERAL)
				// SS only
				return 1d;
			
			FaultSection before2 = jump.fromSection;
			FaultSection before1 = nav.getPredecessor(before2);
			if (before1 == null)
				return 1d;
			double beforeAz = distAzCalc.getAzimuth(before1, before2);
			FaultSection after1 = jump.toSection;
			double prob = 1d;
			for (FaultSection after2 : nav.getDescendants(after1)) {
				double afterAz = distAzCalc.getAzimuth(after1, after2);
				double diff = Math.abs(JumpAzimuthChangeFilter.getAzimuthDifference(beforeAz, afterAz));
				// will just grab the closest binned value
				// this extends the last bin uniformly to 180 degree differences, TODO confer with Biasi
				double passingRatio = bw2017_ss_passRatio.getY(bw2017_ss_passRatio.getClosestXIndex(diff));
				prob = Math.min(prob, passingRatioToProb(passingRatio));
			}
			return prob;
		}

		@Override
		public String getName() {
			return "BW17 SS AzChange";
		}
		
	}
	
	public static final double bw2017_mech_change_prob = 4d/75d;
	
	public static class BiasiWesnousky2017MechChangeProb extends JumpProbabilityCalc {

		public BiasiWesnousky2017MechChangeProb() {
		}

		@Override
		public double calcJumpProbability(ClusterRupture fullRupture, Jump jump) {
			double rake1 = jump.fromSection.getAveRake();
			double rake2 = jump.toSection.getAveRake();
			if ((float)rake1 == (float)rake2)
				// no mechanism change
				return 1d;
			RakeType type1 = RakeType.getType(rake1);
			RakeType type2 = RakeType.getType(rake2);
			if (type1 == type2)
				// no mechanism change
				return 1d;
			// only 4 of 75 ruptures had a mechanism change of any type
			// TODO consult Biasi
			return bw2017_mech_change_prob;
		}

		@Override
		public String getName() {
			return "BW17 MechChange";
		}
		
	}
	
	public static RuptureProbabilityCalc[] getPrefferedBWCalcs(SectionDistanceAzimuthCalculator distAzCalc) {
		return new RuptureProbabilityCalc[] {
				new BiasiWesnousky2016CombJumpDistProb(),
				new BiasiWesnousky2017JumpAzChangeProb(distAzCalc),
				new BiasiWesnousky2017MechChangeProb()
		};
	}
	
	public static class RelativeCoulombProb extends JumpProbabilityCalc {
		
		private AggregatedStiffnessCalculator aggCalc;
		private transient ClusterConnectionStrategy connStrat;
		private boolean fullRuptureSource;
		private boolean allowNegative;
		
		private transient Map<Integer, FaultSubsectionCluster> fullClustersMap;

		public RelativeCoulombProb(AggregatedStiffnessCalculator aggCalc, ClusterConnectionStrategy connStrat,
				boolean fullRuptureSource, boolean allowNegative) {
			this.aggCalc = aggCalc;
			this.connStrat = connStrat;
			this.fullRuptureSource = fullRuptureSource;
			this.allowNegative = allowNegative;
		}

		@Override
		public void init(ClusterConnectionStrategy connStrat, SectionDistanceAzimuthCalculator distAzCalc) {
			this.connStrat = connStrat;
		}

		@Override
		public String getName() {
			String name = "Rel CFF";
			if (fullRuptureSource)
				name += ", Full Rup Src";
			if (allowNegative)
				name += ", Allow Neg";
			return name;
		}
		
		private void checkInitFullClusters() {
			if (fullClustersMap == null) {
				synchronized (this) {
					if (fullClustersMap == null) {
						Map<Integer, FaultSubsectionCluster> map = new HashMap<>();
						for (FaultSubsectionCluster cluster : connStrat.getClusters())
							map.put(cluster.parentSectionID, cluster);
						this.fullClustersMap = map;
					}
				}
			}
			return;
		}
		
		private List<FaultSection> getSectsBefore(ClusterRupture rupture, Jump jump) {
			List<FaultSection> sectsBefore = new ArrayList<>();
			for (FaultSubsectionCluster cluster : rupture.clusters) {
				if (jump.toCluster.equals(cluster))
					break;
				sectsBefore.addAll(cluster.subSects);
			}
			for (Jump splayJump : rupture.splays.keySet()) {
				if (!splayJump.equals(jump))
					sectsBefore.addAll(getSectsBefore(rupture.splays.get(splayJump), jump));
			}
			return sectsBefore;
		}

		@Override
		public double calcJumpProbability(ClusterRupture fullRupture, Jump jump) {
			List<FaultSection> sources;
			if (fullRuptureSource) {
				sources = getSectsBefore(fullRupture, jump);
				Preconditions.checkState(!sources.isEmpty() && sources.size() >= jump.fromCluster.subSects.size());
			} else {
				sources = jump.fromCluster.subSects;
			}
			double myVal = aggCalc.calc(sources, jump.toCluster.subSects);
			if (!allowNegative && myVal < 0)
				return 0d;
			
			checkInitFullClusters();
			FaultSubsectionCluster fullFrom = fullClustersMap.get(jump.fromCluster.parentSectionID);
			Preconditions.checkNotNull(fullFrom);
			List<FaultSubsectionCluster> targetClusters = new ArrayList<>();
			if (fullFrom.subSects.size() > jump.fromCluster.subSects.size()
					&& !jump.fromCluster.endSects.contains(jump.fromSection)) {
				// need to add continuing on this cluster as a possible "jump"
				int fromSectIndex = fullFrom.subSects.indexOf(jump.fromSection);
				Preconditions.checkState(fromSectIndex >=0, "From section not found in full cluster?");
				if (fromSectIndex < fullFrom.subSects.size()-1) {
					// try going forward in list
					List<FaultSection> possibleSects = new ArrayList<>();
					for (int i=fromSectIndex+1; i<fullFrom.subSects.size(); i++) {
						FaultSection sect = fullFrom.subSects.get(i);
						if (jump.fromCluster.contains(sect))
							break;
						possibleSects.add(sect);
					}
					if (!possibleSects.isEmpty())
						targetClusters.add(new FaultSubsectionCluster(possibleSects));
				}
				
				if (fromSectIndex > 0) {
					// try going backward in list
					List<FaultSection> possibleSects = new ArrayList<>();
//					for (int i=fromSectIndex+1; i<fullFrom.subSects.size(); i++) {
					for (int i=fromSectIndex; --i>=0;) {
						FaultSection sect = fullFrom.subSects.get(i);
						if (jump.fromCluster.contains(sect))
							break;
						possibleSects.add(sect);
					}
					if (!possibleSects.isEmpty())
						targetClusters.add(new FaultSubsectionCluster(possibleSects));
				}
			}
			// now add possible jumps to other clusters
			for (Jump possible : jump.fromCluster.getConnections(jump.fromSection)) {
				if (possible.toCluster.parentSectionID != jump.toCluster.parentSectionID)
					targetClusters.add(jump.toCluster);
			}
			List<Double> targetVals = new ArrayList<>();
			double normalization = Math.min(myVal, 0d);
			for (FaultSubsectionCluster targetCluster : targetClusters) {
				double val = aggCalc.calc(sources, targetCluster.subSects);
				if (val < 0) {
					if (allowNegative && myVal < 0) {
						normalization = Math.min(val, normalization);
					} else {
						continue;
					}
				}
				targetVals.add(val);
			}
			if (targetVals.isEmpty())
				// no alternatives
				return 1d;
			if (normalization != 0d) {
				myVal -= normalization;
				for (int i=0; i<targetVals.size(); i++)
					targetVals.set(i, targetVals.get(i) - normalization);
			}
			double sum = myVal;
			for (double val : targetVals)
				sum += val;
			Preconditions.checkState((float)sum >= 0f,
					"Bad CFF sum = %s.\n\tnormalization: %s\n\tmyVal: %s\n\tallVals (after norm): %s",
					sum, normalization, myVal, targetVals);
			if ((float)sum == 0f)
				return 0d;
			
			double prob = myVal / sum;
			Preconditions.checkState(prob >= 0d && prob <= 1d,
					"Bad CFF prob! P = %s / %s = %s.\n\tnormalization: %s\n\tallVals (after norm): %s",
					myVal, sum, prob, normalization, targetVals);
			return prob;
		}
		
	}
	
	private float minProbability;
	private RuptureProbabilityCalc[] calcs;
	
	public CumulativeProbabilityFilter(float minProbability, RuptureProbabilityCalc... calcs) {
		Preconditions.checkArgument(minProbability > 0d && minProbability <= 1d,
				"Minimum probability (%s) not in the range (0,1]", minProbability);
		this.minProbability = minProbability;
		Preconditions.checkArgument(calcs.length > 0);
		this.calcs = calcs;
	}

	@Override
	public PlausibilityResult apply(ClusterRupture rupture, boolean verbose) {
		float prob = getValue(rupture, verbose);
		if (verbose)
			System.out.println(getShortName()+": final prob="+prob+", pass="+(prob >= minProbability));
		if ((float)prob >= minProbability)
			return PlausibilityResult.PASS;
		return PlausibilityResult.FAIL_HARD_STOP;
	}

	@Override
	public String getShortName() {
		if (calcs.length > 1)
			return "CumProb≥"+minProbability;
		return "P("+calcs[0].getName().replaceAll(" ", "")+")≥"+minProbability;
	}

	@Override
	public String getName() {
		if (calcs.length > 1)
			return "Cumulative Probability Filter ≥"+minProbability;
		return calcs[0].getName()+" ≥"+minProbability;
	}

	@Override
	public Float getValue(ClusterRupture rupture) {
		return getValue(rupture, false);
	}

	public Float getValue(ClusterRupture rupture, boolean verbose) {
		double prob = 1d;
		for (RuptureProbabilityCalc calc : calcs) {
			double indvProb = calc.calcRuptureProb(rupture);
			if (verbose)
				System.out.println("\t"+calc.getName()+": P="+indvProb);
			Preconditions.checkState(indvProb >= 0d && indvProb <= 1d,
					"Bad probability for %s: %s\n\tRupture: %s", indvProb, calc.getName(), rupture);
			prob *= indvProb;
		}
		return (float)prob;
	}

	@Override
	public Range<Float> getAcceptableRange() {
		return Range.closed(minProbability, 1f);
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
			Preconditions.checkState(value instanceof CumulativeProbabilityFilter);
			CumulativeProbabilityFilter filter = (CumulativeProbabilityFilter)value;
			out.beginObject();
			
			out.name("minProbability").value(filter.minProbability);
			out.name("calcs").beginArray();
			for (RuptureProbabilityCalc calc : filter.calcs) {
				out.beginObject();
				out.name("class").value(calc.getClass().getName());
				out.name("value");
				gson.toJson(calc, calc.getClass(), out);
				out.endObject();
			}
			out.endArray();
			
			out.endObject();
		}

		@Override
		public PlausibilityFilter read(JsonReader in) throws IOException {
			in.beginObject();
			
			Float minProbability = null;
			RuptureProbabilityCalc[] calcs = null;
			
			while (in.hasNext()) {
				switch (in.nextName()) {
				case "minProbability":
					minProbability = (float)in.nextDouble();
					break;
				case "calcs":
					ArrayList<RuptureProbabilityCalc> list = new ArrayList<>();
					in.beginArray();
					while (in.hasNext()) {
						in.beginObject();
						
						Class<RuptureProbabilityCalc> type = null;
						RuptureProbabilityCalc calc = null;
						
						while (in.hasNext()) {
							switch (in.nextName()) {
							case "class":
								type = PlausibilityConfiguration.getDeclaredTypeClass(in.nextString());
								break;
							case "value":
								Preconditions.checkNotNull(type, "Class must preceed value in ProbCalc JSON");
								calc = gson.fromJson(in, type);
								break;

							default:
								throw new IllegalStateException("Unexpected JSON field");
							}
						}
						Preconditions.checkNotNull(calc, "Penalty is null?");
						calc.init(connStrategy, distAzCalc);
						list.add(calc);
						
						in.endObject();
					}
					in.endArray();
					Preconditions.checkState(!list.isEmpty(), "No prob calcs?");
					calcs = list.toArray(new RuptureProbabilityCalc[0]);
					break;

				default:
					throw new IllegalStateException("Unexpected JSON field");
				}
			}
			in.endObject();
			
			Preconditions.checkNotNull(minProbability, "threshold not supplied");
			Preconditions.checkNotNull(calcs, "penalties not supplied");
			return new CumulativeProbabilityFilter(minProbability, calcs);
		}
		
	}
	
	public static void main(String[] args) {
		BiasiWesnousky2016SSJumpProb jumpProb = new BiasiWesnousky2016SSJumpProb(0d);
		
		for (double len=0; len<=10d; len++) {
			System.out.println("Length="+(float)len+"\tr="+(float)jumpProb.calcPassingRatio(len)
				+"\tp="+(float)jumpProb.calcPassingProb(len));
		}
		
		System.out.println("\nSS Azimuth Passing Ratios/Probs");
		for (int i=0; i<bw2017_ss_passRatio.size(); i++) {
			double x = bw2017_ss_passRatio.getX(i);
			double ratio = bw2017_ss_passRatio.getY(i);
			double prob = passingRatioToProb(ratio);
			System.out.println("azDiff="+(float)x+"\tr="+(float)ratio+"\tp="+(float)prob);
		}
	}

}
