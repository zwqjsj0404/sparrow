package edu.berkeley.sparrow.daemon.nodemonitor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;

import org.apache.commons.configuration.Configuration;
import org.apache.log4j.Logger;
import org.apache.thrift.TException;
import org.apache.thrift.async.AsyncMethodCallback;

import edu.berkeley.sparrow.daemon.nodemonitor.TaskScheduler.TaskReservation;
import edu.berkeley.sparrow.daemon.scheduler.SchedulerThrift;
import edu.berkeley.sparrow.daemon.util.Logging;
import edu.berkeley.sparrow.daemon.util.Network;
import edu.berkeley.sparrow.daemon.util.TClients;
import edu.berkeley.sparrow.daemon.util.ThriftClientPool;
import edu.berkeley.sparrow.thrift.BackendService;
import edu.berkeley.sparrow.thrift.GetTaskService;
import edu.berkeley.sparrow.thrift.GetTaskService.AsyncClient;
import edu.berkeley.sparrow.thrift.GetTaskService.AsyncClient.getTask_call;
import edu.berkeley.sparrow.thrift.PongService.AsyncClient.ping_call;
import edu.berkeley.sparrow.thrift.PongService;
import edu.berkeley.sparrow.thrift.TFullTaskId;
import edu.berkeley.sparrow.thrift.THostPort;
import edu.berkeley.sparrow.thrift.TTaskLaunchSpec;

/**
 * TaskLauncher service consumes TaskReservations produced by {@link TaskScheduler.getNextTask}.
 * For each TaskReservation, the TaskLauncherService attempts to fetch the task specification from
 * the scheduler that send the reservation using the {@code getTask} RPC; if it successfully
 * fetches a task, it launches the task on the appropriate backend.
 */
public class TaskLauncherService {
  private final static Logger LOG = Logger.getLogger(TaskLauncherService.class);
  private final static Logger AUDIT_LOG = Logging.getAuditLogger(TaskLauncherService.class);

  /* The number of threads we use to launch tasks on backends. We also use this
   * to determine how many thrift connections to keep open to each backend, so that
   * in the limit case where all threads are talking to the same backend, we don't run
   * out of connections.*/
  public final static int CLIENT_POOL_SIZE = 10;

  private ThriftClientPool<GetTaskService.AsyncClient> getTaskClientPool =
      new ThriftClientPool<GetTaskService.AsyncClient>(
          new ThriftClientPool.GetTaskServiceMakerFactory());

  private static ThriftClientPool<PongService.AsyncClient> pongClientPool =
      new ThriftClientPool<PongService.AsyncClient>(
          new ThriftClientPool.PongServiceMakerFactory());

  private THostPort nodeMonitorInternalAddress;

  private TaskScheduler scheduler;

  /** Cache of thrift clients pools for each backends. Clients are removed from the pool
   *  when in use. */
  private HashMap<InetSocketAddress, BlockingQueue<BackendService.Client>> backendClients =
      new HashMap<InetSocketAddress, BlockingQueue<BackendService.Client>>();

  /** A runnable which spins in a loop asking for tasks to launch and launching them. */
  private class TaskLaunchRunnable implements Runnable {
    @Override
    public void run() {
      while (true) {
        TaskReservation task = scheduler.getNextTask(); // blocks until task is ready
        LOG.debug("Tring to get scheduler client to make getTask() request for app " + task.appId +
                  ", request " + task.requestId);

        // Request the task specification from the scheduler.
        GetTaskService.AsyncClient getTaskClient;
        PongService.AsyncClient pongClient;
        InetSocketAddress newAddress = new InetSocketAddress(
            task.schedulerAddress.getHostName(), SchedulerThrift.DEFAULT_GET_TASK_PORT);
        InetSocketAddress pongAddress = new InetSocketAddress(
            task.schedulerAddress.getHostName(), 12345);
        try {
          getTaskClient = getTaskClientPool.borrowClient(newAddress);
          pongClient = pongClientPool.borrowClient(pongAddress);
        } catch (Exception e) {
          LOG.fatal("Unable to create client to contact scheduler at " +
              newAddress.toString() + ":" + e);
          return;
        }
        try {
          LOG.debug("Attempting to get task from scheduler at " +
                    nodeMonitorInternalAddress.toString() + " for request " + task.requestId);
          AUDIT_LOG.debug(Logging.auditEventString("node_monitor_get_task", task.requestId,
                                                   nodeMonitorInternalAddress.getHost()));
          getTaskClient.getTask(task.requestId, nodeMonitorInternalAddress,
                                  new GetTaskCallback(task, newAddress, System.nanoTime()));
          pongClient.ping("PING", new PongCallback(pongAddress, System.nanoTime()));
        } catch (TException e) {
          LOG.error("Unable to getTask() from scheduler at " +
              newAddress.toString() + ":" + e);
        }
      }
    }
  }

  private class GetTaskCallback implements AsyncMethodCallback<getTask_call> {
    private TaskReservation taskReservation;
    private InetSocketAddress getTaskAddress;
    private Long t0;

    public GetTaskCallback(TaskReservation taskReservation, InetSocketAddress getTaskAddress,
        Long t0) {
      this.taskReservation = taskReservation;
      this.getTaskAddress = getTaskAddress;
      this.t0 = t0;
    }

    @Override
    public void onComplete(getTask_call response) {
      LOG.debug(Logging.functionCall(response));
      try {
        getTaskClientPool.returnClient(getTaskAddress, (AsyncClient) response.getClient());
      } catch (Exception e) {
        LOG.error("Error getting client from scheduler client pool: " + e.getMessage());
        return;
      }
      List<TTaskLaunchSpec> taskLaunchSpecs;
      try {
        taskLaunchSpecs = response.getResult();
      } catch (TException e) {
        LOG.error("Unable to read result of calling getTask() on scheduler " +
                  taskReservation.schedulerAddress.toString() + ": " + e);
        scheduler.noTaskForRequest(taskReservation);
        return;
      }

      if (taskLaunchSpecs.isEmpty()) {
        LOG.debug("Didn't receive a task for request " + taskReservation.requestId);
        scheduler.noTaskForRequest(taskReservation);
        return;
      }

      if (taskLaunchSpecs.size() > 1) {
        LOG.warn("Received " + taskLaunchSpecs +
                 " task launch specifications; ignoring all but the first one.");
      }
      TTaskLaunchSpec taskLaunchSpec = taskLaunchSpecs.get(0);
      LOG.debug("Received task for request " + taskReservation.requestId + ", task " +
                taskLaunchSpec.getTaskId());
      AUDIT_LOG.info(Logging.auditEventString("node_monitor_task_launch",
                                              taskReservation.requestId,
                                              nodeMonitorInternalAddress.getHost(),
                                              taskLaunchSpec.getTaskId(),
                                              taskReservation.previousRequestId,
                                              taskReservation.previousTaskId));

      // Launch the task on the backend.
      BackendService.Client client = null;
      if (!backendClients.containsKey(taskReservation.appBackendAddress)) {
        createThriftClients(taskReservation.appBackendAddress);
      }

      try {
        // Blocks until a client becomes available.
        client = backendClients.get(taskReservation.appBackendAddress).take();
      } catch (InterruptedException e) {
        LOG.fatal("Error when trying to get a client for " + taskReservation.appId
                  + "backend at " + taskReservation.appBackendAddress.toString() + ":" +
                  e);
      }

      THostPort schedulerHostPort = Network.socketAddressToThrift(
          taskReservation.schedulerAddress);
      TFullTaskId taskId = new TFullTaskId(taskLaunchSpec.getTaskId(), taskReservation.requestId,
                                           taskReservation.appId, schedulerHostPort);
      try {
        client.launchTask(taskLaunchSpec.bufferForMessage(), taskId, taskReservation.user,
                          taskReservation.estimatedResources);
      } catch (TException e) {
        LOG.fatal("Unable to launch task on backend " + taskReservation.appBackendAddress + ":" +
                  e);
      }

      try {
        backendClients.get(taskReservation.appBackendAddress).put(client);
      } catch (InterruptedException e) {
        LOG.fatal("Error while attempting to return client for " +
                  taskReservation.appBackendAddress.toString() +
                  " to the set of backend clients: " + e);
      }

      LOG.debug("Launched task " + taskId.taskId + " for request " + taskReservation.requestId +
                " on application backend at system time " + System.currentTimeMillis());
      System.out.println("Gettask took: " + (System.nanoTime() - t0) / (1000.0 * 1000.0) + "ms");
    }

    @Override
    public void onError(Exception exception) {
      // Do not return error client to pool.
      exception.printStackTrace();
      LOG.error("Error executing getTask() RPC:" + exception.getStackTrace().toString() +
                exception.toString());
    }
  }

  public void initialize(Configuration conf, TaskScheduler scheduler,
                         int nodeMonitorPort) {
    this.scheduler = scheduler;
    nodeMonitorInternalAddress = new THostPort(Network.getHostName(conf), nodeMonitorPort);
    ExecutorService service = Executors.newFixedThreadPool(CLIENT_POOL_SIZE);
    for (int i = 0; i < CLIENT_POOL_SIZE; i++) {
      service.submit(new TaskLaunchRunnable());
    }
  }

  /** Creates a set of thrift clients and adds them to the client pool. */
  public void createThriftClients(InetSocketAddress backendAddr) {
    BlockingQueue<BackendService.Client> clients = new
        LinkedBlockingDeque<BackendService.Client>();
    for (int i = 0; i < CLIENT_POOL_SIZE; i++) {
      try {
        clients.put(TClients.createBlockingBackendClient(
            backendAddr.getAddress().getHostAddress(), backendAddr.getPort()));
      } catch (InterruptedException e) {
        LOG.error("Interrupted creating thrift clients", e);
      } catch (IOException e) {
        LOG.error("Error creating thrift client", e);
      }
    }

    backendClients.put(backendAddr, clients);
  }

  private class PongCallback implements AsyncMethodCallback<ping_call> {
    private InetSocketAddress address;
    private Long t0;

    PongCallback(InetSocketAddress address, Long t0) { this.address = address; this.t0 = t0; }

    @Override
    public void onComplete(ping_call response) {
      try {
        pongClientPool.returnClient(address, (PongService.AsyncClient) response.getClient());
        System.out.println("Ping took: " + (System.nanoTime() - t0) / (1000.0 * 1000.0) + "ms");
      } catch (Exception e) {
        System.out.println("ERROR!!!");
      }
    }

    @Override
    public void onError(Exception exception) {
      System.out.println("ERROR!!!");
    }

  }

}