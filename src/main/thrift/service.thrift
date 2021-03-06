include 'types.thrift'

namespace java edu.berkeley.sparrow.thrift

# SchedulerService is used by application frontends to communicate with Sparrow
# and place jobs.
service SchedulerService {
  # Register a frontend for the given application.
  bool registerFrontend(1: string app, 2: string socketAddress);

  # Submit a job composed of a list of individual tasks. 
  void submitJob(1: types.TSchedulingRequest req) throws (1: types.IncompleteRequestException e);

  # Send a message to be delivered to the frontend for {app} pertaining
  # to the task {taskId}. The {status} field allows for application-specific
  # status enumerations. Right now this is used only for Spark, which relies on
  # the scheduler to send task completion messages to frontends.
  void sendFrontendMessage(1: string app, 2: types.TFullTaskId taskId, 
                           3: i32 status, 4: binary message);
  
  # Called by a node monitor when it has available responses to run a task. Always called in
  # response to an enqueueTask() request from this scheduler, requestId specifies the ID given
  # in that enqueueTask() request. Currently, we only support returning 0 or 1 task
  # specs, where 0 signals that the given request has no more tasks that can be launched on the
  # node.
  # TODO: Add a numTasks parameter to signal how many slots are free, and support
  #       returning more than 1 tasks.
  list<types.TTaskLaunchSpec> getTask(1: string requestId, 2: types.THostPort nodeMonitorAddress);
}

# A service used by application backends to coordinate with Sparrow.
service NodeMonitorService {
  # Register this machine as a backend for the given application.
  bool registerBackend(1: string app, 2: string listenSocket);

  # Inform the NodeMonitor that a particular task has finished
  void tasksFinished(1: list<types.TFullTaskId> tasks);

  # See SchedulerService.sendFrontendMessage
  void sendFrontendMessage(1: string app, 2: types.TFullTaskId taskId,
                           3: i32 status, 4: binary message);
}

# A service that backends are expected to extend. Handles communication
# from a NodeMonitor.
service BackendService {
  void launchTask(1: binary message, 2: types.TFullTaskId taskId,
                  3: types.TUserGroupInfo user,
                  4: types.TResourceVector estimatedResources);
}

# A service that frontends are expected to extend. Handles communication from
# a Scheduler.
service FrontendService {
  # See SchedulerService.sendFrontendMessage
  void frontendMessage(1: types.TFullTaskId taskId, 2: i32 status, 
                       3: binary message);
}

# The InternalService exposes state about application backends to:
# 1) Other Sparrow daemons
# 2) The central state store
service InternalService {
  # Enqueues a reservation to launch the given number of tasks. The NodeMonitor sends
  # a GetTask() RPC to the given schedulerAddress when it is ready to launch a task, for each
  # enqueued task reservation. Returns whether or not the task was successfully enqueued.
  bool enqueueTaskReservations(1: types.TEnqueueTaskReservationsRequest request);
  
  # Used by the state store.
  map<string, types.TResourceUsage> getLoad(1: string app, 2: string requestId);
}

service SchedulerStateStoreService {
  # Message from the state store giving the scheduler new information
  void updateNodeState(1: map<string, types.TNodeState> snapshot);
}

service StateStoreService {
  # Register a scheduler with the given socket address (IP: Port)
  void registerScheduler(1: string schedulerAddress);

  # Register a node monitor with the given socket address (IP: Port)
  void registerNodeMonitor(1: string nodeMonitorAddress);
}
