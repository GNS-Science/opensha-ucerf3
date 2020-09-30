package org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl;

import java.util.ArrayList;
import java.util.List;

import org.opensha.sha.earthquake.faultSysSolution.ruptures.ClusterRupture;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.FaultSubsectionCluster;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.Jump;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.ScalarCoulombPlausibilityFilter;
import org.opensha.sha.faultSurface.FaultSection;
import org.opensha.sha.simulators.stiffness.RuptureCoulombResult;
import org.opensha.sha.simulators.stiffness.SubSectStiffnessCalculator;
import org.opensha.sha.simulators.stiffness.RuptureCoulombResult.RupCoulombQuantity;
import org.opensha.sha.simulators.stiffness.SubSectStiffnessCalculator.StiffnessAggregationMethod;

import com.google.common.collect.Range;

import scratch.UCERF3.inversion.laughTest.PlausibilityResult;

/**
 * This filter tests the net Coulomb compatibility of a rupture. For each participating section, it computes
 * Coulomb with all other sections as a source that section as the receiver. It then aggregates across all
 * sections according to the specified RupCoulombQuantity, e.g., RupCoulombQuantity.SUM_SECT_CFF to sum
 * the value across all subsections.
 * 
 * @author kevin
 *
 */
public class NetRuptureCoulombFilter implements ScalarCoulombPlausibilityFilter {
	
	private SubSectStiffnessCalculator stiffnessCalc;
	private StiffnessAggregationMethod aggMethod;
	private RupCoulombQuantity quantity;
	private float threshold;

	public NetRuptureCoulombFilter(SubSectStiffnessCalculator stiffnessCalc, StiffnessAggregationMethod aggMethod,
			RupCoulombQuantity quantity, float threshold) {
		super();
		this.stiffnessCalc = stiffnessCalc;
		this.aggMethod = aggMethod;
		this.quantity = quantity;
		this.threshold = threshold;
	}

	@Override
	public PlausibilityResult apply(ClusterRupture rupture, boolean verbose) {
		float val = getValue(rupture);
		PlausibilityResult result = val < threshold ?
				PlausibilityResult.FAIL_HARD_STOP : PlausibilityResult.PASS;
		if (verbose)
			System.out.println(getShortName()+": val="+val+", result="+result);
		return result;
	}

	@Override
	public PlausibilityResult testJump(ClusterRupture rupture, Jump newJump, boolean verbose) {
		float val = getValue(rupture, newJump);
		PlausibilityResult result = val < threshold ?
				PlausibilityResult.FAIL_HARD_STOP : PlausibilityResult.PASS;
		if (verbose)
			System.out.println(getShortName()+": val="+val+", result="+result);
		return result;
	}

	@Override
	public String getShortName() {
		return "NetRupCoulomb≥"+(float)threshold;
	}

	@Override
	public String getName() {
		return "Net Rupture Coulomb  ≥ "+(float)threshold;
	}

	@Override
	public Float getValue(ClusterRupture rupture) {
		if (rupture.getTotalNumSects() == 1)
			return 0f;
		return (float)new RuptureCoulombResult(rupture, stiffnessCalc, aggMethod).getValue(quantity);
	}

	@Override
	public Float getValue(ClusterRupture rupture, Jump newJump) {
		List<FaultSection> sects = new ArrayList<>();
		for (FaultSubsectionCluster cluster : rupture.getClustersIterable())
			sects.addAll(cluster.subSects);
		sects.addAll(newJump.toCluster.subSects);
		return (float)new RuptureCoulombResult(sects, stiffnessCalc, aggMethod).getValue(quantity);
	}

	@Override
	public Range<Float> getAcceptableRange() {
		return Range.atLeast(threshold);
	}

	@Override
	public SubSectStiffnessCalculator getStiffnessCalc() {
		return stiffnessCalc;
	}

}
