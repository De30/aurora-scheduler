import threading
import time

import mesos_pb2 as mesos_pb

from twitter.common import log
from twitter.common.exceptions import ExceptionalThread
from twitter.common.quantity import Amount, Time

from gen.twitter.thermos.ttypes import TaskState

from .executor_base import ThermosExecutorBase


class StatusManager(ExceptionalThread):
  """
    An agent that periodically checks the health of a task via HealthInterfaces that
    provide HTTP health checking, resource consumption, etc.

    If any of the health managers return a false health check, the Status Manager is
    responsible for enforcing the "graceful" shutdown cycle via /quitquitquit and
    /abortabortabort.
  """

  POLL_WAIT = Amount(500, Time.MILLISECONDS)
  WAIT_LIMIT = Amount(1, Time.MINUTES)
  ESCALATION_WAIT = Amount(5, Time.SECONDS)
  PERSISTENCE_WAIT = Amount(5, Time.SECONDS)

  def __init__(self, runner, driver, task_id, health_checkers=(), signaler=None, clock=time):
    self._driver = driver
    self._runner = runner
    self._task_id = task_id
    self._clock = clock
    self._unhealthy_event = threading.Event()
    self._signaler = signaler
    self._health_checkers = health_checkers
    super(StatusManager, self).__init__()
    self.daemon = True

  def _stop_health_checkers(self):
    if self._unhealthy_event.is_set():
      return
    self._unhealthy_event.set()
    for checker in self._health_checkers:
      log.debug('Terminating %s' % checker)
      checker.stop()

  def run(self):
    for checker in self._health_checkers:
      log.debug("Starting checker: %s" % checker.__class__.__name__)
      checker.start()

    failure_reason = None

    while self._runner.is_alive() and failure_reason is None:
      for checker in self._health_checkers:
        if not checker.healthy:
          self._stop_health_checkers()
          failure_reason = checker.failure_reason
          log.info('Got %s from %s' % (failure_reason, checker.__class__.__name__))
          break
      else:
        self._clock.sleep(self.POLL_WAIT.as_(Time.SECONDS))

    log.info('Executor polling thread detected termination condition.')
    self.terminate(failure_reason)

  def _terminate_http(self):
    if not self._signaler:
      return

    # pass 1
    self._signaler.quitquitquit()
    self._clock.sleep(self.ESCALATION_WAIT.as_(Time.SECONDS))
    if not self._runner.is_alive():
      return True

    # pass 2
    self._signaler.abortabortabort()
    self._clock.sleep(self.ESCALATION_WAIT.as_(Time.SECONDS))
    if not self._runner.is_alive():
      return True

  def _terminate_runner(self):
    self._runner.kill()

  def _wait_for_rebind(self):
    # TODO(wickman) MESOS-438
    #
    # There is a legit race condition here.  If we catch the is_alive latch
    # down at exactly the right time, there are no proper waits to wait for
    # rebinding to the executor by a third party killing process.
    #
    # While kills in production should be rare, we should monitor the
    # task_state for 60 seconds until it is in a terminal state. If it never
    # reaches a terminal state, then we could either:
    #    1) issue the kills ourself and send a LOST message
    # or 2) send a rebind_task call to the executor and have it attempt to
    #       re-take control of the executor.  perhaps with a max bind
    #       limit before going with route #1.
    wait_limit = self.WAIT_LIMIT
    while wait_limit > Amount(0, Time.SECONDS):
      current_state = self._runner.task_state()
      log.info('Waiting for terminal state, current state: %s' %
        TaskState._VALUES_TO_NAMES.get(current_state, '(unknown)'))
      if ThermosExecutorBase.thermos_status_is_terminal(current_state):
        log.info('Terminal reached, breaking')
        break
      self._clock.sleep(self.POLL_WAIT.as_(Time.SECONDS))
      wait_limit -= self.POLL_WAIT

  def terminate(self, failure_reason=None):
    if not self._terminate_http():
      self._terminate_runner()

    self._wait_for_rebind()

    last_state = self._runner.task_state()
    log.info("State we've accepted: Thermos(%s) / Failure: %s" % (
        TaskState._VALUES_TO_NAMES.get(last_state, 'unknown'),
        failure_reason))

    finish_state = None
    if last_state == TaskState.ACTIVE:
      log.error("Runner is dead but task state unexpectedly ACTIVE!")
      # TODO(wickman) This is a potentially dangerous operation.
      # If the status_manager caught the is_alive latch on down, then we should be
      # safe because the task_runner_wrapper will have the same view and won't block.
      self._runner.quitquitquit()
      finish_state = mesos_pb.TASK_LOST
    else:
      try:
        finish_state = ThermosExecutorBase.THERMOS_TO_MESOS_STATES[last_state]
      except KeyError:
        log.error("Unknown task state = %r!" % last_state)
        finish_state = mesos_pb.TASK_FAILED

    update = mesos_pb.TaskStatus()
    update.task_id.value = self._task_id
    update.state = failure_reason.status if failure_reason is not None else finish_state
    if failure_reason and failure_reason.reason:
      update.message = failure_reason.reason
    task_state = mesos_pb._TASKSTATE.values_by_number.get(update.state)
    log.info('Sending terminal state update: %s' % (task_state.name if task_state else 'UNKNOWN'))
    self._driver.sendStatusUpdate(update)

    self._stop_health_checkers()

    # the executor is ephemeral and we just submitted a terminal task state, so shutdown
    log.info('Stopping executor.')
    self._runner.cleanup()

    # TODO(wickman) Remove this once external MESOS-243 is resolved.
    log.info('Sleeping briefly to mitigate https://issues.apache.org/jira/browse/MESOS-243')
    self._clock.sleep(self.PERSISTENCE_WAIT.as_(Time.SECONDS))
    self._driver.stop()
