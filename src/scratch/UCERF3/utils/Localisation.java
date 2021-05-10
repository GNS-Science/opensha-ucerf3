package scratch.UCERF3.utils;

import org.opensha.commons.geo.GriddedRegion;
import scratch.UCERF3.analysis.FaultSystemRupSetCalc;

import java.util.function.Supplier;

public class Localisation {

    protected static Supplier<GriddedRegion> griddedRegionConstructor;
    protected static FaultSystemRupSetCalc.AdjustMinSeismoMagParkfieldFn parkFieldMinSeismoMagFn;

    // default values for California
    static {
        setGriddedRegionConstructor(RELM_RegionUtils::getGriddedRegionInstance);
        setParkfieldMinSeismoMagFn(FaultSystemRupSetCalc::adjustMinSeismoMagParkfield);
    }

    public static void setGriddedRegionConstructor(Supplier<GriddedRegion> griddedRegionConstructor) {
        Localisation.griddedRegionConstructor = griddedRegionConstructor;
    }

    public static  void setParkfieldMinSeismoMagFn(FaultSystemRupSetCalc.AdjustMinSeismoMagParkfieldFn fn){
        Localisation.parkFieldMinSeismoMagFn = fn;
    }

    public static GriddedRegion newGriddedRegion(){
        return griddedRegionConstructor.get();
    }

    public static FaultSystemRupSetCalc.AdjustMinSeismoMagParkfieldFn getParkFieldMinSeismoMagFn(){
        return parkFieldMinSeismoMagFn;
    }

}
