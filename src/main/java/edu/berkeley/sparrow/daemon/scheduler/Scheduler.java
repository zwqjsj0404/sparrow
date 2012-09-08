package edu.berkeley.sparrow.daemon.scheduler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import org.apache.commons.configuration.Configuration;
import org.apache.log4j.Logger;
import org.apache.thrift.TException;
import org.apache.thrift.async.AsyncMethodCallback;

import com.google.common.base.Optional;

import edu.berkeley.sparrow.daemon.SparrowConf;
import edu.berkeley.sparrow.daemon.scheduler.TaskPlacer.TaskPlacementResponse;
import edu.berkeley.sparrow.daemon.util.Hostname;
import edu.berkeley.sparrow.daemon.util.Logging;
import edu.berkeley.sparrow.daemon.util.Serialization;
import edu.berkeley.sparrow.daemon.util.ThriftClientPool;
import edu.berkeley.sparrow.thrift.FrontendService;
import edu.berkeley.sparrow.thrift.FrontendService.AsyncClient;
import edu.berkeley.sparrow.thrift.FrontendService.AsyncClient.frontendMessage_call;
import edu.berkeley.sparrow.thrift.InternalService;
import edu.berkeley.sparrow.thrift.InternalService.AsyncClient.launchTask_call;
import edu.berkeley.sparrow.thrift.TFullTaskId;
import edu.berkeley.sparrow.thrift.TPlacementPreference;
import edu.berkeley.sparrow.thrift.TSchedulingRequest;
import edu.berkeley.sparrow.thrift.TTaskPlacement;
import edu.berkeley.sparrow.thrift.TTaskSpec;

/**
 * This class implements the Sparrow scheduler functionality.
 */
public class Scheduler {
  private final static Logger LOG = Logger.getLogger(Scheduler.class);
  private final static Logger AUDIT_LOG = Logging.getAuditLogger(Scheduler.class);

  /** Used to uniquely identify requests arriving at this scheduler. */
  private int counter = 0;
  private InetSocketAddress address;

  /** Socket addresses for each frontend. */
  HashMap<String, InetSocketAddress> frontendSockets =
      new HashMap<String, InetSocketAddress>();

  /** Thrift client pool for communicating with node monitors */
  ThriftClientPool<InternalService.AsyncClient> nodeMonitorClientPool =
      new ThriftClientPool<InternalService.AsyncClient>(
      new ThriftClientPool.InternalServiceMakerFactory());

  /** Thrift client pool for communicating with front ends. */
  private ThriftClientPool<FrontendService.AsyncClient> frontendClientPool =
      new ThriftClientPool<FrontendService.AsyncClient>(
          new ThriftClientPool.FrontendServiceMakerFactory());

  /** Information about cluster workload due to other schedulers. */
  SchedulerState state;

  /** Logical task assignment. */
  // TODO: NOTE - this is a hack - we need to modify constrainedPlacer to have more
  // advanced features like waiting for some probes and configurable probe ratio.
  TaskPlacer constrainedPlacer;
  TaskPlacer unconstrainedPlacer;

  private Configuration conf;

  /**
   * A callback handler for asynchronous task launches.
   *
   * We use the thrift event-based interface for launching tasks. In parallel, we launch
   * several tasks, then we return when all have finished launching.
   */
  private class TaskLaunchCallback implements AsyncMethodCallback<launchTask_call> {
    private CountDownLatch latch;
    private InternalService.AsyncClient client;
    private InetSocketAddress socket;

    // Note that the {@code client} must come from the Scheduler's {@code clientPool}.
    public TaskLaunchCallback(CountDownLatch latch, InternalService.AsyncClient client,
        InetSocketAddress socket) {
      this.latch = latch;
      this.client = client;
      this.socket = socket;
    }

    @Override
    public void onComplete(launchTask_call response) {
      try {
        nodeMonitorClientPool.returnClient(socket, client);
      } catch (Exception e) {
        LOG.error(e);
      }
      latch.countDown();
    }

    @Override
    public void onError(Exception exception) {
      LOG.error("Error launching task: " + exception);
      // TODO We need to have a story here, regarding the failure model when the
      //      task launch doesn't succeed.
      latch.countDown();
    }
  }

  public void initialize(Configuration conf, InetSocketAddress socket) throws IOException {
    address = socket;
    String mode = conf.getString(SparrowConf.DEPLYOMENT_MODE, "unspecified");
    this.conf = conf;
    if (mode.equals("standalone")) {
      state = new StandaloneSchedulerState();
      constrainedPlacer = new ConstraintObservingProbingTaskPlacer();
      unconstrainedPlacer = new ProbingTaskPlacer();
    } else if (mode.equals("configbased")) {
      state = new ConfigSchedulerState();
      constrainedPlacer = new ConstraintObservingProbingTaskPlacer();
      unconstrainedPlacer = new ProbingTaskPlacer();
    } else if (mode.equals("production")) {
      state = new StateStoreSchedulerState();
      constrainedPlacer = new ConstraintObservingProbingTaskPlacer();
      unconstrainedPlacer = new ProbingTaskPlacer();
    } else {
      throw new RuntimeException("Unsupported deployment mode: " + mode);
    }

    state.initialize(conf);
    constrainedPlacer.initialize(conf, nodeMonitorClientPool);
    unconstrainedPlacer.initialize(conf, nodeMonitorClientPool);
  }

  public boolean registerFrontend(String appId, String addr) {
    LOG.debug(Logging.functionCall(appId, addr));
    Optional<InetSocketAddress> socketAddress = Serialization.strToSocket(addr);
    if (!socketAddress.isPresent()) {
      LOG.error("Bad address from frontend: " + addr);
      return false;
    }
    frontendSockets.put(appId, socketAddress.get());
    return state.watchApplication(appId);
  }

  /** This is a special case where we want to ensure a very specific scheduling allocation.*/
  private boolean isSpecialCase(TSchedulingRequest req) {
    final int specialSize = 2;
    if (req.getTasks().size() != specialSize) {
      return false;
    }
    for (TTaskSpec t: req.getTasks()) {
      if ((t.getPreference().getNodes() != null)  &&
          (t.getPreference().getNodes().size() != 0)) {
        return false;
      }
    }
    return true;
  }

  /** Handles special case. */
  private boolean handleSpecialCase(TSchedulingRequest req) throws TException {
    LOG.info("Handling special case request");
    final int specialSize = 2;

    // No tasks have preferences and we have the magic number of tasks
    TSchedulingRequest req1 = new TSchedulingRequest();
    req1.user = req.user;
    req1.app = req.app;
    req1.schedulingPref = req.schedulingPref;
    TSchedulingRequest req2 = new TSchedulingRequest();
    req2.user = req.user;
    req2.app = req.app;
    req2.schedulingPref = req.schedulingPref;
    TSchedulingRequest req3 = new TSchedulingRequest();
    req3.user = req.user;
    req3.app = req.app;
    req3.schedulingPref = req.schedulingPref;

    List<InetSocketAddress> backends = new ArrayList<InetSocketAddress>();
    for (InetSocketAddress backend : state.getBackends(req.app).keySet()) {
      backends.add(backend);
    }

    if (!(backends.size() >= (specialSize * 3))) {
      LOG.error("Special case expects at least three times as many machines as tasks.");
      return false;
    }
    LOG.info(backends);
    for (int i = 0; i < backends.size(); i++) {
      int taskIndex = i / 3;
      if (taskIndex > req.getTasksSize() - 1) { break; }

      TTaskSpec task = req.getTasks().get(i / 3);
      // Create three copies of this task each on a different backend
      TTaskSpec task1 = new TTaskSpec();
      task1.estimatedResources = task.estimatedResources;
      task1.message = task.message;
      task1.taskID = task.taskID;
      task1.preference = new TPlacementPreference();
      task1.preference.addToNodes(backends.get(i).getHostName());
      req1.addToTasks(task1);
      i++;

      TTaskSpec task2 = new TTaskSpec();
      task2.estimatedResources = task.estimatedResources;
      task2.message = task.message;
      task2.taskID = task.taskID;
      task2.preference = new TPlacementPreference();
      task2.preference.addToNodes(backends.get(i).getHostName());
      req2.addToTasks(task2);
      i++;

      TTaskSpec task3 = new TTaskSpec();
      task3.estimatedResources = task.estimatedResources;
      task3.message = task.message;
      task3.taskID = task.taskID;
      task3.preference = new TPlacementPreference();
      task3.preference.addToNodes(backends.get(i).getHostName());
      req3.addToTasks(task3);
    }
    LOG.info("Three requests: " + req1 + req2 + req3);
    return submitJob(req1) && submitJob(req2) && submitJob(req3);
  }

  public boolean submitJob(TSchedulingRequest req) throws TException {
    if (isSpecialCase(req)) { return handleSpecialCase(req); }

    LOG.debug(Logging.functionCall(req));
    long start = System.currentTimeMillis();

    String requestId = getRequestId();
    // Logging the address here is somewhat redundant, since all of the
    // messages in this particular log file come from the same address.
    // However, it simplifies the process of aggregating the logs, and will
    // also be useful when we support multiple daemons running on a single
    // machine.
    AUDIT_LOG.info(Logging.auditEventString("arrived", requestId,
                                            req.getTasks().size(),
                                            address.getAddress().getHostAddress()));
    Collection<TaskPlacementResponse> placement = null;
    try {
      placement = getJobPlacementResp(req, requestId);
    } catch (IOException e) {
      LOG.error(e);
      return false;
    }
    long probeFinish = System.currentTimeMillis();

    // Launch tasks.
    CountDownLatch latch = new CountDownLatch(placement.size());
    for (TaskPlacementResponse response : placement) {
      LOG.debug("Attempting to launch task " + response.getTaskSpec().getTaskID()
          + " on " + response.getNodeAddr());

      InternalService.AsyncClient client;
      try {
        long t0 = System.currentTimeMillis();
        client = nodeMonitorClientPool.borrowClient(response.getNodeAddr());
        long t1 = System.currentTimeMillis();
        if (t1 - t0 > 100) {
          LOG.error("Took more than 100ms to create client for: " +
            response.getNodeAddr());
        }
      } catch (Exception e) {
        LOG.error(e);
        return false;
      }
      String taskId = response.getTaskSpec().taskID;

      AUDIT_LOG.info(Logging.auditEventString("scheduler_launch", requestId, taskId));
      TFullTaskId id = new TFullTaskId();
      id.appId = req.getApp();
      id.frontendSocket = address.getHostName() + ":" + address.getPort();
      id.requestId = requestId;
      id.taskId = taskId;
      client.launchTask(response.getTaskSpec().message, id,
          req.getUser(), response.getTaskSpec().getEstimatedResources(),
          new TaskLaunchCallback(latch, client, response.getNodeAddr()));
    }
    // NOTE: Currently we just return rather than waiting for all tasks to launch
    /*
    try {
      LOG.debug("Waiting for " + placement.size() + " tasks to finish launching");
      latch.await();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
    */
    long end = System.currentTimeMillis();
    LOG.debug("All tasks launched, returning. Total time: " + (end - start) +
        "Probe time: " + (probeFinish - start));
    return true;
  }

  public Collection<TTaskPlacement> getJobPlacement(TSchedulingRequest req)
      throws IOException {
    LOG.debug(Logging.functionCall(req));
    // Get placement
    Collection<TaskPlacementResponse> placements = getJobPlacementResp(req,
                                                                       getRequestId());

    // Massage into correct Thrift output type
    Collection<TTaskPlacement> out = new HashSet<TTaskPlacement>(placements.size());
    for (TaskPlacementResponse placement : placements) {
      TTaskPlacement tPlacement = new TTaskPlacement();
      tPlacement.node = placement.getNodeAddr().toString();
      tPlacement.taskID = placement.getTaskSpec().getTaskID();
      out.add(tPlacement);
    }
    LOG.debug("Returning task placement: " + out);
    return out;
  }

  /**
   * Internal method called by both submitJob() and getJobPlacement().
   */
  private Collection<TaskPlacementResponse> getJobPlacementResp(TSchedulingRequest req,
      String requestId) throws IOException {
    LOG.debug(Logging.functionCall(req));
    String app = req.getApp();
    List<TTaskSpec> tasks = req.getTasks();
    Set<InetSocketAddress> backends = state.getBackends(app).keySet();
    List<InetSocketAddress> backendList = new ArrayList<InetSocketAddress>(backends.size());
    for (InetSocketAddress backend : backends) {
      backendList.add(backend);
    }
    boolean constrained = false;
    for (TTaskSpec task : tasks) {
      constrained = constrained || (
          task.preference != null &&
          task.preference.nodes != null &&
          !task.preference.nodes.isEmpty());
    }

    /* This is a hack to force Spark to cache data on multiple machines. We
     * use a probe ratio of three to signal that the scheduler should perform
     * this hack rather than trying to do a "good" job scheduling.
     *
     * This makes several assumptions, including the fact that after all preferences
     * are excluded, there are still nodes left in the cluster. This works only under
     * a very limited set of circumstances that correspond to our large scale tests.*/
    /*
    if (req.getSchedulingPref() != null && req.getSchedulingPref().getProbeRatio() == 3) {
      if (tasks.get(0).preference.nodes != null &&
          (tasks.get(0).preference.nodes.size() == 1 ||
          tasks.get(0).preference.nodes.size() == 2)) {

        // Explicitly avoid nodes with preferences, because those are nodes where
        // the data is already cached, and we're trying to force the data to be
        // cached on other nodes.
        List<InetSocketAddress> subBackends = Lists.newArrayList(backends);
        List<InetSocketAddress> toRemove = Lists.newArrayList();
        for (TTaskSpec task : tasks) {
          for (String node : task.preference.nodes) {
           InetAddress addr = InetAddress.getByName(node);
           for (InetSocketAddress backend : subBackends) {
             if (backend.getAddress().equals(addr)) { toRemove.add(backend); }
           }
          }
        }
       subBackends.removeAll(toRemove);
       return new RandomTaskPlacer().placeTasks(
           app, requestId, subBackends, tasks, req.schedulingPref);
      }
    }*/
    if (constrained) {
      return constrainedPlacer.placeTasks(
          app, requestId, backendList, tasks, req.schedulingPref);
    } else {
      return unconstrainedPlacer.placeTasks(
          app, requestId, backendList, tasks, req.schedulingPref);
    }
  }

  /**
   * Returns an ID that identifies a request uniquely (across all Sparrow schedulers).
   *
   * This should only be called once for each request (it will return a different
   * identifier if called a second time).
   *
   * TODO: Include the port number, so this works when there are multiple schedulers
   * running on a single machine (as there will be when we do large scale testing).
   */
  private String getRequestId() {
    /* The request id is a string that includes the IP address of this scheduler followed
     * by the counter.  We use a counter rather than a hash of the request because there
     * may be multiple requests to run an identical job. */
    return String.format("%s_%d", Hostname.getIPAddress(conf), counter++);
  }

  private class sendFrontendMessageCallback implements
      AsyncMethodCallback<frontendMessage_call> {
    private InetSocketAddress frontendSocket;
    private AsyncClient client;
    public sendFrontendMessageCallback(InetSocketAddress socket, AsyncClient client) {
      frontendSocket = socket;
      this.client = client;
    }

    public void onComplete(frontendMessage_call response) {
      try { frontendClientPool.returnClient(frontendSocket, client); }
      catch (Exception e) { LOG.error(e); }
    }

    public void onError(Exception exception) {
      // Do not return error client to pool
      LOG.error(exception);
    }
  }

  public void sendFrontendMessage(String app, TFullTaskId taskId,
      int status, ByteBuffer message) {
    LOG.debug(Logging.functionCall(app, taskId, message));
    InetSocketAddress frontend = frontendSockets.get(app);
    if (frontend == null) {
      LOG.error("Requested message sent to unregistered app: " + app);
    }
    try {
      AsyncClient client = frontendClientPool.borrowClient(frontend);
      client.frontendMessage(taskId, status, message,
          new sendFrontendMessageCallback(frontend, client));
    } catch (IOException e) {
      LOG.error("Error launching message on frontend: " + app, e);
    } catch (TException e) {
      LOG.error("Error launching message on frontend: " + app, e);
    } catch (Exception e) {
      LOG.error("Error launching message on frontend: " + app, e);
    }
  }
}
