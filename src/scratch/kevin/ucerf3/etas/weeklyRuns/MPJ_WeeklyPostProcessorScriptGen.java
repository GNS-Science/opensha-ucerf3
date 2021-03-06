package scratch.kevin.ucerf3.etas.weeklyRuns;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.opensha.commons.hpc.JavaShellScriptWriter;
import org.opensha.commons.hpc.mpj.FastMPJShellScriptWriter;
import org.opensha.commons.hpc.mpj.MPJExpressShellScriptWriter;
import org.opensha.commons.hpc.pbs.BatchScriptWriter;
import org.opensha.commons.hpc.pbs.StampedeScriptWriter;
import org.opensha.commons.hpc.pbs.USC_HPCC_ScriptWriter;

import com.google.common.base.Preconditions;

import edu.usc.kmilner.mpj.taskDispatch.MPJTaskCalculator;

public class MPJ_WeeklyPostProcessorScriptGen {

	public static void main(String[] args) throws IOException {
		File localSimsDir = new File("/home/kevin/OpenSHA/UCERF3/etas/simulations");
		
//		String batchName = "2020_05_14-weekly-1986-present-full_td-kCOV1.5";
//		String batchName = "2020_05_25-weekly-1986-present-no_ert-kCOV1.5";
		String batchName = "2020_07_13-weekly-1986-present-gridded-kCOV1.5";
		
		File localBatchDir = new File(localSimsDir, batchName);
		Preconditions.checkState(localBatchDir.exists());
		
		BatchScriptWriter pbsWrite = new USC_HPCC_ScriptWriter();
		File remoteSimsDir = new File("/home/scec-02/kmilner/ucerf3/etas_sim");
		File remoteBatchDir = new File(remoteSimsDir, batchName);
		File javaBin = USC_HPCC_ScriptWriter.JAVA_BIN;
		File mpjHome = USC_HPCC_ScriptWriter.MPJ_HOME;
		int maxHeapMB = 55000;
		List<File> classpath = new ArrayList<>();
		classpath.add(new File(remoteSimsDir, "opensha-dev-all.jar"));
		JavaShellScriptWriter mpjWrite = new MPJExpressShellScriptWriter(
				javaBin, maxHeapMB, classpath, mpjHome);
		
		int mins = 24*60;
		int nodes = 30;
		int ppn = 10;
		String queue = "scec";
		File jobFile = new File(localBatchDir, "post_process.slurm");
		
//		BatchScriptWriter pbsWrite = new StampedeScriptWriter();
//		File remoteSimsDir = new File("/scratch/00950/kevinm/ucerf3/etas_sim");
//		File remoteBatchDir = new File(remoteSimsDir, batchName);
//		File javaBin = StampedeScriptWriter.JAVA_BIN;
//		File fmpjHome = StampedeScriptWriter.FMPJ_HOME;
//		int maxHeapMB = 55000;
//		List<File> classpath = new ArrayList<>();
//		classpath.add(new File(remoteSimsDir, "opensha-dev-all.jar"));
//		JavaShellScriptWriter mpjWrite = new FastMPJShellScriptWriter(
//				javaBin, maxHeapMB, classpath, fmpjHome);
//		
//		int mins = 3*60;
//		int nodes = 30;
//		int ppn = 20;
//		String queue = "normal";
//		File jobFile = new File(localBatchDir, "post_process_stampede.slurm");
		
		String argz = MPJTaskCalculator.argumentBuilder().minDispatch(1).maxDispatch(5*ppn).threads(ppn).build(" ");
		argz += " "+remoteBatchDir.getAbsolutePath();
		
		pbsWrite.writeScript(jobFile, mpjWrite.buildScript(MPJ_WeeklyPostProcessor.class.getName(), argz),
				mins, nodes, ppn, queue);
	}

}
