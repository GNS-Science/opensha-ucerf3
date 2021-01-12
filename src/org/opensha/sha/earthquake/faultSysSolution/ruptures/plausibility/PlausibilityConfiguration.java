package org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import org.opensha.commons.util.ExceptionUtils;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.ClusterRupture;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.Jump;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.PlausibilityFilter.PlausibilityFilterTypeAdapter;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl.ClusterCoulombCompatibilityFilter;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl.ClusterPathCoulombCompatibilityFilter;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl.CumulativeAzimuthChangeFilter;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl.CumulativePenaltyFilter;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl.CumulativePenaltyFilter.Penalty;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl.CumulativeProbabilityFilter;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl.CumulativeProbabilityFilter.RelativeCoulombProb;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl.CumulativeProbabilityFilter.RuptureProbabilityCalc;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl.CumulativeRakeChangeFilter;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl.JumpAzimuthChangeFilter;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl.JumpAzimuthChangeFilter.AzimuthCalc;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl.MinSectsPerParentFilter;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl.NetClusterCoulombFilter;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl.NetRuptureCoulombFilter;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl.NumClustersFilter;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl.ParentCoulombCompatibilityFilter;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl.ParentCoulombCompatibilityFilter.Directionality;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl.SplayLengthFilter;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl.TotalAzimuthChangeFilter;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl.U3CompatibleCumulativeRakeChangeFilter;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl.U3CoulombJunctionFilter;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.strategies.ClusterConnectionStrategy;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.strategies.ClusterConnectionStrategy.ConnStratTypeAdapter;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.strategies.DistCutoffClosestSectClusterConnectionStrategy;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.strategies.UCERF3ClusterConnectionStrategy;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.util.SectionDistanceAzimuthCalculator;
import org.opensha.sha.faultSurface.FaultSection;
import org.opensha.sha.simulators.stiffness.AggregatedStiffnessCalculator;
import org.opensha.sha.simulators.stiffness.SubSectStiffnessCalculator;
import org.opensha.sha.simulators.stiffness.AggregatedStiffnessCalculator.AggregationMethod;
import org.opensha.sha.simulators.stiffness.SubSectStiffnessCalculator.PatchAlignment;
import org.opensha.sha.simulators.stiffness.SubSectStiffnessCalculator.StiffnessType;

import com.google.common.base.Preconditions;
import com.google.common.collect.BoundType;
import com.google.common.collect.Range;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import scratch.UCERF3.enumTreeBranches.DeformationModels;
import scratch.UCERF3.enumTreeBranches.FaultModels;
import scratch.UCERF3.inversion.coulomb.CoulombRates;
import scratch.UCERF3.inversion.coulomb.CoulombRatesTester;
import scratch.UCERF3.inversion.coulomb.CoulombRatesTester.TestType;
import scratch.UCERF3.inversion.laughTest.PlausibilityResult;
import scratch.UCERF3.utils.DeformationModelFetcher;

public class PlausibilityConfiguration {
	
	public static PlausibilityConfiguration getUCERF3(
			List<? extends FaultSection> subSects, SectionDistanceAzimuthCalculator distAzCalc,
			FaultModels fm) throws IOException {
		return getUCERF3(subSects, distAzCalc, CoulombRates.loadUCERF3CoulombRates(fm));
	}
	
	public static PlausibilityConfiguration getUCERF3(
			List<? extends FaultSection> subSects, SectionDistanceAzimuthCalculator distAzCalc,
			CoulombRates coulombRates) {
		ClusterConnectionStrategy connectionStrategy = new UCERF3ClusterConnectionStrategy(
				subSects, distAzCalc, 5d, coulombRates);
		return builder(connectionStrategy, distAzCalc).maxSplays(0).u3All(coulombRates).build();
	}
	
	private List<PlausibilityFilter> filters;
	private int maxNumSplays;
	private ClusterConnectionStrategy connectionStrategy;
	private SectionDistanceAzimuthCalculator distAzCalc;

	public PlausibilityConfiguration(List<PlausibilityFilter> filters, int maxNumSplays,
			ClusterConnectionStrategy connectionStrategy, SectionDistanceAzimuthCalculator distAzCalc) {
		this.filters = filters;
		this.maxNumSplays = maxNumSplays;
		this.connectionStrategy = connectionStrategy;
		this.distAzCalc = distAzCalc;
	}
	
	public List<PlausibilityFilter> getFilters() {
		return filters;
	}

	public int getMaxNumSplays() {
		return maxNumSplays;
	}

	public ClusterConnectionStrategy getConnectionStrategy() {
		return connectionStrategy;
	}

	public SectionDistanceAzimuthCalculator getDistAzCalc() {
		return distAzCalc;
	}
	
	/*
	 * Builder for convenience
	 */

	public static class Builder {
		
		private ClusterConnectionStrategy connectionStrategy;
		private SectionDistanceAzimuthCalculator distAzCalc;
		private int maxSplays;
		private List<PlausibilityFilter> filters;

		private Builder(ClusterConnectionStrategy connectionStrategy,
				SectionDistanceAzimuthCalculator distAzCalc) {
			this.connectionStrategy = connectionStrategy;
			this.distAzCalc = distAzCalc;
			this.maxSplays = 0;
			this.filters = new ArrayList<>();
		}
		
		public Builder maxSplays(int maxSplays) {
			this.maxSplays = maxSplays;
			return this;
		}
		
		public Builder add(PlausibilityFilter filter) {
			this.filters.add(filter);
			return this;
		}
		
		/**
		 * Adds all UCERF3 plausibility filters
		 * @param coulombRates
		 * @return
		 */
		public Builder u3All(CoulombRates coulombRates) {
			u3Azimuth();
			u3Cumulatives();
			minSectsPerParent(2, true, true);
			u3Coulomb(coulombRates);
			return this;
		}
		
		/**
		 * Adds the UCERF3 jump and total (start-to-end, not cumulative) azimuth change filters
		 * @return
		 */
		public Builder u3Azimuth() {
			AzimuthCalc u3AzCalc = new JumpAzimuthChangeFilter.UCERF3LeftLateralFlipAzimuthCalc(distAzCalc);
			filters.add(new JumpAzimuthChangeFilter(u3AzCalc, 60f));
			filters.add(new TotalAzimuthChangeFilter(u3AzCalc, 60f, true, true));
			return this;
		}
		
		/**
		 * Adds the UCERF3 cumulative azimuth and rake filters. Note that the latter suffers from
		 * floating point precision issues and should not be used unless reproducing UCERF3 exactly
		 * @return
		 */
		public Builder u3Cumulatives() {
			cumulativeAzChange(new JumpAzimuthChangeFilter.SimpleAzimuthCalc(distAzCalc), 560f);
			filters.add(new U3CompatibleCumulativeRakeChangeFilter(180d));
			return this;
		}
		
		public Builder u3Coulomb(CoulombRates coulombRates) {
			CoulombRatesTester coulombTester = new CoulombRatesTester(
					TestType.COULOMB_STRESS, 0.04, 0.04, 1.25d, true, true);
			filters.add(new U3CoulombJunctionFilter(coulombTester, coulombRates));
			return this;
		}
		
		public Builder minSectsPerParent(int minPerParent, boolean allowIfNoDirect, boolean allowChained) {
			filters.add(new MinSectsPerParentFilter(minPerParent, allowIfNoDirect,
					allowChained, connectionStrategy));
			return this;
		}
		
		public Builder clusterCoulomb(AggregatedStiffnessCalculator aggCalc, float threshold) {
			filters.add(new ClusterCoulombCompatibilityFilter(aggCalc, threshold));
			return this;
		}
		
		public Builder clusterPathCoulomb(AggregatedStiffnessCalculator aggCalc, float threshold) {
			filters.add(new ClusterPathCoulombCompatibilityFilter(aggCalc, threshold));
			return this;
		}
		
		public Builder clusterPathCoulomb(AggregatedStiffnessCalculator aggCalc, Range<Float> acceptableRange) {
			filters.add(new ClusterPathCoulombCompatibilityFilter(aggCalc, acceptableRange));
			return this;
		}
		
		public Builder clusterPathCoulomb(AggregatedStiffnessCalculator aggCalc, Range<Float> acceptableRange,
				float fractPathsThreshold, boolean failFuturePossible) {
			filters.add(new ClusterPathCoulombCompatibilityFilter(
					aggCalc, acceptableRange, fractPathsThreshold, failFuturePossible));
			return this;
		}
		
		public Builder parentCoulomb(AggregatedStiffnessCalculator aggCalc, float threshold, Directionality directionality) {
			filters.add(new ParentCoulombCompatibilityFilter(aggCalc, threshold, directionality));
			return this;
		}
		
		public Builder netRupCoulomb(AggregatedStiffnessCalculator aggCalc, float threshold) {
			filters.add(new NetRuptureCoulombFilter(aggCalc, threshold));
			return this;
		}
		
		public Builder netRupCoulomb(AggregatedStiffnessCalculator aggCalc, Range<Float> acceptableRange) {
			filters.add(new NetRuptureCoulombFilter(aggCalc, acceptableRange));
			return this;
		}
		
		public Builder netClusterCoulomb(AggregatedStiffnessCalculator aggCalc, float threshold) {
			filters.add(new NetClusterCoulombFilter(aggCalc, threshold));
			return this;
		}
		
		public Builder cumulativeRakeChange(float threshold) {
			filters.add(new CumulativeRakeChangeFilter(threshold));
			return this;
		}
		
		public Builder cumulativeAzChange(float threshold) {
			return cumulativeAzChange(new JumpAzimuthChangeFilter.SimpleAzimuthCalc(distAzCalc), threshold);
		}
		
		public Builder cumulativeAzChange(AzimuthCalc azCalc, float threshold) {
			filters.add(new CumulativeAzimuthChangeFilter(azCalc, threshold));
			return this;
		}
		
		public Builder jumpAzChange(AzimuthCalc azCalc, float threshold) {
			filters.add(new JumpAzimuthChangeFilter(azCalc, threshold));
			return this;
		}
		
		public Builder totAzChange(AzimuthCalc azCalc, float threshold, boolean multiFaultOnly,
				boolean testFullEnd) {
			filters.add(new TotalAzimuthChangeFilter(azCalc, threshold, multiFaultOnly, testFullEnd));
			return this;
		}
		
		/**
		 * 
		 * @param maxLen maximum splay length
		 * @param isFractOfMain if true, maxLen is a fractional length of the primary rupture
		 * @param totalAcrossSplays if true, maxLen is applied as a sum of all splays
		 */
		public Builder splayLength(double maxLen, boolean isFractOfMain, boolean totalAcrossSplays) {
			filters.add(new SplayLengthFilter(maxLen, isFractOfMain, totalAcrossSplays));
			return this;
		}

		
		public Builder cumulativePenalty(float threshold, boolean noDoubleCount, Penalty... penalties) {
			filters.add(new CumulativePenaltyFilter(threshold, noDoubleCount, penalties));
			return this;
		}
		
		public Builder cumulativeProbability(float minProbability, RuptureProbabilityCalc... calcs) {
			filters.add(new CumulativeProbabilityFilter(minProbability, calcs));
			return this;
		}
		
		public Builder maxNumClusters(int maxNumClusters) {
			filters.add(new NumClustersFilter(maxNumClusters));
			return this;
		}
		
		public PlausibilityConfiguration build() {
			return new PlausibilityConfiguration(filters, maxSplays, connectionStrategy, distAzCalc);
		}
	}
	
	public static Builder builder(ClusterConnectionStrategy connectionStrategy,
			SectionDistanceAzimuthCalculator distAzCalc) {
		return new Builder(connectionStrategy, distAzCalc);
	}
	
	public static Builder builder(ClusterConnectionStrategy connectionStrategy,
			List<? extends FaultSection> subSects) {
		return new Builder(connectionStrategy, new SectionDistanceAzimuthCalculator(subSects));
	}
	
	/*
	 * JSON [de]serialization
	 */
	
	public String toJSON() {
		Gson gson = buildGson(connectionStrategy.getSubSections());
		return gson.toJson(this);
	}
	
	public void writeJSON(File jsonFile) throws IOException {
		Gson gson = buildGson(connectionStrategy.getSubSections());
		FileWriter fw = new FileWriter(jsonFile);
		gson.toJson(this, fw);
		fw.write("\n");
		fw.close();
	}
	
	public static PlausibilityConfiguration readJSON(File jsonFile, List<? extends FaultSection> subSects)
			throws IOException {
		BufferedReader reader = new BufferedReader(new FileReader(jsonFile));
		return readJSON(reader, subSects);
	}
	
	public static PlausibilityConfiguration readJSON(String json, List<? extends FaultSection> subSects) {
		return readJSON(new StringReader(json), subSects);
	}
	
	public static PlausibilityConfiguration readJSON(Reader json, List<? extends FaultSection> subSects) {
		Gson gson = buildGson(subSects);
		PlausibilityConfiguration conf = gson.fromJson(json, PlausibilityConfiguration.class);
		try {
			json.close();
		} catch (IOException e) {}
		return conf;
	}
	
	public String filtersToJSON(List<PlausibilityFilter> filters) {
		List<PlausibilityFilterRecord> records = new ArrayList<>();
		for (PlausibilityFilter filter : filters)
			records.add(new PlausibilityFilterRecord(filter));
		Gson gson = buildGson(connectionStrategy.getSubSections(), distAzCalc, connectionStrategy);
		return gson.toJson(records);
	}
	
	public void writeFiltersJSON(File jsonFile) throws IOException {
		List<PlausibilityFilterRecord> records = new ArrayList<>();
		for (PlausibilityFilter filter : filters)
			records.add(new PlausibilityFilterRecord(filter));
		Gson gson = buildGson(connectionStrategy.getSubSections(), distAzCalc, connectionStrategy);
		FileWriter fw = new FileWriter(jsonFile);
		gson.toJson(records, fw);
		fw.write("\n");
		fw.close();
	}
	
	public static List<PlausibilityFilter> readFiltersJSON(File jsonFile, ClusterConnectionStrategy connStrat,
			SectionDistanceAzimuthCalculator distAzCalc)
			throws IOException {
		BufferedReader reader = new BufferedReader(new FileReader(jsonFile));
		return readFiltersJSON(reader, connStrat, distAzCalc);
	}
	
	public static List<PlausibilityFilter> readFiltersJSON(String json, ClusterConnectionStrategy connStrat,
			SectionDistanceAzimuthCalculator distAzCalc) {
		return readFiltersJSON(new StringReader(json), connStrat, distAzCalc);
	}
	
	public static List<PlausibilityFilter> readFiltersJSON(Reader json, ClusterConnectionStrategy connStrat,
			SectionDistanceAzimuthCalculator distAzCalc) {
		Gson gson = buildGson(connStrat.getSubSections(), distAzCalc, connStrat);
		Type listType = new TypeToken<List<PlausibilityFilterRecord>>(){}.getType();
		List<PlausibilityFilterRecord> records = gson.fromJson(json, listType);
		List<PlausibilityFilter> filters = new ArrayList<>();
		for (PlausibilityFilterRecord record : records)
			filters.add(record.filter);
		try {
			json.close();
		} catch (IOException e) {}
		return filters;
	}
	
	private static Gson prevGson;
	private static List<? extends FaultSection> prevSubSects;
	
	private synchronized static Gson buildGson(List<? extends FaultSection> subSects) {
		if (prevGson != null && prevSubSects != null && prevSubSects.size() == subSects.size()) {
			// see if we can reuse
//			System.out.println("Lets se if we can reuse Gson....");
			boolean match = true;
			for (int s=0; s<subSects.size(); s++) {
				FaultSection s1 = subSects.get(s);
				FaultSection s2 = prevSubSects.get(s);
				if (s1.getSectionId() != s2.getSectionId()
						|| s1.getParentSectionId() != s2.getParentSectionId()) {
//					System.out.println("Nope:\n\t"+s1+"\n\t"+s2);
					match = false;
					break;
				}
			}
			if (match) {
				System.out.println("Reusing PlausibilityConfiguration Gson instance");
				return prevGson;
			}
		}
		
		Gson gson = buildGson(subSects, null, null);
		
		prevGson = gson;
		prevSubSects = subSects;
		
		return gson;
	}
	
	private static Gson buildGson(List<? extends FaultSection> subSects,
			SectionDistanceAzimuthCalculator distAzCalc, ClusterConnectionStrategy connStrat) {
		GsonBuilder builder = new GsonBuilder();
		builder.setPrettyPrinting();

		ConnStratTypeAdapter connStratAdapter = new ConnStratTypeAdapter(subSects);
		builder.registerTypeHierarchyAdapter(ClusterConnectionStrategy.class, connStratAdapter);
		builder.registerTypeHierarchyAdapter(FaultSection.class, new FaultSectTypeAdapter(subSects));
		if (distAzCalc == null)
			distAzCalc = new SectionDistanceAzimuthCalculator(subSects);
		DistAzCalcTypeAdapter distAzAdapter = new DistAzCalcTypeAdapter(distAzCalc);
		builder.registerTypeAdapter(SectionDistanceAzimuthCalculator.class, distAzAdapter);
		PlausibilityConfigTypeAdapter configAdapter = new PlausibilityConfigTypeAdapter(
				connStratAdapter, distAzAdapter);
		builder.registerTypeAdapter(PlausibilityConfiguration.class, configAdapter);
		builder.registerTypeAdapter(AzimuthCalc.class,
				new JumpAzimuthChangeFilter.AzimuthCalcTypeAdapter(distAzCalc));
		builder.registerTypeAdapter(SubSectStiffnessCalculator.class,
				new SubSectStiffnessTypeAdapter(subSects));
		builder.registerTypeAdapter(CoulombRates.class,
				new CoulombRates.Adapter());
		PlausibilityFilterAdapter filterAdapter = new PlausibilityFilterAdapter(
				null, connStrat, distAzCalc);
		builder.registerTypeHierarchyAdapter(PlausibilityFilterRecord.class, filterAdapter);
		builder.registerTypeAdapter(TypeToken.getParameterized(Range.class, Float.class).getType(), new FloatRangeTypeAdapter());
		Gson gson = builder.create();
		configAdapter.setGson(gson);
		filterAdapter.setGson(gson);
		
		return gson;
	}
	
	/*
	 * Custom TypeAdapter instances
	 */
	
	private static class PlausibilityConfigTypeAdapter extends TypeAdapter<PlausibilityConfiguration> {

		private ConnStratTypeAdapter connStratAdapter;
		private DistAzCalcTypeAdapter distAzAdapter;
		private Gson gson;

		public PlausibilityConfigTypeAdapter(ConnStratTypeAdapter connStratAdapter,
				DistAzCalcTypeAdapter distAzAdapter) {
			this.connStratAdapter = connStratAdapter;
			this.distAzAdapter = distAzAdapter;
		}
		
		public void setGson(Gson gson) {
			this.gson = gson;
		}

		@Override
		public void write(JsonWriter out, PlausibilityConfiguration config) throws IOException {
			out.beginObject();
			
			out.name("connectionStrategy");
			connStratAdapter.write(out, config.getConnectionStrategy());
			out.name("maxNumSplays").value(config.getMaxNumSplays());
			out.name("filters").beginArray(); // [
			PlausibilityFilterAdapter adapter = new PlausibilityFilterAdapter(
					gson, config.getConnectionStrategy(), config.getDistAzCalc());
			
			for (PlausibilityFilter filter : config.filters)
				adapter.write(out, new PlausibilityFilterRecord(filter));
			
			out.endArray(); // ]
			
			out.endObject();
		}

		@Override
		public PlausibilityConfiguration read(JsonReader in) throws IOException {
			in.beginObject();
			
			Integer maxNumSplays = null;
			ClusterConnectionStrategy connectionStrategy = null;
			List<PlausibilityFilter> filters = null;
			
			while (in.hasNext()) {
				switch (in.nextName()) {
				case "connectionStrategy":
					connectionStrategy = connStratAdapter.read(in);
					break;
				case "maxNumSplays":
					maxNumSplays = in.nextInt();
					break;
				case "filters":
					Preconditions.checkNotNull(connectionStrategy,
							"Connection strategy must be before filters in JSON");
					PlausibilityFilterAdapter adapter = new PlausibilityFilterAdapter(
							gson, connectionStrategy, distAzAdapter.distAzCalc);
					
					filters = new ArrayList<>();
					
					in.beginArray();
					
					while (in.hasNext()) {
						PlausibilityFilter filter = adapter.read(in).filter;
						
						Preconditions.checkNotNull(filter, "Filter not found in JSON object");
						filters.add(filter);
					}
					
					in.endArray();
					break;

				default:
					break;
				}
			}
			
			in.endObject();
			return new PlausibilityConfiguration(filters, maxNumSplays,
					connectionStrategy, distAzAdapter.distAzCalc);
		}
		
	}
	
	/**
	 * wrapper calss for a plausibility filter so that we can specify a top level type adapter
	 * and still use default gson serialization on individual filters themselves
	 * @author kevin
	 *
	 */
	private static class PlausibilityFilterRecord {
		private final PlausibilityFilter filter;

		public PlausibilityFilterRecord(PlausibilityFilter filter) {
			super();
			this.filter = filter;
		}
	}
	
	private static class PlausibilityFilterAdapter extends TypeAdapter<PlausibilityFilterRecord> {
		
		private Gson gson;
		private ClusterConnectionStrategy connStrat;
		private SectionDistanceAzimuthCalculator distAzCalc;

		public PlausibilityFilterAdapter(Gson gson, ClusterConnectionStrategy connStrat,
				SectionDistanceAzimuthCalculator distAzCalc) {
			this.gson = gson;
			this.connStrat = connStrat;
			this.distAzCalc = distAzCalc;
		}

		public void setGson(Gson gson) {
			this.gson = gson;
		}

		@Override
		public void write(JsonWriter out, PlausibilityFilterRecord record) throws IOException {
			out.beginObject(); // {
			
			PlausibilityFilter filter = record.filter;
			
			out.name("name").value(filter.getName());
			out.name("shortName").value(filter.getShortName());
			out.name("class").value(filter.getClass().getName());
			TypeAdapter<PlausibilityFilter> adapter = filter.getTypeAdapter();
			if (adapter == null) {
				// use default Gson serialization
				out.name("filter");
				gson.toJson(filter, filter.getClass(), out);
			} else {
				if (adapter instanceof PlausibilityFilterTypeAdapter) {
					PlausibilityFilterTypeAdapter pAdapt = (PlausibilityFilterTypeAdapter)adapter;
					pAdapt.init(connStrat, distAzCalc, gson);
				}
				out.name("adapter").value(adapter.getClass().getName());
				out.name("filter");
				adapter.write(out, filter);
			}
			
			out.endObject(); // }
		}

		@Override
		public PlausibilityFilterRecord read(JsonReader in) throws IOException {
			Class<PlausibilityFilter> type = null;
			TypeAdapter<PlausibilityFilter> adapter = null;
			PlausibilityFilter filter = null;
			String name = null;
			String shortName = null;
			
			in.beginObject();
			
			while (in.hasNext()) {
				switch (in.nextName()) {
				case "class":
					String clazz = in.nextString();
					try {
						type = getDeclaredTypeClass(clazz);
					} catch (Exception e) {
						System.err.println("Warning: adapter class not found, will use stub: "+clazz);
					}
					break;
				case "adapter":
					Preconditions.checkState(filter == null, "adapter must be before filter in JSON");
					try {
						Class<TypeAdapter<PlausibilityFilter>> adapterClass =
								getDeclaredTypeClass(in.nextString());
						Constructor<TypeAdapter<PlausibilityFilter>> constructor = adapterClass.getConstructor();
						adapter = constructor.newInstance();
					} catch (Exception e) {
						e.printStackTrace();
						System.err.println("Warning: adapter specified but class not found, "
								+ "will attempt default serialization");
//						throw ExceptionUtils.asRuntimeException(e);
						break;
					}
					if (adapter instanceof PlausibilityFilterTypeAdapter)
						((PlausibilityFilterTypeAdapter)adapter).init(connStrat, distAzCalc, gson);
					break;
				case "name":
					name = in.nextString();
					break;
				case "shortName":
					shortName = in.nextString();
					break;
				case "filter":
					String startPath = in.getPath();
					if (type == null) {
						// it's an unknown type, use stub
						filter = new PlausibilityFilterStub(name, shortName);
						skipUntilEndObject(in, startPath);
					} else {
						Preconditions.checkNotNull(type, "filter must be last in json object");
						if (adapter == null) {
							try {
								// use Gson default
								filter = gson.fromJson(in, type);
							} catch (Exception e) {
								e.printStackTrace();
								System.err.println("Warning: couldn't de-serialize filter "
										+ "(using stub instead): "+type.getName());
								System.err.flush();
//								System.out.println("PATH after read: "+in.getPath());
								filter = new PlausibilityFilterStub(name, shortName);
								skipUntilEndObject(in, startPath);
							}
						} else {
							// use specified adapter
							filter = adapter.read(in);
						}
					}
					break;

				default:
					break;
				}
			}
			
			in.endObject();
			return new PlausibilityFilterRecord(filter);
		}
		
	}
	
	/**
	 * This will skip all values in the given reader until it reaches END_OBJECT for the given path.
	 * @param in
	 * @param startPath
	 * @throws IOException
	 */
	private static void skipUntilEndObject(JsonReader in, String startPath) throws IOException {
		// this is where it gets tricky. we have descended into the filter object
		// and need to back the reader out
//		System.out.println("Looking to back out to: "+startPath);
		while (true) {
			String path = in.getPath();
			JsonToken peek = in.peek();
			if (peek == JsonToken.END_OBJECT && path.equals(startPath)) {
				// we're ready to break
//				System.out.println("DONE, new path: "+in.getPath());
				break;
			}
//			System.out.println("Still in the thick of it at: "+path);
			if (peek == JsonToken.END_ARRAY) {
//				System.out.println("\tending array");
				in.endArray();
			} else if (peek == JsonToken.END_OBJECT) {
//				System.out.println("\tending object");
				in.endObject();
			} else {
//				System.out.println("\tskipping "+peek);
				in.skipValue();
			}
		}
	}
	
	@SuppressWarnings("unchecked")
	public static <T> Class<T> getDeclaredTypeClass(String className) {
		Class<?> raw;
		try {
			raw = Class.forName(className);
		} catch (ClassNotFoundException e) {
			throw ExceptionUtils.asRuntimeException(e);
		}
		return (Class<T>)raw;
	}
	
	private static class PlausibilityFilterStub implements PlausibilityFilter {
		
		private String name;
		private String shortName;

		public PlausibilityFilterStub(String name, String shortName) {
			this.name = name;
			this.shortName = shortName;
		}

		@Override
		public String getShortName() {
			return name;
		}

		@Override
		public String getName() {
			return shortName;
		}

		@Override
		public PlausibilityResult apply(ClusterRupture rupture, boolean verbose) {
			throw new UnsupportedOperationException(getShortName()+" could not be deserialized from "
					+ "JSON and cannot be used for filtering");
		}
		
	}
	
	private static class FaultSectTypeAdapter extends TypeAdapter<FaultSection> {

		private List<? extends FaultSection> subSects;
		
		public FaultSectTypeAdapter(List<? extends FaultSection> subSects) {
			this.subSects = subSects;
		}
		
		@Override
		public void write(JsonWriter out, FaultSection value) throws IOException {
			out.value(value.getSectionId());
		}

		@Override
		public FaultSection read(JsonReader in) throws IOException {
			int id = in.nextInt();
			return subSects.get(id);
		}
		
	}
	
	private static class DistAzCalcTypeAdapter extends TypeAdapter<SectionDistanceAzimuthCalculator> {
		
		private SectionDistanceAzimuthCalculator distAzCalc;

		public DistAzCalcTypeAdapter(SectionDistanceAzimuthCalculator distAzCalc) {
			Preconditions.checkNotNull(distAzCalc);
			this.distAzCalc = distAzCalc;
		}

		@Override
		public void write(JsonWriter out, SectionDistanceAzimuthCalculator value) throws IOException {
			out.beginObject();
			out.name("numSects").value(value.getSubSections().size());
			out.endObject();
		}

		@Override
		public SectionDistanceAzimuthCalculator read(JsonReader in) throws IOException {
			in.beginObject();
			in.nextName();
			int numSects = in.nextInt();
			Preconditions.checkState(numSects == distAzCalc.getSubSections().size());
			in.endObject();
			return distAzCalc;
		}
		
	}
	
	private static class SubSectStiffnessTypeAdapter extends TypeAdapter<SubSectStiffnessCalculator> {

		private List<? extends FaultSection> subSects;
		private SubSectStiffnessCalculator prevCalc;

		public SubSectStiffnessTypeAdapter(List<? extends FaultSection> subSects) {
			this.subSects = subSects;
		}

		@Override
		public void write(JsonWriter out, SubSectStiffnessCalculator calc) throws IOException {
			out.beginObject();
			out.name("gridSpacing").value(calc.getGridSpacing());
			out.name("lameLambda").value(calc.getLameLambda());
			out.name("lameMu").value(calc.getLameMu());
			out.name("coeffOfFriction").value(calc.getCoeffOfFriction());
			out.name("patchAlignment").value(calc.getPatchAlignment().name());
			out.endObject();
		}

		@Override
		public SubSectStiffnessCalculator read(JsonReader in) throws IOException {
			in.beginObject();
			Double mu = null;
			Double lambda = null;
			Double coeffOfFriction = null;
			Double gridSpacing = null;
			PatchAlignment alignment = SubSectStiffnessCalculator.alignment_default;
			while (in.hasNext()) {
				switch (in.nextName()) {
				case "lameMu":
					mu = in.nextDouble();
					break;
				case "lameLambda":
					lambda = in.nextDouble();
					break;
				case "coeffOfFriction":
					coeffOfFriction = in.nextDouble();
					break;
				case "gridSpacing":
					gridSpacing = in.nextDouble();
					break;
				case "patchAlignment":
					alignment = PatchAlignment.valueOf(in.nextString());
					break;

				default:
					break;
				}
			}
			in.endObject();
			if (prevCalc != null) {
				// see if it's the same
				if (gridSpacing == prevCalc.getGridSpacing() && lambda == prevCalc.getLameLambda()
						&& mu == prevCalc.getLameMu() && coeffOfFriction == prevCalc.getCoeffOfFriction()) {
					return prevCalc;
				}
			}
			SubSectStiffnessCalculator calc = new SubSectStiffnessCalculator(
					subSects, gridSpacing, lambda, mu, coeffOfFriction, alignment);
			prevCalc = calc;
			return calc;
		}
		
	}
	
	private static class FloatRangeTypeAdapter extends TypeAdapter<Range<Float>> {

		@Override
		public void write(JsonWriter out, Range<Float> value) throws IOException {
			out.beginObject();
			if (value.hasLowerBound())
				out.name("lower").value(value.lowerEndpoint()).name("lowerType").value(value.lowerBoundType().name());
			if (value.hasUpperBound())
				out.name("upper").value(value.upperEndpoint()).name("upperType").value(value.upperBoundType().name());
			out.endObject();
		}

		@Override
		public Range<Float> read(JsonReader in) throws IOException {
			Float lower = null;
			BoundType lowerType = null;
			Float upper = null;
			BoundType upperType = null;
			in.beginObject();
			
			while (in.hasNext()) {
				String name = in.nextName();
				switch (name) {
				case "lower":
					lower = (float)in.nextDouble();
					break;
				case "lowerType":
					lowerType = BoundType.valueOf(in.nextString());
					break;
				case "upper":
					upper = (float)in.nextDouble();
					break;
				case "upperType":
					upperType = BoundType.valueOf(in.nextString());
					break;

				default:
					throw new IllegalStateException("unexpected json name: "+name);
				}
			}
			
			in.endObject();
			Preconditions.checkState(lower != null || upper != null);
			if (lower != null)
				Preconditions.checkNotNull(lowerType, "lower bound supplied without type");
			if (upper != null)
				Preconditions.checkNotNull(upperType, "upper bound supplied without type");
			if (lower == null)
				return Range.upTo(upper, upperType);
			if (upper == null)
				return Range.downTo(lower, lowerType);
			return Range.range(lower, lowerType, upper, upperType);
		}
		
	}
	
	public static void main(String[] args) throws IOException {
		FaultModels fm = FaultModels.FM3_1;
		DeformationModels dm = fm.getFilterBasis();
		
		DeformationModelFetcher dmFetch = new DeformationModelFetcher(fm, dm,
				null, 0.1);
		

		List<? extends FaultSection> subSects = dmFetch.getSubSectionList();
		// small subset
		subSects = subSects.subList(0, 30);
		double maxDist = 50d;
		
//		double maxDist = 5d;
		
		SectionDistanceAzimuthCalculator distAzCalc = new SectionDistanceAzimuthCalculator(subSects);
		
		DistCutoffClosestSectClusterConnectionStrategy connStrat =
				new DistCutoffClosestSectClusterConnectionStrategy(subSects, distAzCalc, maxDist);
		
		Builder builder = builder(connStrat, subSects);
//		builder.u3Azimuth();
//		builder.u3Cumulatives();
//		builder.minSectsPerParent(2, true, true);
		SubSectStiffnessCalculator stiffnessCalc =
				new SubSectStiffnessCalculator(subSects, 2d, 3e4, 3e4, 0.5);
		stiffnessCalc.setPatchAlignment(PatchAlignment.FILL_OVERLAP);
//		builder.parentCoulomb(stiffnessCalc, StiffnessAggregationMethod.MEDIAN, 0f, Directionality.EITHER);
//		builder.clusterCoulomb(stiffnessCalc, StiffnessAggregationMethod.MEDIAN, 0f);
		builder.clusterPathCoulomb(new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, false,
				AggregationMethod.FLATTEN, AggregationMethod.MEDIAN, AggregationMethod.SUM, AggregationMethod.SUM), 0f);
//		builder.clusterPathCoulomb(new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, false,
//				AggregationMethod.FLATTEN, AggregationMethod.GREATER_SUM_MEDIAN, AggregationMethod.SUM, AggregationMethod.SUM), 0f);
		builder.clusterPathCoulomb(new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, false,
				AggregationMethod.SUM, AggregationMethod.SUM, AggregationMethod.SUM, AggregationMethod.SUM), 0f);
		// fraction of all receiver patches that are net positive (summed over all sources)
		builder.netRupCoulomb(new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, true,
				AggregationMethod.SUM, AggregationMethod.PASSTHROUGH, AggregationMethod.RECEIVER_SUM, AggregationMethod.FRACT_POSITIVE), 0.95f);
//		// fraction of all [section -> receiver patch] interactions are positive
//		builder.netRupCoulomb(new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, true,
//				AggregationMethod.SUM, AggregationMethod.FLATTEN, AggregationMethod.FLATTEN, AggregationMethod.FRACT_POSITIVE), 0.8f);
//		// average fraction of positive receiver patches between each subsection pair
//		builder.netRupCoulomb(new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, true,
//				AggregationMethod.SUM, AggregationMethod.FRACT_POSITIVE, AggregationMethod.MEAN, AggregationMethod.MEAN), 0.8f);
//		// fraction of net positive receiver sections, averaged across all source sections
//		builder.netRupCoulomb(new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, true,
//				AggregationMethod.SUM, AggregationMethod.SUM, AggregationMethod.FRACT_POSITIVE, AggregationMethod.MEAN), 0.8f);
		// average fraction of receiver patches net positive
		builder.netRupCoulomb(new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, true,
				AggregationMethod.SUM, AggregationMethod.FRACT_POSITIVE, AggregationMethod.MEAN, AggregationMethod.MEAN), 0.75f);
		// fraction of receiver patches with >1/2 of interactions positive
		builder.netRupCoulomb(new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, true,
				AggregationMethod.NUM_POSITIVE, AggregationMethod.SUM, AggregationMethod.HALF_INTERACTIONS, AggregationMethod.FRACT_POSITIVE), 0.90f);
		// 1/2 of all interactions positive
		builder.netRupCoulomb(new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, true,
				AggregationMethod.NUM_POSITIVE, AggregationMethod.SUM, AggregationMethod.SUM, AggregationMethod.HALF_INTERACTIONS), 0f);
		// 3/4 of all interactions positive
		builder.netRupCoulomb(new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, true,
				AggregationMethod.NUM_POSITIVE, AggregationMethod.SUM, AggregationMethod.SUM, AggregationMethod.THREE_QUARTER_INTERACTIONS), 0f);
		// 9/10 of all interactions positive
//		builder.netRupCoulomb(new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, true,
//				AggregationMethod.NUM_POSITIVE, AggregationMethod.SUM, AggregationMethod.SUM, AggregationMethod.NINE_TENTH_INTERACTIONS), 0f);
		// number of receiver sections with <half of interactions positive
		builder.netRupCoulomb(new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, true,
				AggregationMethod.NUM_POSITIVE, AggregationMethod.SUM, AggregationMethod.HALF_INTERACTIONS, AggregationMethod.NUM_NEGATIVE), Range.atMost(5f));
		// fraction of receiver patches on the opposite side of a jump that are net positive with the prior rupture as source
		builder.clusterCoulomb(new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, false,
				AggregationMethod.SUM, AggregationMethod.PASSTHROUGH, AggregationMethod.RECEIVER_SUM, AggregationMethod.FRACT_POSITIVE), 0.8f);
		// fraction of receiver patches on the opposite side of a jump that have >1/2 interactions positive with the prior rupture as source
		builder.clusterCoulomb(new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, false,
				AggregationMethod.NUM_POSITIVE, AggregationMethod.SUM, AggregationMethod.HALF_INTERACTIONS, AggregationMethod.FRACT_POSITIVE), 0.5f);
		// jump cluster must be sum >= 0
		builder.netClusterCoulomb(new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, true,
				AggregationMethod.SUM, AggregationMethod.SUM, AggregationMethod.SUM, AggregationMethod.SUM), 0f);
		builder.cumulativeProbability(0.02f, new RelativeCoulombProb(
				new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, false,
				AggregationMethod.FLATTEN, AggregationMethod.SUM, AggregationMethod.SUM, AggregationMethod.SUM),
				connStrat, false, false));
		builder.cumulativeProbability(0.02f, new RelativeCoulombProb(
				new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, false,
				AggregationMethod.FLATTEN, AggregationMethod.SUM, AggregationMethod.SUM, AggregationMethod.SUM),
				connStrat, false, true));
		builder.cumulativeProbability(0.02f, new RelativeCoulombProb(
				new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, false,
				AggregationMethod.FLATTEN, AggregationMethod.SUM, AggregationMethod.SUM, AggregationMethod.SUM),
				connStrat, true, false));
		builder.cumulativeProbability(0.02f, new RelativeCoulombProb(
				new AggregatedStiffnessCalculator(StiffnessType.CFF, stiffnessCalc, false,
				AggregationMethod.FLATTEN, AggregationMethod.SUM, AggregationMethod.SUM, AggregationMethod.SUM),
				connStrat, true, true));
//		builder.clusterPathCoulomb(stiffnessCalc, StiffnessAggregationMethod.MEDIAN, 0f, 0.5f);
//		builder.clusterPathCoulomb(stiffnessCalc, StiffnessAggregationMethod.MEDIAN, 0f, 1f/3f);
//		builder.clusterPathCoulomb(stiffnessCalc, StiffnessAggregationMethod.MEDIAN, 0f, 2f/3f);
//		builder.netRupCoulomb(stiffnessCalc, StiffnessAggregationMethod.MEDIAN, 0f, RupCoulombQuantity.SUM_SECT_CFF);
//		builder.netClusterCoulomb(stiffnessCalc, StiffnessAggregationMethod.MEDIAN, 0f);
		
//		Penalty[] penalties = {
//				new CumulativePenaltyFilter.JumpPenalty(0f, 1d, true),
//				new CumulativePenaltyFilter.RakeChangePenalty(45f, 2d, false),
//				new CumulativePenaltyFilter.DipChangePenalty(20, 1d, false),
//				new CumulativePenaltyFilter.AzimuthChangePenalty(20f, 1d, false,
//						new JumpAzimuthChangeFilter.SimpleAzimuthCalc(distAzCalc))
//		};
//		builder.cumulativePenalty(10f, false, penalties);
		
//		RuptureProbabilityCalc[] probCalcs = CumulativeProbabilityFilter.getPrefferedBWCalcs(distAzCalc);
//		builder.cumulativeProbability(0.01f, probCalcs);
//		builder.cumulativeProbability(0.0075f, probCalcs);
//		builder.cumulativeProbability(0.005f, probCalcs);
//		builder.cumulativeProbability(0.0025f, probCalcs);
//		builder.cumulativeProbability(0.001f, probCalcs);
//		builder.cumulativeProbability(0.0005f, probCalcs);
		
		PlausibilityConfiguration config = builder.build();
		
//		config.writeFiltersJSON(new File("/home/kevin/OpenSHA/UCERF4/rup_sets/new_coulomb_filters.json"));
//		config.writeFiltersJSON(new File("/home/kevin/OpenSHA/UCERF4/rup_sets/new_cumulative_prob_filters.json"));
		config.writeFiltersJSON(new File("/home/kevin/OpenSHA/UCERF4/rup_sets/alt_filters.json"));
		
		Gson gson = buildGson(subSects);
		
		String json = gson.toJson(config);
		System.out.println(json);
		
		System.out.println("Deserializing");
		gson = buildGson(subSects);
		gson.fromJson(json, PlausibilityConfiguration.class);
		
		System.out.println("Serializing filters only");
		json = config.filtersToJSON(config.getFilters());
		System.out.println("Filters JSON:\n"+json);
		System.out.println("Deserializing filters JSON");
		readFiltersJSON(json, config.getConnectionStrategy(), config.getDistAzCalc());
	}

}
