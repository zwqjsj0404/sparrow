package edu.berkeley.sparrow.prototype;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import joptsimple.OptionParser;
import joptsimple.OptionSet;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.thrift.TException;

import edu.berkeley.sparrow.api.SparrowFrontendClient;
import edu.berkeley.sparrow.daemon.scheduler.SchedulerThrift;
import edu.berkeley.sparrow.daemon.util.Serialization;
import edu.berkeley.sparrow.daemon.util.TResources;
import edu.berkeley.sparrow.thrift.FrontendService;
import edu.berkeley.sparrow.thrift.TFullTaskId;
import edu.berkeley.sparrow.thrift.TPlacementPreference;
import edu.berkeley.sparrow.thrift.TResourceVector;
import edu.berkeley.sparrow.thrift.TTaskSpec;
import edu.berkeley.sparrow.thrift.TUserGroupInfo;

/**
 * Frontend for the prototype implementation.
 */
public class ProtoFrontend implements FrontendService.Iface {
  /** Jobs/second during warmup period. */
  public static final double DEFAULT_WARMUP_JOB_ARRIVAL_RATE_S = 10;

  /** Duration of warmup period. */
  public static final int DEFAULT_WARMUP_S = 10;

  /** Amount of time to wait for queues to drain once warmup period is over. */
  public static final int DEFAULT_POST_WARMUP_S = 60;

  /** Amount of time to launch tasks for (not including the warmup period). */
  public static final int DEFAULT_EXPERIMENT_S = 300;

  public static final double DEFAULT_JOB_ARRIVAL_RATE_S = 10; // Jobs/second
  public static final int DEFAULT_TASKS_PER_JOB = 1;          // Tasks/job

  // Type of benchmark to run, see ProtoBackend static constant for benchmark types
  public static final int DEFAULT_TASK_BENCHMARK = ProtoBackend.BENCHMARK_TYPE_FP_CPU;
  public static final int DEFAULT_BENCHMARK_ITERATIONS = 10;  // # of benchmark iterations

  /**
   * The default number of preferred nodes for each task. 0 signals that tasks are
   * unconstrained.
   */
  public static final int DEFAULT_NUM_PREFERRED_NODES = 0;

  /**
   * Configuration parameter name for the set of backends (used to set preferred nodes for
   * tasks).
   */
  public static final String BACKENDS = "backends";

  /**
   * Default application name.
   */
  public static final String APPLICATION_ID = "testApp";

  private static final Logger LOG = Logger.getLogger(ProtoFrontend.class);
  public final static long startTime = System.currentTimeMillis();
  public static AtomicInteger tasksLaunched = new AtomicInteger(0);

  /** A runnable which Spawns a new thread to launch a scheduling request. */
  private class JobLaunchRunnable implements Runnable {
    private List<TTaskSpec> request;
    private SparrowFrontendClient client;

    public JobLaunchRunnable(List<TTaskSpec> request, SparrowFrontendClient client) {
      this.request = request;
      this.client = client;
    }

    @Override
    public void run() {
      long start = System.currentTimeMillis();
      TUserGroupInfo user = new TUserGroupInfo();
      user.setUser("*");
      user.setGroup("*");
      try {
        client.submitJob(APPLICATION_ID, request, user);
        LOG.debug("Submitted job: " + request);
      } catch (TException e) {
        LOG.error("Scheduling request failed!", e);
      }
      long end = System.currentTimeMillis();
      LOG.debug("Scheduling request duration " + (end - start));
    }
  }

  public List<TTaskSpec> generateJob(int numTasks, int numPreferredNodes, List<String> backends,
                                     int benchmarkId, int benchmarkIterations) {
    TResourceVector resources = TResources.createResourceVector(300, 1);

    // Pack task parameters
    ByteBuffer message = ByteBuffer.allocate(8);
    message.putInt(benchmarkId);
    message.putInt(benchmarkIterations);

    List<TTaskSpec> out = new ArrayList<TTaskSpec>();
    for (int taskId = 0; taskId < numTasks; taskId++) {
      TTaskSpec spec = new TTaskSpec();
      spec.setTaskId(Integer.toString((new Random().nextInt())));
      spec.setMessage(message.array());
      spec.setEstimatedResources(resources);
      if (numPreferredNodes > 0) {
        Collections.shuffle(backends);
        TPlacementPreference preference = new TPlacementPreference();
        for (int i = 0; i < numPreferredNodes; i++) {
          preference.addToNodes(backends.get(i));
        }
        spec.setPreference(preference);
      }
      out.add(spec);
    }
    return out;
  }

  /**
   * Generates exponentially distributed interarrival delays.
   */
  public double generateInterarrivalDelay(Random r, double lambda) {
    double u = r.nextDouble();
    return -Math.log(u)/lambda;
  }

  public void run(String[] args) {
    try {
      OptionParser parser = new OptionParser();
      parser.accepts("c", "configuration file").withRequiredArg().ofType(String.class);
      parser.accepts("help", "print help statement");
      OptionSet options = parser.parse(args);

      if (options.has("help")) {
        parser.printHelpOn(System.out);
        System.exit(-1);
      }

      // Logger configuration: log to the console
      BasicConfigurator.configure();
      LOG.setLevel(Level.DEBUG);

      Configuration conf = new PropertiesConfiguration();

      if (options.has("c")) {
        String configFile = (String) options.valueOf("c");
        conf = new PropertiesConfiguration(configFile);
      }

      double warmup_lambda = conf.getDouble("warmup_job_arrival_rate_s",
                                            DEFAULT_WARMUP_JOB_ARRIVAL_RATE_S);
      int warmup_duration_s = conf.getInt("warmup_s", DEFAULT_WARMUP_S);
      int post_warmup_s = conf.getInt("post_warmup_s", DEFAULT_POST_WARMUP_S);

      double lambda = conf.getDouble("job_arrival_rate_s", DEFAULT_JOB_ARRIVAL_RATE_S);
      int experiment_duration_s = conf.getInt("experiment_s", DEFAULT_EXPERIMENT_S);
      LOG.debug("Using arrival rate of  " + lambda +
          " tasks per second and running experiment for " + experiment_duration_s + " seconds.");
      int tasksPerJob = conf.getInt("tasks_per_job", DEFAULT_TASKS_PER_JOB);
      int numPreferredNodes = conf.getInt("num_preferred_nodes", DEFAULT_NUM_PREFERRED_NODES);
      LOG.debug("Using " + numPreferredNodes + " preferred nodes for each task.");
      int benchmarkIterations = conf.getInt("benchmark.iterations",
          DEFAULT_BENCHMARK_ITERATIONS);
      int benchmarkId = conf.getInt("benchmark.id", DEFAULT_TASK_BENCHMARK);

      List<String> backends = new ArrayList<String>();
      if (numPreferredNodes > 0) {
        /* Attempt to parse the list of slaves, which we'll need to (randomly) select preferred
         * nodes. */
        if (!conf.containsKey(BACKENDS)) {
          LOG.fatal("Missing configuration backend list, which is needed to randomly select " +
                    "preferred nodes (num_preferred_nodes set to " + numPreferredNodes + ")");
        }
        for (String node : conf.getStringArray(BACKENDS)) {
          backends.add(node);
        }
        if (backends.size() < numPreferredNodes) {
          LOG.fatal("Number of backends smaller than number of preferred nodes!");
        }
      }

      SparrowFrontendClient client = new SparrowFrontendClient();
      int schedulerPort = conf.getInt("scheduler_port",
          SchedulerThrift.DEFAULT_SCHEDULER_THRIFT_PORT);
      client.initialize(new InetSocketAddress("localhost", schedulerPort), APPLICATION_ID, this);

      if (warmup_duration_s > 0) {
        LOG.debug("Warming up for " + warmup_duration_s + " seconds at arrival rate of " +
                  warmup_lambda + " jobs per second");
        launchTasks(warmup_lambda, warmup_duration_s, tasksPerJob, numPreferredNodes, benchmarkIterations,
            benchmarkId, backends, client);
        LOG.debug("Waiting for queues to drain after warmup (waiting " + post_warmup_s +
                 " seconds)");
        Thread.sleep(post_warmup_s * 1000);
      }
      LOG.debug("Launching experiment for " + experiment_duration_s + " seconds");
      launchTasks(lambda, experiment_duration_s, tasksPerJob, numPreferredNodes, benchmarkIterations,
          benchmarkId, backends, client);
    }
    catch (Exception e) {
      LOG.error("Fatal exception", e);
    }
  }

  private void launchTasks(double lambda, int launch_duration_s, int tasksPerJob,
      int numPreferredNodes, int benchmarkIterations, int benchmarkId,
      List<String> backends, SparrowFrontendClient client)
      throws InterruptedException {
    /* This is a little tricky.
     *
     * We want to generate inter-arrival delays according to the arrival rate specified.
     * The simplest option would be to generate an arrival delay and then sleep() for it
     * before launching each task. However, this is problematic because sleep() might wait
     * several ms longer than we ask it to. When task arrival rates get really fast,
     * i.e. one task every 10 ms, sleeping an additional few ms will mean we launch
     * tasks at a much lower rate than requested.
     *
     * Instead, we keep track of task launches in a way that does not depend on how long
     * sleep() actually takes. We still might have tasks launch slightly after their
     * scheduled launch time, but we will not systematically "fall behind" due to
     * compounding time lost during sleep()'s;
     */
    Random r = new Random();
    long mostRecentLaunch = System.currentTimeMillis();
    long end = System.currentTimeMillis() + launch_duration_s * 1000;
    while (System.currentTimeMillis() < end) {
      // Lambda is the arrival rate in S, so we need to multiply the result here by
      // 1000 to convert to ms.
      long delay = (long) (generateInterarrivalDelay(r, lambda) * 1000);
      long curLaunch = mostRecentLaunch + delay;
      long toWait = Math.max(0,  curLaunch - System.currentTimeMillis());
      mostRecentLaunch = curLaunch;
      if (toWait == 0) {
        LOG.warn("Lanching task after start time in generated workload.");
      }
      Thread.sleep(toWait);
      Runnable runnable =  new JobLaunchRunnable(
          generateJob(tasksPerJob, numPreferredNodes, backends, benchmarkId,
                      benchmarkIterations),
          client);
      new Thread(runnable).start();
      int launched = tasksLaunched.addAndGet(1);
      double launchRate = (double) launched * 1000.0 /
          (System.currentTimeMillis() - startTime);
      LOG.debug("Aggregate launch rate: " + launchRate);
    }
  }

  @Override
  public void frontendMessage(TFullTaskId taskId, int status, ByteBuffer message)
      throws TException {
    // We don't use messages here, so just log it.
    LOG.debug("Got unexpected message: " + Serialization.getByteBufferContents(message));
  }

  public static void main(String[] args) {
    new ProtoFrontend().run(args);
  }
}
